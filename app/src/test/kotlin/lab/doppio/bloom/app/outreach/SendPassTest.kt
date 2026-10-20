package lab.doppio.bloom.app.outreach

import kotlinx.coroutines.test.runTest
import lab.doppio.bloom.app.TestDatabase
import lab.doppio.bloom.core.model.Account
import lab.doppio.bloom.core.model.Channel
import lab.doppio.bloom.core.model.Contact
import lab.doppio.bloom.core.model.Draft
import lab.doppio.bloom.core.model.DraftStatus
import lab.doppio.bloom.core.model.Persona
import lab.doppio.bloom.core.sequence.DefaultSequences
import lab.doppio.bloom.data.db.Repository
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SendPassTest {
    private val repo = Repository()
    private val sender = FakeSender()
    private val boxes = listOf(Mailbox("a@doppio-studio.fr", "pw", "A"), Mailbox("b@doppio-studio.fr", "pw", "B"))
    private val orchestrator = TestOrchestrator.build(repo, sender, TestOrchestrator.mailConfig(*boxes.toTypedArray()))

    /** A Tuesday, 10:00 in Paris: inside the sending window. */
    private val now = Instant.parse("2026-10-13T08:00:00Z")

    @BeforeTest
    fun clean() = TestDatabase.reset()

    private suspend fun approvedDraft(company: String): Long {
        repo.ensureSequences(DefaultSequences.all())
        val accountId = repo.upsertAccount(Account(name = company))
        val contactId = repo.addContact(Contact(accountId = accountId, fullName = "Alex $company", role = "CTO", persona = Persona.CTO, email = "alex@$company.fr", dataSource = "test"))
        val enrollmentId = repo.enroll(contactId, repo.sequences().first().id)
        return repo.createDraft(Draft(enrollmentId = enrollmentId, stepIndex = 0, channel = Channel.EMAIL, subject = "Objet $company", body = "Corps",
            citedFactIds = emptyList(), referenceUsed = null, rationale = "test", status = DraftStatus.APPROVED))
    }

    @Test
    fun `one pass sends one email per available mailbox, oldest drafts first`() = runTest {
        val first = approvedDraft("alpha")
        val second = approvedDraft("beta")
        approvedDraft("gamma")

        orchestrator.sendApproved(now)

        assertEquals(listOf("alex@alpha.fr", "alex@beta.fr"), sender.sent.map { it.to })
        assertEquals(boxes.map { it.address }.toSet(), sender.sent.map { it.mailbox }.toSet(), "each mailbox sends once")
        assertEquals(DraftStatus.SENT, repo.draft(first)!!.status)
        assertEquals(DraftStatus.SENT, repo.draft(second)!!.status)
        assertEquals(1, repo.draftsByStatus(DraftStatus.APPROVED).size, "the third waits for the next pass")
    }

    @Test
    fun `a mailbox that sent less than three minutes ago is skipped`() = runTest {
        approvedDraft("alpha")
        approvedDraft("beta")
        orchestrator.sendApproved(now)
        approvedDraft("gamma")

        orchestrator.sendApproved(now.plusSeconds(60))
        assertEquals(2, sender.sent.size, "both mailboxes are still in their spacing")
        orchestrator.sendApproved(now.plusSeconds(200))
        assertEquals(3, sender.sent.size)
    }
}
