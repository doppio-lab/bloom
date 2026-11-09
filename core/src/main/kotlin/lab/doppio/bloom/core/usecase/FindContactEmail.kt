package lab.doppio.bloom.core.usecase

import lab.doppio.bloom.core.model.Account
import lab.doppio.bloom.core.model.Contact
import lab.doppio.bloom.core.model.EventType
import lab.doppio.bloom.core.port.ContactRepository
import lab.doppio.bloom.core.port.EmailFinder
import lab.doppio.bloom.core.port.EventRepository

/** Looks the email of a contact up (Dropcontact answers in one to three minutes), then enrolls the contact if asked. */
class FindContactEmail(
    private val finder: EmailFinder,
    private val contactRepo: ContactRepository,
    private val eventRepo: EventRepository,
    private val enrollContact: EnrollContact,
) {
    suspend operator fun invoke(contact: Contact, account: Account, enroll: Boolean) {
        val found = finder.findEmail(contact.fullName, account.name, account.domain)
        if (found != null) {
            contactRepo.updateContactEmail(contact.id, found.email, UseCaseMessages.sourceDropcontact(found.qualification))
            eventRepo.logEvent(EventType.EMAIL_FOUND, account.id, contact.id)
            if (enroll) contactRepo.contact(contact.id)?.let { enrollContact(it) }
        } else {
            eventRepo.logEvent(EventType.EMAIL_NOT_FOUND, account.id, contact.id)
        }
    }
}
