package lab.doppio.bloom.core.usecase

import kotlinx.coroutines.test.runTest
import lab.doppio.bloom.core.model.Channel
import lab.doppio.bloom.core.model.DraftStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class CompleteTaskTest {
    private val drafts = FakeDraftRepository()
    private val sequences = FakeSequenceRepository()
    private val contacts = FakeContactRepository()
    private val events = FakeEventRepository()
    private val complete = CompleteTask(drafts, sequences, contacts, events)

    private val contact = contacts.add()
    private val enrollment = sequences.addEnrollment(contact.id, nextStep = 1)

    @Test
    fun `an approved LinkedIn task done by hand advances the sequence`() = runTest {
        val task = drafts.add(enrollment.id, DraftStatus.APPROVED, step = 1, channel = Channel.LINKEDIN)
        assertEquals(CompleteTask.Result.DONE, complete(task.id))
        assertEquals(DraftStatus.TASK_DONE, drafts.status(task.id))
        assertEquals(2, sequences.enrollment(enrollment.id)!!.nextStep)
        assertEquals(listOf("LINKEDIN_TASK_DONE"), events.types)
    }

    @Test
    fun `an email draft is not a task`() = runTest {
        val email = drafts.add(enrollment.id, DraftStatus.APPROVED, channel = Channel.EMAIL)
        assertEquals(CompleteTask.Result.NOT_A_PENDING_TASK, complete(email.id))
        assertEquals(DraftStatus.APPROVED, drafts.status(email.id))
    }

    @Test
    fun `a task that is not approved or already done is refused`() = runTest {
        val pending = drafts.add(enrollment.id, DraftStatus.PENDING_REVIEW, channel = Channel.LINKEDIN)
        val done = drafts.add(enrollment.id, DraftStatus.TASK_DONE, channel = Channel.LINKEDIN)
        assertEquals(CompleteTask.Result.NOT_A_PENDING_TASK, complete(pending.id))
        assertEquals(CompleteTask.Result.NOT_A_PENDING_TASK, complete(done.id))
        assertEquals(1, sequences.enrollment(enrollment.id)!!.nextStep)
    }

    @Test
    fun `an unknown task is not found`() = runTest {
        assertEquals(CompleteTask.Result.NOT_FOUND, complete(42))
    }
}
