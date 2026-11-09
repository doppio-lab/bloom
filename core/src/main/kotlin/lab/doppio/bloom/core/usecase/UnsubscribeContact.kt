package lab.doppio.bloom.core.usecase

import lab.doppio.bloom.core.model.ContactId
import lab.doppio.bloom.core.model.EnrollmentStatus
import lab.doppio.bloom.core.model.EventType
import lab.doppio.bloom.core.port.ContactRepository
import lab.doppio.bloom.core.port.EventRepository
import lab.doppio.bloom.core.port.ReplyRepository

/** The contact followed the unsubscribe link: the address is suppressed and every running sequence stops. */
class UnsubscribeContact(
    private val contactRepo: ContactRepository,
    private val replyRepo: ReplyRepository,
    private val eventRepo: EventRepository,
    private val stop: StopContactSequences,
) {
    suspend operator fun invoke(contactId: ContactId) {
        val contact = contactRepo.contact(contactId)
        contact?.email?.let { replyRepo.suppress(it, UseCaseMessages.SUPPRESSED_UNSUBSCRIBE_LINK) }
        contact?.let { stop(it.id, EnrollmentStatus.STOPPED_UNSUBSCRIBE) }
        eventRepo.logEvent(EventType.UNSUBSCRIBED, contact?.accountId, contactId)
    }
}
