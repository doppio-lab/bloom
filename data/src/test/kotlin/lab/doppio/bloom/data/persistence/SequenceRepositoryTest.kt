package lab.doppio.bloom.data.persistence

import kotlinx.coroutines.test.runTest
import kotlin.test.assertNull
import kotlin.test.assertIs
import java.time.temporal.ChronoUnit
import java.time.Instant
import lab.doppio.bloom.data.persistence.draft.ExposedDraftRepository
import lab.doppio.bloom.core.sequence.SequenceEngine
import lab.doppio.bloom.core.sequence.NextAction
import lab.doppio.bloom.core.model.SendingPolicy
import lab.doppio.bloom.core.model.DraftStatus
import lab.doppio.bloom.core.model.Draft
import lab.doppio.bloom.core.model.Channel
import lab.doppio.bloom.core.model.Account
import lab.doppio.bloom.core.model.Contact
import lab.doppio.bloom.core.model.EnrollmentStatus
import lab.doppio.bloom.core.model.Persona
import lab.doppio.bloom.core.sequence.DefaultSequences
import lab.doppio.bloom.data.TestDatabase
import lab.doppio.bloom.data.persistence.account.ExposedAccountRepository
import lab.doppio.bloom.data.persistence.contact.ExposedContactRepository
import lab.doppio.bloom.data.persistence.sequence.ExposedSequenceRepository
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SequenceRepositoryTest {
    private val accounts = ExposedAccountRepository()
    private val contacts = ExposedContactRepository()
    private val sequences = ExposedSequenceRepository()

    @BeforeTest
    fun clean() = TestDatabase.reset()

    @Test
    fun `les contacts déjà inscrits le restent, même après la fin de leur séquence`() = runTest {
        sequences.ensureSequences(DefaultSequences.all())
        val accountId = accounts.upsertAccount(Account(name = "Velomoov"))
        fun person(name: String) = Contact(accountId = accountId, fullName = name, role = "CTO", persona = Persona.CTO, email = "$name@velomoov.fr", dataSource = "test")
        val running = contacts.addContact(person("alex"))
        val finished = contacts.addContact(person("sam"))
        contacts.addContact(person("lou"))
        val seq = sequences.all().first().id
        sequences.enroll(running, seq)
        sequences.setEnrollmentStatus(sequences.enroll(finished, seq), EnrollmentStatus.COMPLETED)

        assertEquals(setOf(running, finished), sequences.enrolledContactIds())
    }

    @Test
    fun `une absence repousse la suite à la date de retour au lieu de mettre la séquence en pause`() = runTest {
        val drafts = ExposedDraftRepository()
        sequences.ensureSequences(DefaultSequences.all())
        val accountId = accounts.upsertAccount(Account(name = "Velomoov"))
        val contactId = contacts.addContact(Contact(accountId = accountId, fullName = "Alex", role = "CTO", persona = Persona.CTO, email = "alex@velomoov.fr", dataSource = "test"))
        val seq = sequences.all().first()
        val enrollmentId = sequences.enroll(contactId, seq.id)
        sequences.setEnrollmentStatus(enrollmentId, EnrollmentStatus.PAUSED)
        val draftId = drafts.createDraft(Draft(enrollmentId = enrollmentId, stepIndex = 0, channel = Channel.EMAIL, subject = "Objet", body = "Corps",
            citedFactIds = emptyList(), referenceUsed = null, rationale = "test", status = DraftStatus.APPROVED))

        val now = Instant.parse("2026-10-13T08:00:00Z")
        val back = now.plus(14, ChronoUnit.DAYS)
        sequences.postponeEnrollment(enrollmentId, back)
        drafts.holdOpenDrafts(enrollmentId, back)

        val e = sequences.enrollment(enrollmentId)!!
        assertEquals(EnrollmentStatus.ACTIVE, e.status, "the sequence resumes on its own")
        assertEquals(back.toString(), e.lastActionAt)
        assertIs<NextAction.WaitUntil>(SequenceEngine.next(e, seq, SendingPolicy(), now), "nothing is drafted before the return")
        assertNull(drafts.nextToSend(now), "an approved draft waits for the return too")
        assertEquals(draftId, drafts.nextToSend(back)?.id)
    }
}
