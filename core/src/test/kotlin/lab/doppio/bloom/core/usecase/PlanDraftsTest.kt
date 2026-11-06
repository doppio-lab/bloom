package lab.doppio.bloom.core.usecase

import kotlinx.coroutines.test.runTest
import lab.doppio.bloom.core.model.DraftStatus
import lab.doppio.bloom.core.model.EnrollmentStatus
import lab.doppio.bloom.core.model.SendingPolicy
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlanDraftsTest {
    private val drafts = FakeDraftRepository()
    private val sequences = FakeSequenceRepository()
    private val contacts = FakeContactRepository()
    private val replies = FakeReplyRepository()
    private val events = FakeEventRepository()
    private val writer = FakeDrafter()
    private val contact = contacts.add()
    private val enrollment get() = sequences.enrollments.values.first()

    private fun plan() = PlanDrafts(sequences, drafts, contacts, replies, events, writer, StopContactSequences(sequences), SendingPolicy(), clockAt(WEDNESDAY_MORNING))

    @BeforeTest
    fun setUp() {
        sequences.sequences += emailSequence()
        sequences.addEnrollment(contact.id)
    }

    @Test
    fun `a due step is handed to the writer`() = runTest {
        plan()()
        assertEquals(listOf(enrollment.id to 0), writer.calls)
    }

    @Test
    fun `a step that already has an open draft is not written twice`() = runTest {
        drafts.add(enrollment.id, DraftStatus.PENDING_REVIEW, step = 0)
        plan()()
        assertTrue(writer.calls.isEmpty())
    }

    @Test
    fun `a suppressed contact stops its sequences instead of being written to`() = runTest {
        replies.suppressed[contact.email!!] = "opposition"
        plan()()
        assertTrue(writer.calls.isEmpty())
        assertEquals(EnrollmentStatus.STOPPED_UNSUBSCRIBE, sequences.status(enrollment.id))
    }

    @Test
    fun `three rejections of the same step stop the sequence`() = runTest {
        drafts.rejections[enrollment.id to 0] = 3
        plan()()
        assertTrue(writer.calls.isEmpty())
        assertEquals(EnrollmentStatus.STOPPED_MANUAL, sequences.status(enrollment.id))
        assertEquals(listOf("ENROLLMENT_STOPPED_REJECTIONS"), events.types)
    }

    @Test
    fun `two rejections still get a new draft`() = runTest {
        drafts.rejections[enrollment.id to 0] = 2
        plan()()
        assertEquals(1, writer.calls.size)
    }

    @Test
    fun `a writer failure backs off for 10 minutes then doubles`() = runTest {
        val plan = plan()
        writer.failure = RuntimeException("llm down")
        plan(WEDNESDAY_MORNING)
        writer.failure = null
        plan(WEDNESDAY_MORNING.plusSeconds(9 * 60)) // still backing off
        assertTrue(writer.calls.isEmpty())
        writer.failure = RuntimeException("llm down")
        plan(WEDNESDAY_MORNING.plusSeconds(10 * 60)) // retried, fails again: wait 20 min
        writer.failure = null
        plan(WEDNESDAY_MORNING.plusSeconds(29 * 60))
        assertTrue(writer.calls.isEmpty())
        plan(WEDNESDAY_MORNING.plusSeconds(31 * 60))
        assertEquals(1, writer.calls.size)
    }

    @Test
    fun `a finished sequence is marked completed`() = runTest {
        sequences.enrollments[enrollment.id] = enrollment.copy(nextStep = 5)
        plan()()
        assertEquals(EnrollmentStatus.COMPLETED, sequences.status(enrollment.id))
    }
}
