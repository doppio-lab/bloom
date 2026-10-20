package lab.doppio.bloom.core.usecase

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import lab.doppio.bloom.core.model.ContactId
import lab.doppio.bloom.core.model.EnrollmentStatus
import lab.doppio.bloom.core.model.SendingPolicy
import lab.doppio.bloom.core.port.DraftRepository
import lab.doppio.bloom.core.port.SequenceRepository

/**
 * An out-of-office reply is not an answer: the contact is away. The running sequences resume after the return date
 * the reply gave, or [DEFAULT_ABSENCE_DAYS] days after the reply when it gave none, and the drafts waiting for
 * review or for their send leave no sooner. Nothing would resume a PAUSED enrollment otherwise.
 */
class PostponeContactSequences(
    private val sequenceRepo: SequenceRepository,
    private val draftRepo: DraftRepository,
    policy: SendingPolicy,
) {
    private val zone = ZoneId.of(policy.timezone)

    /** Returns how many enrollments were postponed. */
    suspend operator fun invoke(contactId: ContactId, returnsOn: LocalDate?, repliedAt: Instant): Int {
        val until = returnsOn?.atStartOfDay(zone)?.toInstant() ?: repliedAt.plus(DEFAULT_ABSENCE_DAYS, ChronoUnit.DAYS)
        val running = sequenceRepo.enrollmentsOfContact(contactId).filter { it.status in RUNNING }
        running.forEach {
            sequenceRepo.postponeEnrollment(it.id, until)
            draftRepo.holdOpenDrafts(it.id, until)
        }
        return running.size
    }

    companion object {
        /** Away with no return date given: try again after two weeks. */
        const val DEFAULT_ABSENCE_DAYS = 14L

        private val RUNNING = setOf(EnrollmentStatus.ACTIVE, EnrollmentStatus.PAUSED)
    }
}
