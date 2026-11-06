package lab.doppio.bloom.core.usecase

import lab.doppio.bloom.core.model.Channel
import lab.doppio.bloom.core.model.DraftStatus
import lab.doppio.bloom.core.port.ContactRepository
import lab.doppio.bloom.core.port.DraftRepository
import lab.doppio.bloom.core.port.EventRepository
import lab.doppio.bloom.core.port.SequenceRepository

/** A LinkedIn or phone task done by hand moves its sequence to the next step. */
class CompleteTask(
    private val draftRepo: DraftRepository,
    private val sequenceRepo: SequenceRepository,
    private val contactRepo: ContactRepository,
    private val eventRepo: EventRepository,
) {
    enum class Result { DONE, NOT_FOUND, NOT_A_PENDING_TASK }

    suspend operator fun invoke(draftId: Long): Result {
        val draft = draftRepo.draft(draftId) ?: return Result.NOT_FOUND
        if (draft.status != DraftStatus.APPROVED || draft.channel == Channel.EMAIL) return Result.NOT_A_PENDING_TASK
        draftRepo.decideDraft(draftId, DraftStatus.TASK_DONE)
        sequenceRepo.advance(draft.enrollmentId, draft.stepIndex + 1)
        val e = sequenceRepo.enrollment(draft.enrollmentId)
        val c = e?.let { contactRepo.contact(it.contactId) }
        eventRepo.logEvent("${draft.channel}_TASK_DONE", c?.accountId, c?.id, draftId)
        return Result.DONE
    }
}
