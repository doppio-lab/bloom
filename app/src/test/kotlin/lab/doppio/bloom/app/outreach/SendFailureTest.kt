package lab.doppio.bloom.app.outreach

import jakarta.mail.SendFailedException
import jakarta.mail.internet.InternetAddress
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
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException
import org.eclipse.angus.mail.smtp.SMTPSendFailedException
import java.net.SocketTimeoutException
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SendFailureTest {
    private val repo = Repository()

    @BeforeTest
    fun clean() = TestDatabase.reset()

    private suspend fun claimedDraft(): Draft {
        repo.ensureSequences(DefaultSequences.all())
        val accountId = repo.upsertAccount(Account(name = "Velomoov"))
        val contactId = repo.addContact(Contact(accountId = accountId, fullName = "Alex Martin", role = "CTO", persona = Persona.CTO, email = "alex@velomoov.fr", dataSource = "test"))
        val enrollmentId = repo.enroll(contactId, repo.sequences().first().id)
        val draft = Draft(enrollmentId = enrollmentId, stepIndex = 0, channel = Channel.EMAIL, subject = "Objet", body = "Corps",
            citedFactIds = emptyList(), referenceUsed = null, rationale = "test", status = DraftStatus.APPROVED)
        val saved = draft.copy(id = repo.createDraft(draft))
        assertTrue(repo.claimForSending(saved.id, "<1@x>", "me@x"))
        return saved
    }

    @Test
    fun `an SMTP rejection before acceptance is retried later`() {
        assertTrue(isRetryableSmtpFailure(SMTPSendFailedException("MAIL FROM", 550, "mailbox unavailable", null, null, null, null)))
        assertTrue(isRetryableSmtpFailure(SMTPAddressFailedException(InternetAddress("a@b.fr"), "RCPT TO", 550, "no such user")))
        assertTrue(isRetryableSmtpFailure(SendFailedException("refused", null, null, null, arrayOf(InternetAddress("a@b.fr")))))
    }

    @Test
    fun `anything else, including a timeout after DATA, is not retried`() {
        assertFalse(isRetryableSmtpFailure(SocketTimeoutException("read timed out")))
        assertFalse(isRetryableSmtpFailure(jakarta.mail.MessagingException("lost connection")))
        assertFalse(isRetryableSmtpFailure(SendFailedException("partial", null, arrayOf(InternetAddress("a@b.fr")), null, null)))
    }

    @Test
    fun `a non-retryable failure is FAILED on the first attempt`() = runTest {
        val now = Instant.parse("2026-10-13T08:00:00Z")
        val d = claimedDraft()
        assertEquals(DraftStatus.FAILED, repo.recordSendFailure(d.id, "read timed out", now, retryable = false))
        val stored = repo.draft(d.id)!!
        assertEquals(DraftStatus.FAILED, stored.status)
        assertNull(repo.nextToSend(now.plusSeconds(3600)))
    }

    @Test
    fun `a retryable failure goes back to APPROVED with a retry delay`() = runTest {
        val now = Instant.parse("2026-10-13T08:00:00Z")
        val d = claimedDraft()
        assertEquals(DraftStatus.APPROVED, repo.recordSendFailure(d.id, "550", now, retryable = true))
        assertNull(repo.nextToSend(now), "waits for the delay")
        assertNotNull(repo.nextToSend(now.plusSeconds(16 * 60)))
    }
}
