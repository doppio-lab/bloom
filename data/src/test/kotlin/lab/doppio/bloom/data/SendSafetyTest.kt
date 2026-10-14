package lab.doppio.bloom.data

import kotlinx.coroutines.test.runTest
import lab.doppio.bloom.core.model.Account
import lab.doppio.bloom.core.model.Channel
import lab.doppio.bloom.core.model.Contact
import lab.doppio.bloom.core.model.Draft
import lab.doppio.bloom.core.model.DraftStatus
import lab.doppio.bloom.core.model.Persona
import lab.doppio.bloom.core.sequence.DefaultSequences
import lab.doppio.bloom.data.db.Repository
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SendSafetyTest {
    private val repo = Repository()

    @BeforeTest
    fun clean() = TestDatabase.reset()

    private suspend fun approvedDraft(step: Int = 0, name: String = "Velomoov"): Draft {
        repo.ensureSequences(DefaultSequences.all())
        val accountId = repo.upsertAccount(Account(name = name))
        val contactId = repo.addContact(Contact(accountId = accountId, fullName = "Alex Martin", role = "CTO", persona = Persona.CTO, email = "alex@$name.fr", dataSource = "test"))
        val enrollmentId = repo.enroll(contactId, repo.sequences().first().id)
        val draft = Draft(enrollmentId = enrollmentId, stepIndex = step, channel = Channel.EMAIL, subject = "Objet", body = "Corps",
            citedFactIds = emptyList(), referenceUsed = null, rationale = "test", status = DraftStatus.APPROVED)
        return draft.copy(id = repo.createDraft(draft))
    }

    @Test
    fun `un brouillon réservé ne peut pas être réservé une seconde fois`() = runTest {
        val d = approvedDraft()
        assertTrue(repo.claimForSending(d.id, "<a@x>", "me@x"))
        assertFalse(repo.claimForSending(d.id, "<b@x>", "me@x"))
        assertNull(repo.nextToSend())
    }

    @Test
    fun `un envoi réussi passe le brouillon en SENT et avance la séquence`() = runTest {
        val d = approvedDraft()
        repo.claimForSending(d.id, "<a@x>", "me@x")
        repo.recordSent(d, threadMessageId = "<a@x>")

        assertEquals(DraftStatus.SENT, repo.draft(d.id)!!.status)
        val e = repo.enrollment(d.enrollmentId)!!
        assertEquals(1, e.nextStep)
        assertEquals("<a@x>", repo.threadMessageId(e.id))
        assertEquals(e.id, repo.enrollmentByMessageId("<a@x>"))
    }

    @Test
    fun `après un redémarrage, un envoi interrompu n'est jamais renvoyé`() = runTest {
        val d = approvedDraft()
        repo.claimForSending(d.id, "<a@x>", "me@x")

        assertEquals(1, repo.reconcileInterruptedSends())
        assertEquals(DraftStatus.FAILED, repo.draft(d.id)!!.status)
        assertNull(repo.nextToSend())
    }

    @Test
    fun `un refus SMTP laisse passer les autres emails puis abandonne après trois essais`() = runTest {
        val now = Instant.parse("2026-10-13T08:00:00Z")
        val bad = approvedDraft(name = "Alpha")
        val good = approvedDraft(name = "Beta")

        repo.claimForSending(bad.id, "<1@x>", "me@x")
        assertEquals(DraftStatus.APPROVED, repo.recordSendFailure(bad.id, "550 mailbox unavailable", now))
        assertEquals(good.id, repo.nextToSend(now)?.id, "the failing draft waits, the next one goes out")
        assertEquals(bad.id, repo.nextToSend(now.plus(16, ChronoUnit.MINUTES))?.id)

        repo.claimForSending(bad.id, "<2@x>", "me@x")
        assertEquals(DraftStatus.APPROVED, repo.recordSendFailure(bad.id, "550", now))
        repo.claimForSending(bad.id, "<3@x>", "me@x")
        assertEquals(DraftStatus.FAILED, repo.recordSendFailure(bad.id, "550", now))
    }

    @Test
    fun `une étape ne peut avoir qu'un brouillon ouvert`() = runTest {
        val d = approvedDraft()
        assertFailsWith<Exception> { repo.createDraft(d.copy(id = 0, status = DraftStatus.PENDING_REVIEW)) }
        assertTrue(repo.hasOpenDraft(d.enrollmentId, d.stepIndex))
    }
}
