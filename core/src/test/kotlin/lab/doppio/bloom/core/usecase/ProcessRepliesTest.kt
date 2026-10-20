package lab.doppio.bloom.core.usecase

import kotlinx.coroutines.test.runTest
import lab.doppio.bloom.core.model.DraftStatus
import lab.doppio.bloom.core.model.EnrollmentStatus
import lab.doppio.bloom.core.model.EventType
import lab.doppio.bloom.core.model.ReplyIntent
import lab.doppio.bloom.core.model.SendingPolicy
import java.time.Instant
import java.time.LocalDate
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

    private fun process() = ProcessReplies(inbox, classifier, replies, drafts, sequences, contacts, events, StopContactSequences(sequences), PostponeContactSequences(sequences, drafts, SendingPolicy()))

    @Test
    fun `a bounce suppresses the contact of the original email, found by its message id`() = runTest {
        val other = contacts.add(email = "bounced@client.test")
        val e = sequences.addEnrollment(other.id)
        drafts.messageIdToEnrollment["<orig@doppio.test>"] = e.id
        inbox.mails = listOf(inbound(1, from = "mailer-daemon@mx.test", bounce = true, inReplyTo = listOf("<orig@doppio.test>")))
        process()()
        assertTrue("bounced@client.test" in replies.suppressed)
        assertEquals(EnrollmentStatus.STOPPED_BOUNCE, sequences.status(e.id))
        assertEquals(listOf(EventType.BOUNCED, EventType.REPLY_RECEIVED), events.types)
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
        assertEquals(listOf(EventType.UNSUBSCRIBED, EventType.REPLY_RECEIVED), events.types)
    }

    @Test
    fun `an out-of-office reply resumes the sequence after the return date instead of pausing it for good`() = runTest {
        val e = sequences.addEnrollment(contact.id)
        val open = drafts.add(e.id, DraftStatus.APPROVED, step = 0)
        classifier.intent = ReplyIntent.OUT_OF_OFFICE
        classifier.followUpOn = LocalDate.parse("2026-11-16")
        inbox.mails = listOf(inbound(1))
        process()()
        assertEquals(EnrollmentStatus.ACTIVE, sequences.status(e.id))
        // Midnight of Monday 16 Nov 2026 in Paris (UTC+1): the next step is counted from there
        assertEquals("2026-11-15T23:00:00Z", sequences.enrollments.getValue(e.id).lastActionAt)
        assertEquals(mapOf(open.id to Instant.parse("2026-11-15T23:00:00Z")), drafts.sendAfter, "an open draft leaves no sooner")
        assertEquals(listOf(EventType.REPLY_OUT_OF_OFFICE, EventType.REPLY_RECEIVED), events.types)
    }

    @Test
    fun `an out-of-office reply without a return date postpones by two weeks, a paused sequence is resumed`() = runTest {
        val e = sequences.addEnrollment(contact.id, EnrollmentStatus.PAUSED)
        classifier.intent = ReplyIntent.OUT_OF_OFFICE
        inbox.mails = listOf(inbound(1))
        process()()
        assertEquals(EnrollmentStatus.ACTIVE, sequences.status(e.id))
        assertEquals(WEDNESDAY_MORNING.plusSeconds(14 * 24 * 3600).toString(), sequences.enrollments.getValue(e.id).lastActionAt)
    }

    @Test
    fun `a reply that keeps failing is retried twice, then marked seen and logged`() = runTest {
        classifier.failure = IllegalStateException("corps illisible")
        inbox.mails = listOf(inbound(1))
        val process = process()
        process()
        process()
        assertTrue(inbox.seen.isEmpty(), "the first two passes leave it unread")
        assertTrue(events.types.isEmpty())

        process()
        assertEquals(listOf(1L), inbox.seen[mailboxA.address], "the third pass gives up on it")
        assertEquals(listOf(EventType.REPLY_UNREADABLE), events.types)
        assertEquals("""{"key":"<m1@client.test>","from":"jane@client.test"}""", events.events.single().second)
    }

    @Test
    fun `a failure followed by a success starts the count again`() = runTest {
        inbox.mails = listOf(inbound(1))
        val process = process()
        classifier.failure = IllegalStateException("boom")
        process()
        process()
        classifier.failure = null
        process()
        assertEquals(listOf(1L), inbox.seen[mailboxA.address])
        assertTrue(EventType.REPLY_UNREADABLE !in events.types)
    }

    @Test
    fun `an unsubscribe from another address also suppresses the address we wrote to`() = runTest {
        val e = sequences.addEnrollment(contact.id)
        // The reply is threaded to our email by its Message-ID, whoever sends it
        drafts.messageIdToEnrollment["<orig@doppio.test>"] = e.id
        classifier.intent = ReplyIntent.UNSUBSCRIBE
        inbox.mails = listOf(inbound(1, from = "assistant@client.test", inReplyTo = listOf("<orig@doppio.test>")))
        process()()
        assertTrue("assistant@client.test" in replies.suppressed)
        assertTrue(contact.email!! in replies.suppressed, "the contact cannot be enrolled again at the address we used")
        assertEquals(2, replies.suppressed.size)
    }

    @Test
    fun `an unsubscribe from the contact's own address is suppressed once`() = runTest {
        sequences.addEnrollment(contact.id)
        classifier.intent = ReplyIntent.UNSUBSCRIBE
        inbox.mails = listOf(inbound(1, from = contact.email!!.uppercase()))
        process()()
        assertEquals(1, replies.suppressed.size)
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
        assertEquals(listOf(EventType.REPLY_POSITIVE, EventType.REPLY_RECEIVED), events.types)
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
