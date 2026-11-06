package lab.doppio.bloom.core.usecase

import kotlinx.coroutines.test.runTest
import lab.doppio.bloom.core.model.EnrollmentStatus
import lab.doppio.bloom.core.model.ReplyIntent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProcessRepliesTest {
    private val inbox = FakeInbox()
    private val classifier = FakeClassifier()
    private val replies = FakeReplyRepository()
    private val drafts = FakeDraftRepository()
    private val sequences = FakeSequenceRepository()
    private val contacts = FakeContactRepository()
    private val events = FakeEventRepository()
    private val contact = contacts.add()

    private fun process() = ProcessReplies(inbox, classifier, replies, drafts, sequences, contacts, events, StopContactSequences(sequences))

    @Test
    fun `a bounce suppresses the contact of the original email, found by its message id`() = runTest {
        val other = contacts.add(email = "bounced@client.test")
        val e = sequences.addEnrollment(other.id)
        drafts.messageIdToEnrollment["<orig@doppio.test>"] = e.id
        inbox.mails = listOf(inbound(1, from = "mailer-daemon@mx.test", bounce = true, inReplyTo = listOf("<orig@doppio.test>")))
        process()()
        assertTrue("bounced@client.test" in replies.suppressed)
        assertEquals(EnrollmentStatus.STOPPED_BOUNCE, sequences.status(e.id))
        assertEquals(listOf("BOUNCED", "REPLY_RECEIVED"), events.types)
        assertEquals(listOf(1L), inbox.seen[mailboxA.address])
    }

    @Test
    fun `an unsubscribe suppresses the sender and stops the sequence`() = runTest {
        val e = sequences.addEnrollment(contact.id)
        classifier.intent = ReplyIntent.UNSUBSCRIBE
        inbox.mails = listOf(inbound(1))
        process()()
        assertTrue(contact.email!! in replies.suppressed)
        assertEquals(EnrollmentStatus.STOPPED_UNSUBSCRIBE, sequences.status(e.id))
        assertEquals(listOf("UNSUBSCRIBED", "REPLY_RECEIVED"), events.types)
    }

    @Test
    fun `a reply stops running sequences only, finished ones keep their status`() = runTest {
        val active = sequences.addEnrollment(contact.id, EnrollmentStatus.ACTIVE)
        val paused = sequences.addEnrollment(contact.id, EnrollmentStatus.PAUSED)
        val completed = sequences.addEnrollment(contact.id, EnrollmentStatus.COMPLETED)
        val bounced = sequences.addEnrollment(contact.id, EnrollmentStatus.STOPPED_BOUNCE)
        classifier.intent = ReplyIntent.INTERESTED
        inbox.mails = listOf(inbound(1))
        process()()
        assertEquals(EnrollmentStatus.REPLIED, sequences.status(active.id))
        assertEquals(EnrollmentStatus.REPLIED, sequences.status(paused.id))
        assertEquals(EnrollmentStatus.COMPLETED, sequences.status(completed.id))
        assertEquals(EnrollmentStatus.STOPPED_BOUNCE, sequences.status(bounced.id))
        assertEquals(listOf("REPLY_POSITIVE", "REPLY_RECEIVED"), events.types)
        assertEquals(listOf(contact.id), contacts.inbound)
    }

    @Test
    fun `a message that fails stays unread and nothing is recorded`() = runTest {
        classifier.failure = RuntimeException("llm down")
        inbox.mails = listOf(inbound(1), inbound(2, from = "bounce", bounce = true))
        process()()
        assertEquals(listOf(2L), inbox.seen[mailboxA.address]) // the bounce needs no classifier
        assertEquals(listOf(ReplyIntent.BOUNCE), replies.saved)
    }

    @Test
    fun `a message already recorded is skipped but still marked seen`() = runTest {
        replies.recordedKeys += "<m1@client.test>"
        inbox.mails = listOf(inbound(1))
        process()()
        assertTrue(replies.saved.isEmpty())
        assertEquals(listOf(1L), inbox.seen[mailboxA.address])
    }
}
