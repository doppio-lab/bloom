package lab.doppio.bloom.data.persistence

import kotlinx.coroutines.test.runTest
import lab.doppio.bloom.core.model.Account
import lab.doppio.bloom.core.model.Channel
import lab.doppio.bloom.core.model.Contact
import lab.doppio.bloom.core.model.Draft
import lab.doppio.bloom.core.model.DraftStatus
import lab.doppio.bloom.core.model.Persona
import lab.doppio.bloom.core.sequence.DefaultSequences
import lab.doppio.bloom.data.TestDatabase
import lab.doppio.bloom.data.persistence.account.ExposedAccountRepository
import lab.doppio.bloom.data.persistence.contact.ExposedContactRepository
import lab.doppio.bloom.data.persistence.draft.ExposedDraftRepository
import lab.doppio.bloom.data.persistence.sequence.ExposedSequenceRepository
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DraftRepositoryTest {
    private val accounts = ExposedAccountRepository()
    private val contacts = ExposedContactRepository()
    private val sequences = ExposedSequenceRepository()
    private val drafts = ExposedDraftRepository()

    @BeforeTest
    fun clean() = TestDatabase.reset()

    private suspend fun approvedDraft(step: Int = 0, name: String = "Velomoov"): Draft {
        sequences.ensureSequences(DefaultSequences.all())
        val accountId = accounts.upsertAccount(Account(name = name))
        val contactId = contacts.addContact(Contact(accountId = accountId, fullName = "Alex Martin", role = "CTO", persona = Persona.CTO, email = "alex@$name.fr", dataSource = "test"))
        val enrollmentId = sequences.enroll(contactId, sequences.all().first().id)
        val draft = Draft(enrollmentId = enrollmentId, stepIndex = step, channel = Channel.EMAIL, subject = "Objet", body = "Corps",
            citedFactIds = emptyList(), referenceUsed = null, rationale = "test", status = DraftStatus.APPROVED)
        return draft.copy(id = drafts.createDraft(draft))
    }

    @Test
    fun `un brouillon réservé ne peut pas être réservé une seconde fois`() = runTest {
        val d = approvedDraft()
        assertTrue(drafts.claimForSending(d.id, "<a@x>", "me@x"))
        assertFalse(drafts.claimForSending(d.id, "<b@x>", "me@x"))
        assertNull(drafts.nextToSend())
    }

    @Test
    fun `un envoi réussi passe le brouillon en SENT et avance la séquence`() = runTest {
        val d = approvedDraft()
        drafts.claimForSending(d.id, "<a@x>", "me@x")
        drafts.recordSent(d, threadMessageId = "<a@x>")

        assertEquals(DraftStatus.SENT, drafts.draft(d.id)!!.status)
        val e = sequences.enrollment(d.enrollmentId)!!
        assertEquals(1, e.nextStep)
        assertEquals("<a@x>", sequences.threadMessageId(e.id))
        assertEquals(e.id, drafts.enrollmentByMessageId("<a@x>"))
    }

    @Test
    fun `après un redémarrage, un envoi interrompu n'est jamais renvoyé`() = runTest {
        val d = approvedDraft()
        drafts.claimForSending(d.id, "<a@x>", "me@x")

        assertEquals(1, drafts.reconcileInterruptedSends())
        assertEquals(DraftStatus.FAILED, drafts.draft(d.id)!!.status)
        assertNull(drafts.nextToSend())
    }

    @Test
    fun `un refus SMTP laisse passer les autres emails puis abandonne après trois essais`() = runTest {
        val now = Instant.parse("2026-10-13T08:00:00Z")
        val bad = approvedDraft(name = "Alpha")
        val good = approvedDraft(name = "Beta")

        drafts.claimForSending(bad.id, "<1@x>", "me@x")
        assertEquals(DraftStatus.APPROVED, drafts.recordSendFailure(bad.id, "550 mailbox unavailable", now))
        assertEquals(good.id, drafts.nextToSend(now)?.id, "the failing draft waits, the next one goes out")
        assertEquals(bad.id, drafts.nextToSend(now.plus(16, ChronoUnit.MINUTES))?.id)

        drafts.claimForSending(bad.id, "<2@x>", "me@x")
        assertEquals(DraftStatus.APPROVED, drafts.recordSendFailure(bad.id, "550", now))
        drafts.claimForSending(bad.id, "<3@x>", "me@x")
        assertEquals(DraftStatus.FAILED, drafts.recordSendFailure(bad.id, "550", now))
    }

    @Test
    fun `une étape ne peut avoir qu'un brouillon ouvert`() = runTest {
        val d = approvedDraft()
        assertFailsWith<Exception> { drafts.createDraft(d.copy(id = 0, status = DraftStatus.PENDING_REVIEW)) }
        assertTrue(drafts.hasOpenDraft(d.enrollmentId, d.stepIndex))
    }

    @Test
    fun `décider avec une correction humaine marque le brouillon comme édité et garde le texte corrigé`() = runTest {
        val d = approvedDraft()
        val pending = drafts.createDraft(d.copy(id = 0, stepIndex = 1, status = DraftStatus.PENDING_REVIEW))

        drafts.decideDraft(pending, DraftStatus.APPROVED, editedSubject = "Nouvel objet", editedBody = "Nouveau corps")

        val decided = drafts.draft(pending)!!
        assertEquals(DraftStatus.APPROVED, decided.status)
        assertEquals("Nouvel objet", decided.subject)
        assertEquals("Nouveau corps", decided.body)
    }

    @Test
    fun `décider sans correction laisse le texte et ne marque pas le brouillon comme édité`() = runTest {
        val d = approvedDraft()
        val pending = drafts.createDraft(d.copy(id = 0, stepIndex = 1, status = DraftStatus.PENDING_REVIEW))

        drafts.decideDraft(pending, DraftStatus.APPROVED)

        assertEquals("Corps", drafts.draft(pending)!!.body)
        drafts.claimForSending(pending, "<p@x>", "me@x")
        drafts.recordSent(drafts.draft(pending)!!, "<p@x>")
        assertTrue(drafts.humanEditedExamples().isEmpty(), "only human edits become style examples")
    }
}
