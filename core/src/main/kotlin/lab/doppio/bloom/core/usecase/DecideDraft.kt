package lab.doppio.bloom.core.usecase

import lab.doppio.bloom.core.guard.GuardInput
import lab.doppio.bloom.core.guard.MessageGuard
import lab.doppio.bloom.core.model.DraftStatus
import lab.doppio.bloom.core.port.AccountRepository
import lab.doppio.bloom.core.port.ContactRepository
import lab.doppio.bloom.core.port.DraftRepository
import lab.doppio.bloom.core.port.EventRepository
import lab.doppio.bloom.core.port.FactRepository
import lab.doppio.bloom.core.port.SequenceRepository

/** Human decision on a draft waiting for review. A draft decided elsewhere (other user, blacklist, sent) is never decided again. */
class DecideDraft(
    private val draftRepo: DraftRepository,
    private val sequenceRepo: SequenceRepository,
    private val contactRepo: ContactRepository,
    private val accountRepo: AccountRepository,
    private val factRepo: FactRepository,
    private val eventRepo: EventRepository,
) {
    sealed interface Result {
        data object Done : Result
        data object NotFound : Result
        data object NotPending : Result
        data class Violations(val messages: List<String>) : Result
    }

    /** Approves the draft, with the human's corrections if any. The guard applies to corrections too. */
    suspend fun approve(draftId: Long, subject: String? = null, body: String? = null): Result {
        val draft = draftRepo.draft(draftId) ?: return Result.NotFound
        if (draft.status != DraftStatus.PENDING_REVIEW) return Result.NotPending
        val e = checkNotNull(sequenceRepo.enrollment(draft.enrollmentId)) { "inscription introuvable" }
        val c = checkNotNull(contactRepo.contact(e.contactId)) { "contact introuvable" }
        val a = checkNotNull(accountRepo.account(c.accountId)) { "compte introuvable" }
        val seq = sequenceRepo.all().first { it.id == e.sequenceId }
        val violations = MessageGuard.check(
            GuardInput(
                draft.channel, draft.stepIndex, subject ?: draft.subject, body ?: draft.body, draft.citedFactIds,
                factRepo.factsOf(a.id), draft.referenceUsed, a.sector, seq.offer, c.language,
            ),
        )
        if (!MessageGuard.isPublishable(violations)) return Result.Violations(violations.map { it.toString() })
        draftRepo.decideDraft(draftId, DraftStatus.APPROVED, subject, body)
        eventRepo.logEvent(if (body != null) "DRAFT_EDITED_APPROVED" else "DRAFT_APPROVED", a.id, c.id, draftId)
        return Result.Done
    }

    suspend fun reject(draftId: Long): Result {
        val draft = draftRepo.draft(draftId) ?: return Result.NotFound
        if (draft.status != DraftStatus.PENDING_REVIEW) return Result.NotPending
        draftRepo.decideDraft(draftId, DraftStatus.REJECTED)
        eventRepo.logEvent("DRAFT_REJECTED", draftId = draftId)
        return Result.Done
    }
}
