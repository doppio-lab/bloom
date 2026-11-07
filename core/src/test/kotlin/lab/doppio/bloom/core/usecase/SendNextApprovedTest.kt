package lab.doppio.bloom.core.usecase

import kotlinx.coroutines.test.runTest
import lab.doppio.bloom.core.model.Contact
import lab.doppio.bloom.core.model.DraftStatus
import lab.doppio.bloom.core.model.EnrollmentStatus
import lab.doppio.bloom.core.model.SendingPolicy
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SendNextApprovedTest {
    private val policy = SendingPolicy(maxPerMailboxPerDay = 2, minSecondsBetweenSends = 180)
    private val drafts = FakeDraftRepository()
    private val sequences = FakeSequenceRepository()
    private val contacts = FakeContactRepository()
    private val replies = FakeReplyRepository()
    private val events = FakeEventRepository()
    private val sender = FakeMailSender()
    private val contact: Contact = contacts.add()
    private val enrollment = sequences.addEnrollment(contact.id)
    private val draft = drafts.add(enrollment.id, DraftStatus.APPROVED, body = "Bonjour [F:abc]")

    private fun send(now: Instant = WEDNESDAY_MORNING, mailboxes: List<lab.doppio.bloom.core.model.Mailbox> = listOf(mailboxA)) =
        SendNextApproved(drafts, sequences, contacts, replies, events, sender, mailboxes, policy, clockAt(now))

    @Test
    fun `outside the sending window nothing is sent`() = runTest {
        send(Instant.parse("2026-11-06T09:00:00Z"))() // Friday
        assertTrue(sender.sent.isEmpty())
        assertEquals(DraftStatus.APPROVED, drafts.status(draft.id))
    }

    @Test
    fun `a mailbox at its daily quota sends nothing`() = runTest {
        drafts.sentToday[mailboxA.address] = 2
        send()()
        assertTrue(sender.sent.isEmpty())
    }

    @Test
    fun `the next mailbox is used when the first is at its quota`() = runTest {
        drafts.sentToday[mailboxA.address] = 2
        send(mailboxes = listOf(mailboxA, mailboxB))()
        assertEquals(mailboxB, sender.sent.single().mailbox)
    }

    @Test
    fun `the minimum spacing between two sends is respected`() = runTest {
        drafts.lastSent[mailboxA.address] = WEDNESDAY_MORNING.minusSeconds(60)
        send()()
        assertTrue(sender.sent.isEmpty())
        drafts.lastSent[mailboxA.address] = WEDNESDAY_MORNING.minusSeconds(180)
        send()()
        assertEquals(1, sender.sent.size)
    }

    @Test
    fun `a suppressed recipient rejects the draft and stops the sequence`() = runTest {
        replies.suppressed[contact.email!!] = "opposition"
        send()()
        assertTrue(sender.sent.isEmpty())
        assertEquals(DraftStatus.REJECTED, drafts.status(draft.id))
        assertEquals(EnrollmentStatus.STOPPED_UNSUBSCRIBE, sequences.status(enrollment.id))
    }

    @Test
    fun `a draft of an enrollment that is no longer active is rejected`() = runTest {
        sequences.setEnrollmentStatus(enrollment.id, EnrollmentStatus.REPLIED)
        send()()
        assertTrue(sender.sent.isEmpty())
        assertEquals(DraftStatus.REJECTED, drafts.status(draft.id))
    }

    @Test
    fun `a lost claim sends nothing`() = runTest {
        drafts.claimWins = false
        send()()
        assertTrue(sender.sent.isEmpty())
        assertTrue(drafts.sent.isEmpty())
        assertTrue(events.events.isEmpty())
    }

    @Test
    fun `an SMTP failure is recorded and nothing is marked sent`() = runTest {
        sender.failure = RuntimeException("550 refused")
        send()()
        assertEquals(listOf(draft.id to "550 refused"), drafts.failures)
        assertTrue(drafts.sent.isEmpty())
        assertTrue(events.events.isEmpty())
    }

    @Test
    fun `a draft that fails for good logs EMAIL_FAILED`() = runTest {
        sender.failure = RuntimeException("boom")
        drafts.failureStatus = DraftStatus.FAILED
        send()()
        assertEquals(listOf("EMAIL_FAILED"), events.types)
    }

    @Test
    fun `success records the send with the new message id as thread, citations stripped`() = runTest {
        send()()
        val out = sender.sent.single()
        assertEquals(contact.email, out.to)
        assertEquals(false, "[F:" in out.body)
        assertEquals(null, out.inReplyTo)
        val (recorded, thread) = drafts.sent.single()
        assertEquals(draft.id, recorded.id)
        assertEquals(out.messageId, thread)
        assertEquals(listOf("EMAIL_SENT"), events.types)
    }

    @Test
    fun `a follow-up keeps the thread of the first message`() = runTest {
        sequences.threads[enrollment.id] = "<first@doppio.test>"
        send()()
        assertEquals("<first@doppio.test>", sender.sent.single().inReplyTo)
        assertEquals("<first@doppio.test>", drafts.sent.single().second)
    }
}
