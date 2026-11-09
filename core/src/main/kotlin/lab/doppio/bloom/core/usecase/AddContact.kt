package lab.doppio.bloom.core.usecase

import lab.doppio.bloom.core.discovery.Targeting
import lab.doppio.bloom.core.model.Account
import lab.doppio.bloom.core.model.Contact
import lab.doppio.bloom.core.port.ContactRepository
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
        require(req.fullName.isNotBlank() && req.role.isNotBlank()) { UseCaseMessages.NAME_AND_ROLE_REQUIRED }
        req.email?.let { require(!replyRepo.isSuppressed(it)) { UseCaseMessages.PERSON_OPTED_OUT } }

        val contact = Contact(
            accountId = account.id,
            fullName = req.fullName.trim(),
            role = req.role.trim(),
            persona = Targeting.personaFromRole(req.role),
            email = req.email?.trim()?.takeIf { it.contains('@') },
            linkedinUrl = req.linkedinUrl?.trim()?.takeIf { it.startsWith("https://") },
            dataSource = if (req.fromRegistry) UseCaseMessages.SOURCE_REGISTRY_OFFICER else UseCaseMessages.SOURCE_MANUAL_ENTRY,
        )
        val saved = contact.copy(id = contactRepo.addContact(contact))
        val enrolled = saved.email != null && req.enroll && enrollContact(saved) is EnrollContact.Result.Enrolled
        return Added(saved, enrolled)
    }
}
