package lab.doppio.bloom.core.usecase

import lab.doppio.bloom.core.model.Contact
import lab.doppio.bloom.core.model.EnrollmentStatus
import lab.doppio.bloom.core.model.Offer
import lab.doppio.bloom.core.port.AccountRepository
import lab.doppio.bloom.core.port.EventRepository
import lab.doppio.bloom.core.port.FactRepository
import lab.doppio.bloom.core.port.ReplyRepository
import lab.doppio.bloom.core.port.SequenceRepository

/**
 * Enrolls a contact in the sequence of the recommended offer. The only place where enrollment is decided,
 * for the manual button and for the contacts added from the contact book.
 *
 * Safety guards: an address that opted out is never enrolled, on any path; when the dossier concludes that
 * no offer fits, a human must choose the offer explicitly. The "already enrolled" guard depends on who asks:
 * the automatic path (contact book, email found by Dropcontact) never enrolls a contact who has any enrollment,
 * running or finished, so a contact is not written to twice on its own; a human (the enroll button) can start
 * a new sequence for a contact whose previous one is over, and is refused only while one is ACTIVE or PAUSED.
 */
class EnrollContact(
    private val sequenceRepo: SequenceRepository,
    private val replyRepo: ReplyRepository,
    private val factRepo: FactRepository,
    private val accountRepo: AccountRepository,
    private val eventRepo: EventRepository,
    private val afterEnrolled: () -> Unit = {},
) {
    sealed interface Result {
        data class Enrolled(val enrollmentId: Long) : Result
        data object OptedOut : Result
        data object AlreadyEnrolled : Result
        data object NoFit : Result
        data object NoSequence : Result
    }

    /**
     * [offer]: the offer a human chose. Without it, the dossier decides.
     * [manual]: a human asked for this enrollment (see the class comment for what changes).
     */
    suspend operator fun invoke(contact: Contact, offer: Offer? = null, manual: Boolean = false): Result {
        if (contact.email != null && replyRepo.isSuppressed(contact.email!!)) return Result.OptedOut
        val existing = sequenceRepo.enrollmentsOfContact(contact.id)
        val blocking = if (manual) existing.filter { it.status in RUNNING } else existing
        if (blocking.isNotEmpty()) return Result.AlreadyEnrolled
        val dossier = factRepo.dossier(contact.accountId)
        if (offer == null && dossier?.noFit == true) return Result.NoFit
        val chosen = offer
            ?: dossier?.offer
            ?: accountRepo.accountsByScore(500).firstOrNull { it.account.id == contact.accountId }?.recommendedOffer
            ?: Offer.DISCOVERY
        val seq = sequenceRepo.sequenceFor(chosen) ?: sequenceRepo.sequenceFor(Offer.DISCOVERY) ?: return Result.NoSequence
        val id = sequenceRepo.enroll(contact.id, seq.id)
        eventRepo.logEvent("ENROLLED", contact.accountId, contact.id)
        afterEnrolled()
        return Result.Enrolled(id)
    }

    private companion object {
        val RUNNING = setOf(EnrollmentStatus.ACTIVE, EnrollmentStatus.PAUSED)
    }
}
