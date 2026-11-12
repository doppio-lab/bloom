package lab.doppio.bloom.core.usecase

import kotlinx.coroutines.test.runTest
import lab.doppio.bloom.core.model.Dossier
import lab.doppio.bloom.core.model.Offer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CompleteContactEmailTest {
    private val contacts = FakeContactRepository()
    private val replies = FakeReplyRepository()
    private val sequences = FakeSequenceRepository().apply { sequences += emailSequence() }
    private val facts = FakeFactRepository()
    private val enroll = EnrollContact(sequences, replies, facts, FakeAccountRepository(), FakeEventRepository())
    private val complete = CompleteContactEmail(contacts, replies, enroll)

    @Test
    fun `l'email ajouté est enregistré et la séquence démarre`() = runTest {
        val jane = contacts.add(email = null)
        val result = complete(jane.id, "  jane@acme.test ")
        assertTrue(result is CompleteContactEmail.Result.Done && result.enrollment is EnrollContact.Result.Enrolled)
        assertEquals("jane@acme.test", contacts.contacts.getValue(jane.id).email)
        assertEquals(1, sequences.enrollments.size)
    }

    @Test
    fun `une adresse invalide est refusée et rien ne change`() = runTest {
        val jane = contacts.add(email = null)
        assertFailsWith<IllegalArgumentException> { complete(jane.id, "jane at acme") }
        assertNull(contacts.contacts.getValue(jane.id).email)
    }

    @Test
    fun `une personne qui s'est opposée n'est jamais enregistrée`() = runTest {
        val jane = contacts.add(email = null)
        replies.suppressed["jane@acme.test"] = "opposition"
        assertFailsWith<IllegalArgumentException> { complete(jane.id, "jane@acme.test") }
        assertTrue(sequences.enrollments.isEmpty())
    }

    @Test
    fun `quand le dossier ne trouve aucune offre, l'email est gardé mais la séquence attend votre choix`() = runTest {
        val jane = contacts.add(email = null)
        facts.dossier = Dossier(10, "", emptyList(), "", Offer.DISCOVERY, noFit = true)
        val result = complete(jane.id, "jane@acme.test")
        assertEquals(CompleteContactEmail.Result.Done(EnrollContact.Result.NoFit), result)
        assertEquals("jane@acme.test", contacts.contacts.getValue(jane.id).email)
    }

    @Test
    fun `un contact inconnu répond introuvable`() = runTest {
        assertEquals(CompleteContactEmail.Result.NotFound, complete(99, "jane@acme.test"))
    }
}
