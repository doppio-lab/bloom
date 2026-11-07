package lab.doppio.bloom.core.usecase

import lab.doppio.bloom.core.discovery.Targeting
import lab.doppio.bloom.core.model.Account
import lab.doppio.bloom.core.model.Contact
import lab.doppio.bloom.core.port.ContactRepository
import lab.doppio.bloom.core.port.EmailFinder
import lab.doppio.bloom.core.port.EventRepository
import lab.doppio.bloom.core.port.ReplyRepository

/** Adds a person to an account, and starts the sequence right away when an email is known. */
class AddContact(
    private val contactRepo: ContactRepository,
    private val replyRepo: ReplyRepository,
    private val enrollContact: EnrollContact,
) {
    data class Request(
        val fullName: String, val role: String, val email: String? = null, val linkedinUrl: String? = null,
        val enroll: Boolean = true, val fromRegistry: Boolean = false,
    )

    /** [enrolled] is false when no email is known yet: the caller may search one with [FindContactEmail]. */
    data class Added(val contact: Contact, val enrolled: Boolean)

    suspend operator fun invoke(account: Account, req: Request): Added {
        require(req.fullName.isNotBlank() && req.role.isNotBlank()) { "Nom et rôle sont obligatoires" }
        req.email?.let { require(!replyRepo.isSuppressed(it)) { "Cette personne s'est opposée à la prospection" } }

        val contact = Contact(
            accountId = account.id,
            fullName = req.fullName.trim(),
            role = req.role.trim(),
            persona = Targeting.personaFromRole(req.role),
            email = req.email?.trim()?.takeIf { it.contains('@') },
            linkedinUrl = req.linkedinUrl?.trim()?.takeIf { it.startsWith("https://") },
            dataSource = if (req.fromRegistry) "Annuaire des entreprises (dirigeant publié au registre)" else "saisie manuelle (profil public LinkedIn ou site de l'entreprise)",
        )
        val saved = contact.copy(id = contactRepo.addContact(contact))
        val enrolled = saved.email != null && req.enroll && enrollContact(saved) is EnrollContact.Result.Enrolled
        return Added(saved, enrolled)
    }
}

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
            contactRepo.updateContactEmail(contact.id, found.email, "Dropcontact (${found.qualification}) à partir du nom saisi")
            eventRepo.logEvent("EMAIL_FOUND", account.id, contact.id)
            if (enroll) contactRepo.contact(contact.id)?.let { enrollContact(it) }
        } else {
            eventRepo.logEvent("EMAIL_NOT_FOUND", account.id, contact.id)
        }
    }
}
