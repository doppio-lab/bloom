package lab.doppio.bloom.core.usecase

import kotlinx.coroutines.test.runTest
import lab.doppio.bloom.core.model.EnrollmentStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class StopContactSequencesTest {
    private val sequences = FakeSequenceRepository()
    private val contacts = FakeContactRepository()
    private val contact = contacts.add()
    private val stop = StopContactSequences(sequences)

    @Test
    fun `only ACTIVE and PAUSED enrollments are stopped, history is preserved`() = runTest {
        val statuses = EnrollmentStatus.entries.associateWith { sequences.addEnrollment(contact.id, it).id }
        val stopped = stop(contact.id, EnrollmentStatus.STOPPED_UNSUBSCRIBE)
        assertEquals(2, stopped)
        assertEquals(EnrollmentStatus.STOPPED_UNSUBSCRIBE, sequences.status(statuses.getValue(EnrollmentStatus.ACTIVE)))
        assertEquals(EnrollmentStatus.STOPPED_UNSUBSCRIBE, sequences.status(statuses.getValue(EnrollmentStatus.PAUSED)))
        for (history in listOf(EnrollmentStatus.REPLIED, EnrollmentStatus.COMPLETED, EnrollmentStatus.STOPPED_BOUNCE, EnrollmentStatus.STOPPED_MANUAL)) {
            assertEquals(history, sequences.status(statuses.getValue(history)))
        }
    }

    @Test
    fun `other contacts are untouched`() = runTest {
        val other = contacts.add(email = "other@client.test")
        val e = sequences.addEnrollment(other.id)
        stop(contact.id, EnrollmentStatus.STOPPED_MANUAL)
        assertEquals(EnrollmentStatus.ACTIVE, sequences.status(e.id))
    }

    @Test
    fun `the unsubscribe link suppresses the address, stops running sequences and keeps history`() = runTest {
        val replies = FakeReplyRepository()
        val events = FakeEventRepository()
        val running = sequences.addEnrollment(contact.id)
        val done = sequences.addEnrollment(contact.id, EnrollmentStatus.COMPLETED)
        UnsubscribeContact(contacts, replies, events, stop)(contact.id)
        assertEquals(true, contact.email!! in replies.suppressed)
        assertEquals(EnrollmentStatus.STOPPED_UNSUBSCRIBE, sequences.status(running.id))
        assertEquals(EnrollmentStatus.COMPLETED, sequences.status(done.id))
        assertEquals(listOf("UNSUBSCRIBED"), events.types)
    }
}
