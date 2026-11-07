package lab.doppio.bloom.core.usecase

import lab.doppio.bloom.core.model.ContactId
import lab.doppio.bloom.core.model.EnrollmentStatus
import lab.doppio.bloom.core.port.ContactRepository
import lab.doppio.bloom.core.port.EventRepository
import lab.doppio.bloom.core.port.ReplyRepository
import lab.doppio.bloom.core.port.SequenceRepository

/**
 * The one way to stop what a contact is receiving. Only sequences still running (ACTIVE or PAUSED) change:
 * a COMPLETED or REPLIED enrollment is history and keeps its status.
 */
class StopContactSequences(private val sequenceRepo: SequenceRepository) {
    /** Returns how many enrollments were stopped. */
    suspend operator fun invoke(contactId: ContactId, status: EnrollmentStatus): Int {
        val running = sequenceRepo.enrollmentsOfContact(contactId).filter { it.status in RUNNING }
        running.forEach { sequenceRepo.setEnrollmentStatus(it.id, status) }
        return running.size
    }

    private companion object {
        val RUNNING = setOf(EnrollmentStatus.ACTIVE, EnrollmentStatus.PAUSED)
    }
}

/** The contact followed the unsubscribe link: the address is suppressed and every running sequence stops. */
class UnsubscribeContact(
    private val contactRepo: ContactRepository,
    private val replyRepo: ReplyRepository,
    private val eventRepo: EventRepository,
    private val stop: StopContactSequences,
) {
    suspend operator fun invoke(contactId: ContactId) {
        val contact = contactRepo.contact(contactId)
        contact?.email?.let { replyRepo.suppress(it, "lien de désinscription") }
        contact?.let { stop(it.id, EnrollmentStatus.STOPPED_UNSUBSCRIBE) }
        eventRepo.logEvent("UNSUBSCRIBED", contact?.accountId, contactId)
    }
}
