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
import lab.doppio.bloom.core.model.SendingPolicy
import lab.doppio.bloom.core.sequence.DefaultSequences
import lab.doppio.bloom.core.sequence.NextAction
import lab.doppio.bloom.core.sequence.SequenceEngine
import lab.doppio.bloom.data.db.Repository
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class OutOfOfficeTest {
    private val repo = Repository()

    @BeforeTest
    fun clean() = TestDatabase.reset()

    @Test
    fun `an out-of-office reply pushes the next step to the return date instead of pausing for good`() = runTest {
        repo.ensureSequences(DefaultSequences.all())
        val accountId = repo.upsertAccount(Account(name = "Velomoov"))
        val contactId = repo.addContact(Contact(accountId = accountId, fullName = "Alex Martin", role = "CTO", persona = Persona.CTO, email = "alex@velomoov.fr", dataSource = "test"))
        val seq = repo.sequences().first()
        val enrollmentId = repo.enroll(contactId, seq.id)
        repo.setEnrollmentStatus(enrollmentId, EnrollmentStatus.PAUSED)
        val draftId = repo.createDraft(Draft(enrollmentId = enrollmentId, stepIndex = 0, channel = Channel.EMAIL, subject = "Objet", body = "Corps",
            citedFactIds = emptyList(), referenceUsed = null, rationale = "test", status = DraftStatus.APPROVED))

        val now = Instant.parse("2026-10-13T08:00:00Z")
        val back = now.plus(14, ChronoUnit.DAYS)
        repo.postponeEnrollment(enrollmentId, back)

        val e = repo.enrollment(enrollmentId)!!
        assertEquals(EnrollmentStatus.ACTIVE, e.status, "the sequence resumes on its own")
        assertEquals(back.toString(), e.lastActionAt)
        assertIs<NextAction.WaitUntil>(SequenceEngine.next(e, seq, SendingPolicy(), now), "nothing is drafted before the return")
        assertNull(repo.nextToSend(now), "an approved draft waits for the return too")
        assertEquals(draftId, repo.nextToSend(back)?.id)
    }
}
