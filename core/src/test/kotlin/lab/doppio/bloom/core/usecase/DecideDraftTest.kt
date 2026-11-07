package lab.doppio.bloom.core.usecase

import kotlinx.coroutines.test.runTest
import lab.doppio.bloom.core.model.Account
import lab.doppio.bloom.core.model.DraftStatus
import lab.doppio.bloom.core.port.AccountRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DecideDraftTest {
    private val drafts = FakeDraftRepository()
    private val sequences = FakeSequenceRepository().apply { sequences += emailSequence() }
    private val contacts = FakeContactRepository()
    private val accounts = FakeAccountRepository(listOf(AccountRow(Account(10, "Acme"), 60, 0, 0, 0, null, emptyList(), null)))
    private val events = FakeEventRepository()
    private val decide = DecideDraft(drafts, sequences, contacts, accounts, FakeFactRepository(), events)

    private val contact = contacts.add()
    private val enrollment = sequences.addEnrollment(contact.id, nextStep = 1)

    /** A follow-up that passes the guard: no claim, one question. */
    private fun pending(body: String = "Bonjour Jane, avez-vous eu le temps de regarder ma note ?") =
        drafts.add(enrollment.id, DraftStatus.PENDING_REVIEW, step = 1, body = body)

    @Test
    fun `approving a publishable draft moves it to APPROVED and logs the event`() = runTest {
        val draft = pending()
        assertEquals(DecideDraft.Result.Done, decide.approve(draft.id))
        assertEquals(DraftStatus.APPROVED, drafts.status(draft.id))
        assertEquals(listOf("DRAFT_APPROVED"), events.types)
    }

    @Test
    fun `an edited approval stores the correction and logs it as edited`() = runTest {
        val draft = pending()
        assertEquals(DecideDraft.Result.Done, decide.approve(draft.id, "Autre objet", "Bonjour, une question courte ?"))
        assertEquals("Autre objet", drafts.drafts.getValue(draft.id).subject)
        assertEquals("Bonjour, une question courte ?", drafts.drafts.getValue(draft.id).body)
        assertEquals(listOf("DRAFT_EDITED_APPROVED"), events.types)
    }

    @Test
    fun `the guard applies to the human's correction and the draft stays pending`() = runTest {
        val draft = pending()
        val result = assertIs<DecideDraft.Result.Violations>(decide.approve(draft.id, body = "Une synergie rare, vous en pensez quoi ?"))
        assertTrue(result.messages.any { "synergie" in it })
        assertEquals(DraftStatus.PENDING_REVIEW, drafts.status(draft.id))
        assertTrue(events.events.isEmpty())
    }

    @Test
    fun `a draft already blocked by the guard cannot be approved as is`() = runTest {
        val draft = pending(body = "Notre synergie vous intéresse ?")
        assertIs<DecideDraft.Result.Violations>(decide.approve(draft.id))
        assertEquals(DraftStatus.PENDING_REVIEW, drafts.status(draft.id))
    }

    @Test
    fun `a draft that is no longer pending is never decided again`() = runTest {
        val draft = drafts.add(enrollment.id, DraftStatus.APPROVED, step = 1)
        assertEquals(DecideDraft.Result.NotPending, decide.approve(draft.id))
        assertEquals(DecideDraft.Result.NotPending, decide.reject(draft.id))
        assertEquals(DraftStatus.APPROVED, drafts.status(draft.id))
    }

    @Test
    fun `an unknown draft is not found`() = runTest {
        assertEquals(DecideDraft.Result.NotFound, decide.approve(99))
        assertEquals(DecideDraft.Result.NotFound, decide.reject(99))
    }

    @Test
    fun `rejecting moves the draft to REJECTED and logs the event`() = runTest {
        val draft = pending()
        assertEquals(DecideDraft.Result.Done, decide.reject(draft.id))
        assertEquals(DraftStatus.REJECTED, drafts.status(draft.id))
        assertEquals(listOf("DRAFT_REJECTED"), events.types)
    }
}
