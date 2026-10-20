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
import java.time.temporal.ChronoUnit
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SendAfterTest {
    private val repo = Repository()

    @BeforeTest
    fun clean() = TestDatabase.reset()

    private suspend fun approvedDraft(): Long {
        repo.ensureSequences(DefaultSequences.all())
        val accountId = repo.upsertAccount(Account(name = "Velomoov"))
        val contactId = repo.addContact(Contact(accountId = accountId, fullName = "Alex Martin", role = "CTO", persona = Persona.CTO, email = "alex@velomoov.fr", dataSource = "test"))
        val enrollmentId = repo.enroll(contactId, repo.sequences().first().id)
        return repo.createDraft(Draft(enrollmentId = enrollmentId, stepIndex = 1, channel = Channel.EMAIL, subject = "Relance", body = "Corps",
            citedFactIds = emptyList(), referenceUsed = null, rationale = "test", status = DraftStatus.APPROVED))
    }

    @Test
    fun `a follow-up approved the day before does not leave before its day`() = runTest {
        val now = Instant.parse("2026-10-13T08:00:00Z")
        val id = approvedDraft()
        repo.setSendAfter(id, now.plus(1, ChronoUnit.DAYS))
        assertNull(repo.nextToSend(now))
        assertEquals(id, repo.nextToSend(now.plus(1, ChronoUnit.DAYS))?.id)
    }

    @Test
    fun `a draft whose day has come, or with no day set, leaves`() = runTest {
        val now = Instant.parse("2026-10-13T08:00:00Z")
        val id = approvedDraft()
        assertEquals(id, repo.nextToSend(now)?.id, "no send_after: the queue order alone decides")
        repo.setSendAfter(id, now.minus(1, ChronoUnit.DAYS))
        assertEquals(id, repo.nextToSend(now)?.id)
    }
}
