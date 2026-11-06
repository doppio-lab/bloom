package lab.doppio.bloom.core.usecase

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import lab.doppio.bloom.core.model.EnrollmentStatus
import lab.doppio.bloom.core.model.SendingPolicy
import lab.doppio.bloom.core.port.ContactRepository
import lab.doppio.bloom.core.port.Drafter
import lab.doppio.bloom.core.port.DraftRepository
import lab.doppio.bloom.core.port.EventRepository
import lab.doppio.bloom.core.port.ReplyRepository
import lab.doppio.bloom.core.port.SequenceRepository
import lab.doppio.bloom.core.sequence.NextAction
import lab.doppio.bloom.core.sequence.SequenceEngine
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap

/**
 * Prepares the drafts that are due: asks the writer for the next step of every running enrollment, unless the
 * contact opted out, the step was rejected three times, or the writer failed recently.
 */
class PlanDrafts(
    private val sequenceRepo: SequenceRepository,
    private val draftRepo: DraftRepository,
    private val contactRepo: ContactRepository,
    private val replyRepo: ReplyRepository,
    private val eventRepo: EventRepository,
    private val writer: Drafter,
    private val stop: StopContactSequences,
    private val policy: SendingPolicy,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** One planning pass at a time: two passes would ask the writer twice for the same step. */
    private val planning = Mutex()

    /** Writer failures per (enrollment, step): the next try waits 10 min, 20 min... up to a day. */
    private val writerBackoff = ConcurrentHashMap<Pair<Long, Int>, Pair<Int, Instant>>()

    suspend operator fun invoke(now: Instant = clock.instant()) = planning.withLock { plan(now) }

    private suspend fun plan(now: Instant) {
        val sequences = sequenceRepo.all().associateBy { it.id }
        for (e in sequenceRepo.activeEnrollments()) {
            val seq = sequences[e.sequenceId] ?: continue
            when (val action = SequenceEngine.next(e, seq, policy, now)) {
                is NextAction.PrepareDraft -> if (!draftRepo.hasOpenDraft(e.id, action.step.index)) {
                    val contact = contactRepo.contact(e.contactId) ?: continue
                    if (contact.email != null && replyRepo.isSuppressed(contact.email)) {
                        stop(contact.id, EnrollmentStatus.STOPPED_UNSUBSCRIBE)
                        continue
                    }
                    // Trois brouillons rejetés pour la même étape : la séquence s'arrête au lieu d'en réécrire sans fin
                    if (draftRepo.rejectedDrafts(e.id, action.step.index) >= MAX_REJECTIONS_PER_STEP) {
                        sequenceRepo.setEnrollmentStatus(e.id, EnrollmentStatus.STOPPED_MANUAL)
                        eventRepo.logEvent("ENROLLMENT_STOPPED_REJECTIONS", contact.accountId, contact.id)
                        continue
                    }
                    val key = e.id to action.step.index
                    if (writerBackoff[key]?.second?.isAfter(now) == true) continue
                    try {
                        writer.draft(e, seq, action.step)
                        writerBackoff.remove(key)
                    } catch (ex: CancellationException) {
                        throw ex
                    } catch (ex: Exception) {
                        val failures = (writerBackoff[key]?.first ?: 0) + 1
                        val wait = minOf(10L shl (failures - 1).coerceAtMost(8), 24 * 60L)
                        writerBackoff[key] = failures to now.plus(wait, ChronoUnit.MINUTES)
                        log.warn("Brouillon impossible pour l'inscription ${e.id} ($failures échec(s), nouvel essai dans $wait min)", ex)
                    }
                }
                is NextAction.Finish -> if (action.status != e.status) sequenceRepo.setEnrollmentStatus(e.id, action.status)
                is NextAction.WaitUntil -> {}
            }
        }
    }

    private companion object {
        const val MAX_REJECTIONS_PER_STEP = 3
    }
}
