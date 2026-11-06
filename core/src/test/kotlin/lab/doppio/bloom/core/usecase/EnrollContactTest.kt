package lab.doppio.bloom.core.usecase

import kotlinx.coroutines.test.runTest
import lab.doppio.bloom.core.model.Dossier
import lab.doppio.bloom.core.model.EnrollmentStatus
import lab.doppio.bloom.core.model.Offer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EnrollContactTest {
    private val sequences = FakeSequenceRepository().apply { sequences += listOf(emailSequence(Offer.DISCOVERY, 1), emailSequence(Offer.REGIE, 2)) }
    private val replies = FakeReplyRepository()
    private val facts = FakeFactRepository()
    private val accounts = FakeAccountRepository()
    private val events = FakeEventRepository()
    private val contacts = FakeContactRepository()
    private var planned = 0
    private val enroll = EnrollContact(sequences, replies, facts, accounts, events) { planned++ }

    private fun dossier(offer: Offer, noFit: Boolean = false) = Dossier(10, "s", emptyList(), "a", offer, noFit)

    @Test
    fun `the dossier offer picks the sequence, then planning is triggered`() = runTest {
        facts.dossier = dossier(Offer.REGIE)
        val contact = contacts.add()
        val result = assertIs<EnrollContact.Result.Enrolled>(enroll(contact))
        assertEquals(2, sequences.enrollment(result.enrollmentId)!!.sequenceId)
        assertEquals(listOf("ENROLLED"), events.types)
        assertEquals(1, planned)
    }

    @Test
    fun `an address that opted out is never enrolled`() = runTest {
        val contact = contacts.add()
        replies.suppressed[contact.email!!] = "opposition"
        assertEquals(EnrollContact.Result.OptedOut, enroll(contact))
        assertTrue(sequences.enrollments.isEmpty())
        assertEquals(0, planned)
    }

    @Test
    fun `a contact who already has an enrollment is not enrolled again, finished or not`() = runTest {
        val contact = contacts.add()
        sequences.addEnrollment(contact.id, EnrollmentStatus.COMPLETED)
        assertEquals(EnrollContact.Result.AlreadyEnrolled, enroll(contact))
        assertEquals(1, sequences.enrollments.size)
    }

    @Test
    fun `no fit blocks an automatic enrollment but not an explicit offer`() = runTest {
        facts.dossier = dossier(Offer.DISCOVERY, noFit = true)
        val contact = contacts.add()
        assertEquals(EnrollContact.Result.NoFit, enroll(contact))
        assertIs<EnrollContact.Result.Enrolled>(enroll(contact, Offer.REGIE))
    }

    @Test
    fun `without a dossier the recommended offer of the account is used, then discovery`() = runTest {
        val row = lab.doppio.bloom.core.port.AccountRow(
            lab.doppio.bloom.core.model.Account(10, "Acme"), 60, 0, 0, 0, Offer.REGIE, emptyList(), null,
        )
        accounts.rows = listOf(row)
        val result = assertIs<EnrollContact.Result.Enrolled>(enroll(contacts.add()))
        assertEquals(2, sequences.enrollment(result.enrollmentId)!!.sequenceId)

        accounts.rows = emptyList()
        val fallback = assertIs<EnrollContact.Result.Enrolled>(enroll(contacts.add(email = "other@client.test")))
        assertEquals(1, sequences.enrollment(fallback.enrollmentId)!!.sequenceId)
    }

    @Test
    fun `an offer without a sequence falls back to discovery, and no discovery sequence means no enrollment`() = runTest {
        val contact = contacts.add()
        val result = assertIs<EnrollContact.Result.Enrolled>(enroll(contact, Offer.TRAINING_KMP))
        assertEquals(1, sequences.enrollment(result.enrollmentId)!!.sequenceId)

        sequences.sequences.clear()
        assertEquals(EnrollContact.Result.NoSequence, enroll(contacts.add(email = "x@client.test")))
    }
}
