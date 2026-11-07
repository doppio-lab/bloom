package lab.doppio.bloom.core.usecase

import kotlinx.coroutines.CancellationException
import lab.doppio.bloom.core.guard.MessageGuard
import lab.doppio.bloom.core.model.DraftStatus
import lab.doppio.bloom.core.model.EnrollmentStatus
import lab.doppio.bloom.core.model.Mailbox
import lab.doppio.bloom.core.model.SendingPolicy
import lab.doppio.bloom.core.port.ContactRepository
import lab.doppio.bloom.core.port.DraftRepository
import lab.doppio.bloom.core.port.EventRepository
import lab.doppio.bloom.core.port.MailSender
import lab.doppio.bloom.core.port.ReplyRepository
import lab.doppio.bloom.core.port.SequenceRepository
import lab.doppio.bloom.core.sequence.SequenceEngine
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * Sends at most one approved email per pass: inside the sending window, under the daily cap of a mailbox,
 * after the minimum spacing, to a recipient who has not opted out. The draft is claimed before SMTP is called.
 */
class SendNextApproved(
    private val draftRepo: DraftRepository,
    private val sequenceRepo: SequenceRepository,
    private val contactRepo: ContactRepository,
    private val replyRepo: ReplyRepository,
    private val eventRepo: EventRepository,
    private val sender: MailSender,
    private val mailboxes: List<Mailbox>,
    private val policy: SendingPolicy,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val zone = ZoneId.of(policy.timezone)

    suspend operator fun invoke(now: Instant = clock.instant()) {
        if (!SequenceEngine.inWindow(now, policy)) return
        val startOfDay = now.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant()
        val draft = draftRepo.nextToSend(now) ?: return

        // Une seule boîte disponible par passage : on respecte l'écart minimal entre deux envois.
        val mailbox = mailboxes.firstOrNull { mb ->
            draftRepo.sentTodayBy(mb.address, startOfDay) < policy.maxPerMailboxPerDay &&
                (draftRepo.lastSentAt(mb.address)?.let { Duration.between(it, now).seconds >= policy.minSecondsBetweenSends } ?: true)
        } ?: return

        val enrollment = sequenceRepo.enrollment(draft.enrollmentId) ?: run { draftRepo.decideDraft(draft.id, DraftStatus.REJECTED); return }
        if (enrollment.status != EnrollmentStatus.ACTIVE) {
            draftRepo.decideDraft(draft.id, DraftStatus.REJECTED); return
        }
        val contact = contactRepo.contact(enrollment.contactId) ?: run { draftRepo.decideDraft(draft.id, DraftStatus.REJECTED); return }
        val to = contact.email ?: run { draftRepo.decideDraft(draft.id, DraftStatus.REJECTED); return }
        if (replyRepo.isSuppressed(to)) {
            draftRepo.decideDraft(draft.id, DraftStatus.REJECTED)
            sequenceRepo.setEnrollmentStatus(enrollment.id, EnrollmentStatus.STOPPED_UNSUBSCRIBE)
            return
        }

        // Réservé avant l'appel SMTP : après un crash, ce brouillon ne repart jamais tout seul.
        val messageId = sender.newMessageId(mailbox)
        if (!draftRepo.claimForSending(draft.id, messageId, mailbox.address)) return
        val thread = sequenceRepo.threadMessageId(enrollment.id)
        try {
            sender.send(
                mailbox = mailbox,
                to = to,
                subject = draft.subject.orEmpty(),
                body = MessageGuard.stripCitations(draft.body),
                contactId = contact.id,
                language = contact.language,
                inReplyTo = thread,
                messageId = messageId,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val status = draftRepo.recordSendFailure(draft.id, e.message ?: e.javaClass.simpleName, now)
            log.warn("Envoi du brouillon ${draft.id} refusé, statut $status", e)
            if (status == DraftStatus.FAILED) eventRepo.logEvent("EMAIL_FAILED", contact.accountId, contact.id, draft.id)
            return
        }
        draftRepo.recordSent(draft, threadMessageId = thread ?: messageId, now = now)
        eventRepo.logEvent("EMAIL_SENT", contact.accountId, contact.id, draft.id)
    }
}
