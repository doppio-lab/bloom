package lab.doppio.bloom.core.usecase

import lab.doppio.bloom.core.model.ContactId
import lab.doppio.bloom.core.model.EmailAddress
import lab.doppio.bloom.core.port.ContactRepository
import lab.doppio.bloom.core.port.ReplyRepository

/** The email of a contact typed on the board: stored, then the contact is enrolled as if a human had asked. */
class CompleteContactEmail(
    private val contactRepo: ContactRepository,
    private val replyRepo: ReplyRepository,
    private val enrollContact: EnrollContact,
) {
    sealed interface Result {
        data class Done(val enrollment: EnrollContact.Result) : Result
        data object NotFound : Result
    }

    suspend operator fun invoke(contactId: ContactId, email: String): Result {
        contactRepo.contact(contactId) ?: return Result.NotFound
        val address = EmailAddress.normalize(email) ?: throw IllegalArgumentException("Adresse email invalide")
        require(!replyRepo.isSuppressed(address)) { "Cette personne s'est opposée à la prospection" }
        contactRepo.updateContactEmail(contactId, address, SOURCE)
        return Result.Done(enrollContact(contactRepo.contact(contactId)!!, manual = true))
    }

    private companion object {
        const val SOURCE = "saisie manuelle (profil public LinkedIn ou site de l'entreprise)"
    }
}
