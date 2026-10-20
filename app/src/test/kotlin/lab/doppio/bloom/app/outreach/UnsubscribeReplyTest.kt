package lab.doppio.bloom.app.outreach

import kotlinx.coroutines.test.runTest
import lab.doppio.bloom.app.TestDatabase
import lab.doppio.bloom.core.model.Account
import lab.doppio.bloom.core.model.Channel
import lab.doppio.bloom.core.model.Contact
import lab.doppio.bloom.core.model.Draft
import lab.doppio.bloom.core.model.DraftStatus
import lab.doppio.bloom.core.model.EnrollmentStatus
import lab.doppio.bloom.core.model.Persona
import lab.doppio.bloom.core.sequence.DefaultSequences
import lab.doppio.bloom.data.db.Repository
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** An inbox that hands out the given messages once and remembers what was marked seen. */
class FakeInbox(private val messages: List<InboundMail>) : MailInbox {
    val seen = mutableMapOf<String, List<Long>>()
    private var delivered = false
    override suspend fun poll(): List<InboundMail> = if (delivered) emptyList() else messages.also { delivered = true }
    override suspend fun markSeen(mailboxAddress: String, uids: List<Long>) { seen[mailboxAddress] = uids }
}

class UnsubscribeReplyTest {
    private val repo = Repository()
    private val mailbox = Mailbox("a@doppio-studio.fr", "pw", "A")

    @BeforeTest
    fun clean() = TestDatabase.reset()

    @Test
    fun `an unsubscribe reply from another address also suppresses the contact's own address`() = runTest {
        repo.ensureSequences(DefaultSequences.all())
        val accountId = repo.upsertAccount(Account(name = "Velomoov"))
        val contactId = repo.addContact(Contact(accountId = accountId, fullName = "Alex Martin", role = "CTO", persona = Persona.CTO, email = "alex@velomoov.fr", dataSource = "test"))
        val enrollmentId = repo.enroll(contactId, repo.sequences().first().id)
        val draft = Draft(enrollmentId = enrollmentId, stepIndex = 0, channel = Channel.EMAIL, subject = "Objet", body = "Corps",
            citedFactIds = emptyList(), referenceUsed = null, rationale = "test", status = DraftStatus.APPROVED)
        val sent = draft.copy(id = repo.createDraft(draft))
        repo.claimForSending(sent.id, "<sent-1@doppio-studio.fr>", mailbox.address)
        repo.recordSent(sent, threadMessageId = "<sent-1@doppio-studio.fr>")

        val reply = InboundMail(mailbox.address, "alex.martin@velomoov-group.com", "Re: Objet", "Merci de me désinscrire, stop.",
            listOf("<sent-1@doppio-studio.fr>"), Instant.parse("2026-10-13T09:00:00Z"), isBounce = false, uid = 7, key = "<reply-1@velomoov-group.com>")
        val inbox = FakeInbox(listOf(reply))
        val orchestrator = TestOrchestrator.build(repo, FakeSender(), TestOrchestrator.mailConfig(mailbox), inbox = inbox)

        orchestrator.processReplies()

        assertTrue(repo.isSuppressed("alex.martin@velomoov-group.com"), "the address that wrote")
        assertTrue(repo.isSuppressed("alex@velomoov.fr"), "the address we wrote to")
        assertEquals(EnrollmentStatus.STOPPED_UNSUBSCRIBE, repo.enrollment(enrollmentId)!!.status)
        assertEquals(listOf(7L), inbox.seen[mailbox.address])
    }
}
