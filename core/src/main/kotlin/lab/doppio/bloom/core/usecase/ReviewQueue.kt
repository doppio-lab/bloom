package lab.doppio.bloom.core.usecase

import kotlinx.serialization.Serializable
import lab.doppio.bloom.core.model.Draft
import lab.doppio.bloom.core.model.DraftStatus
import lab.doppio.bloom.core.model.Fact
import lab.doppio.bloom.core.port.AccountRepository
import lab.doppio.bloom.core.port.ContactRepository
import lab.doppio.bloom.core.port.DraftRepository
import lab.doppio.bloom.core.port.FactRepository
import lab.doppio.bloom.core.port.SequenceRepository

@Serializable data class DraftView(val draft: Draft, val contactName: String, val contactRole: String, val accountName: String, val facts: List<Fact>, val linkedinUrl: String?)

/** Drafts of a status with the person, the company and the facts they cite, for the review screen. */
class ReviewQueue(
    private val draftRepo: DraftRepository,
    private val sequenceRepo: SequenceRepository,
    private val contactRepo: ContactRepository,
    private val accountRepo: AccountRepository,
    private val factRepo: FactRepository,
) {
    suspend operator fun invoke(status: DraftStatus = DraftStatus.PENDING_REVIEW): List<DraftView> =
        draftRepo.draftsByStatus(status).mapNotNull { d ->
            val e = sequenceRepo.enrollment(d.enrollmentId) ?: return@mapNotNull null
            val c = contactRepo.contact(e.contactId) ?: return@mapNotNull null
            val a = accountRepo.account(c.accountId) ?: return@mapNotNull null
            DraftView(d, c.fullName, c.role, a.name, factRepo.factsOf(a.id).filter { it.id in d.citedFactIds || "[F:${it.id}]" in d.body }, c.linkedinUrl)
        }
}
