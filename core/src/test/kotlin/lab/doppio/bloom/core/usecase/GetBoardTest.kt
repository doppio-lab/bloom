package lab.doppio.bloom.core.usecase

import kotlinx.coroutines.test.runTest
import lab.doppio.bloom.core.model.Account
import lab.doppio.bloom.core.model.Channel
import lab.doppio.bloom.core.model.Deal
import lab.doppio.bloom.core.model.DealStage
import lab.doppio.bloom.core.model.Dossier
import lab.doppio.bloom.core.model.DraftStatus
import lab.doppio.bloom.core.model.EnrollmentStatus
import lab.doppio.bloom.core.model.Offer
import lab.doppio.bloom.core.model.ReplyIntent
import lab.doppio.bloom.core.port.AccountRow
import lab.doppio.bloom.core.port.ReplyRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GetBoardTest {
    private val accounts = FakeAccountRepository()
    private val contacts = FakeContactRepository()
    private val sequences = FakeSequenceRepository().apply { sequences += emailSequence() }
    private val drafts = FakeDraftRepository()
    private val replies = FakeReplyRepository()
    private val deals = FakeDealRepository()
    private val facts = FakeFactRepository()
    private val board = GetBoard(accounts, contacts, sequences, drafts, replies, deals, facts)

    private fun row(id: Long, name: String, score: Int = 70, offer: Offer? = Offer.DISCOVERY) =
        AccountRow(Account(id, name, source = "BODACC"), score, 0, 0, 0, offer, emptyList(), null)
    private fun reply(id: Long, from: String, enrollmentId: Long? = null, intent: ReplyIntent = ReplyIntent.MEETING_REQUEST) =
        ReplyRow(id, enrollmentId, from, intent, "résumé", null, "2026-11-04T09:00:00Z", false)

    @Test
    fun `un compte sans contact attend qu'on trouve la personne`() = runTest {
        accounts.rows = listOf(row(10, "Acme"))
        accounts.toComplete = accounts.rows
        val card = board().cards.single()
        assertEquals(BoardStage.TO_START, card.stage)
        assertEquals(BoardAction.FIND_CONTACT, card.action)
        assertTrue(card.yours)
        assertEquals("Acme", card.title)
        assertEquals(Offer.DISCOVERY, card.offer)
    }

    @Test
    fun `un compte dont le dossier ne trouve aucune offre n'est pas à démarrer`() = runTest {
        accounts.rows = listOf(row(10, "Acme"))
        accounts.toComplete = accounts.rows
        facts.dossier = Dossier(10, "", emptyList(), "", Offer.DISCOVERY, noFit = true)
        assertTrue(board().cards.isEmpty())
    }

    @Test
    fun `un contact sans email attend son email, avec un email il attend le lancement de la séquence`() = runTest {
        accounts.rows = listOf(row(10, "Acme"), row(11, "Globex"))
        contacts.add(email = null, accountId = 10, name = "Jane")
        contacts.add(email = "max@globex.test", accountId = 11, name = "Max")
        val cards = board().cards.associateBy { it.title }
        assertEquals(BoardAction.ADD_EMAIL, cards.getValue("Acme").action)
        assertEquals("Jane", cards.getValue("Acme").contactName)
        assertEquals(BoardAction.START_SEQUENCE, cards.getValue("Globex").action)
        assertTrue(cards.values.all { it.stage == BoardStage.TO_START })
    }

    @Test
    fun `un brouillon en attente place la carte dans Email à valider, à corriger quand le garde-fou bloque`() = runTest {
        accounts.rows = listOf(row(10, "Acme"), row(11, "Globex"))
        val jane = contacts.add(accountId = 10, name = "Jane")
        val max = contacts.add(email = "max@globex.test", accountId = 11, name = "Max")
        val ok = drafts.add(sequences.addEnrollment(jane.id).id, DraftStatus.PENDING_REVIEW)
        val blocked = drafts.add(sequences.addEnrollment(max.id).id, DraftStatus.PENDING_REVIEW)
        drafts.drafts[blocked.id] = blocked.copy(guardReport = listOf("[BLOCK] vocabulaire : expression interdite"))
        val cards = board().cards.associateBy { it.title }
        assertEquals(BoardStage.TO_REVIEW, cards.getValue("Acme").stage)
        assertEquals(BoardAction.REVIEW_EMAIL, cards.getValue("Acme").action)
        assertEquals(ok.id, cards.getValue("Acme").draftId)
        assertEquals(BoardAction.FIX_EMAIL, cards.getValue("Globex").action)
        assertEquals(1, cards.getValue("Acme").step)
        assertEquals(2, cards.getValue("Acme").stepCount)
    }

    @Test
    fun `une séquence en cours dit qui doit agir`() = runTest {
        accounts.rows = listOf(row(10, "Note"), row(11, "Envoi"), row(12, "Rédaction"), row(13, "Attente"))
        val note = sequences.addEnrollment(contacts.add(accountId = 10).id)
        drafts.add(note.id, DraftStatus.APPROVED, channel = Channel.LINKEDIN)
        drafts.add(sequences.addEnrollment(contacts.add(accountId = 11).id).id, DraftStatus.APPROVED)
        sequences.addEnrollment(contacts.add(accountId = 12).id, nextStep = 0)
        sequences.addEnrollment(contacts.add(accountId = 13).id, nextStep = 1)
        val cards = board().cards.associateBy { it.title }
        assertTrue(cards.values.all { it.stage == BoardStage.IN_SEQUENCE })
        assertEquals(BoardAction.SEND_LINKEDIN_NOTE, cards.getValue("Note").action)
        assertTrue(cards.getValue("Note").yours)
        assertEquals(BoardAction.WAIT_SEND, cards.getValue("Envoi").action)
        assertEquals(BoardAction.WAIT_DRAFT, cards.getValue("Rédaction").action)
        assertEquals(BoardAction.WAIT_REPLY, cards.getValue("Attente").action)
        assertTrue(listOf("Envoi", "Rédaction", "Attente").none { cards.getValue(it).yours })
    }

    @Test
    fun `une séquence terminée ne donne aucune carte`() = runTest {
        accounts.rows = listOf(row(10, "Acme"))
        sequences.addEnrollment(contacts.add(accountId = 10).id, EnrollmentStatus.COMPLETED)
        assertTrue(board().cards.isEmpty())
    }

    @Test
    fun `une réponse ouverte place la carte dans A répondu, sans carte de séquence en double`() = runTest {
        accounts.rows = listOf(row(10, "Acme"))
        val jane = contacts.add(accountId = 10, name = "Jane")
        val e = sequences.addEnrollment(jane.id)
        replies.open += reply(7, "jane@client.test", e.id)
        val card = board().cards.single()
        assertEquals(BoardStage.REPLIED, card.stage)
        assertEquals(BoardAction.ANSWER_REPLY, card.action)
        assertEquals(7L, card.replyId)
        assertEquals(ReplyIntent.MEETING_REQUEST, card.replyIntent)
        assertEquals(10L, card.accountId)
    }

    @Test
    fun `une réponse sans contact connu porte l'adresse de l'expéditeur`() = runTest {
        replies.open += reply(8, "inconnu@ailleurs.test")
        val card = board().cards.single()
        assertEquals("inconnu@ailleurs.test", card.title)
        assertNull(card.accountId)
    }

    @Test
    fun `un compte avec un rendez-vous n'a qu'une carte, dans Rendez-vous`() = runTest {
        accounts.rows = listOf(row(10, "Acme"))
        val jane = contacts.add(accountId = 10)
        val e = sequences.addEnrollment(jane.id)
        drafts.add(e.id, DraftStatus.PENDING_REVIEW)
        replies.open += reply(9, "jane@client.test", e.id)
        deals.deals += Deal(1, 10, Offer.DISCOVERY, DealStage.MEETING, 0, null, null)
        val card = board().cards.single()
        assertEquals(BoardStage.MEETING, card.stage)
        assertEquals(BoardAction.NONE, card.action)
        assertTrue(!card.yours)
    }

    @Test
    fun `un compte en liste noire n'apparaît pas, même avec une réponse`() = runTest {
        // A blacklisted account is missing from accountsByScore
        val jane = contacts.add(accountId = 10)
        replies.open += reply(9, "jane@client.test", sequences.addEnrollment(jane.id).id)
        assertTrue(board().cards.isEmpty())
    }

    @Test
    fun `les colonnes suivent le parcours, et dans une colonne ce qui vous attend passe devant, puis le score`() = runTest {
        accounts.rows = listOf(row(10, "Bas", score = 50), row(11, "Haut", score = 90), row(12, "Attente", score = 99))
        accounts.toComplete = listOf(accounts.rows[0])
        sequences.addEnrollment(contacts.add(accountId = 12).id, nextStep = 1)
        drafts.add(sequences.addEnrollment(contacts.add(accountId = 11).id).id, DraftStatus.APPROVED, channel = Channel.LINKEDIN)
        assertEquals(listOf("Bas", "Haut", "Attente"), board().cards.map { it.title })
    }

    @Test
    fun `un compte au-delà des 500 premiers scores garde sa carte`() = runTest {
        accounts.rows = (1L..600L).map { row(it, "Compte $it", score = 1000 - it.toInt()) }
        val last = contacts.add(accountId = 600, name = "Jane")
        replies.open += reply(7, "jane@client.test", sequences.addEnrollment(last.id).id)
        val card = board().cards.single { it.stage == BoardStage.REPLIED }
        assertEquals("Compte 600", card.title)
    }

    @Test
    fun `la carte Rendez-vous montre l'offre de l'affaire la plus avancée, pas celle du dossier`() = runTest {
        accounts.rows = listOf(row(10, "Acme", offer = Offer.DISCOVERY))
        facts.dossier = Dossier(10, "", emptyList(), "", Offer.DISCOVERY)
        deals.deals += Deal(1, 10, Offer.REGIE, DealStage.MEETING, 0, null, null)
        deals.deals += Deal(2, 10, Offer.FORFAIT, DealStage.PROPOSAL, 40000, null, null)
        val card = board().cards.single()
        assertEquals(BoardStage.MEETING, card.stage)
        assertEquals(Offer.FORFAIT, card.offer)
    }

    @Test
    fun `un envoi en échec bloque la séquence et vous demande de vérifier vos envois`() = runTest {
        accounts.rows = listOf(row(10, "Acme"), row(11, "Globex"))
        val failed = drafts.add(sequences.addEnrollment(contacts.add(accountId = 10).id, nextStep = 1).id, DraftStatus.FAILED)
        val other = sequences.addEnrollment(contacts.add(email = "max@globex.test", accountId = 11).id, nextStep = 1)
        drafts.add(other.id, DraftStatus.FAILED)
        drafts.add(other.id, DraftStatus.APPROVED, step = 1)
        val cards = board().cards.associateBy { it.title }
        val acme = cards.getValue("Acme")
        assertEquals(BoardStage.IN_SEQUENCE, acme.stage)
        assertEquals(BoardAction.CHECK_FAILED_SEND, acme.action)
        assertTrue(acme.yours)
        assertEquals(failed.id, acme.draftId)
        // An email ready to leave keeps its place before the failed one
        assertEquals(BoardAction.WAIT_SEND, cards.getValue("Globex").action)
    }

    @Test
    fun `une adresse opposée n'est jamais proposée au lancement de la séquence`() = runTest {
        accounts.rows = listOf(row(10, "Acme"), row(11, "Globex"), row(12, "Initech"))
        replies.suppressed["jane@acme.test"] = "opposition"
        replies.suppressed["max@globex.test"] = "opposition"
        replies.suppressed["ann@initech.test"] = "opposition"
        contacts.add(email = "jane@acme.test", accountId = 10, name = "Jane")
        contacts.add(email = null, accountId = 10, name = "Paul")
        contacts.add(email = "max@globex.test", accountId = 11, name = "Max")
        contacts.add(email = "ann@initech.test", accountId = 12, name = "Ann")
        contacts.add(email = "bob@initech.test", accountId = 12, name = "Bob")
        val cards = board().cards.associateBy { it.title }
        assertEquals(BoardAction.ADD_EMAIL, cards.getValue("Acme").action)
        assertEquals("Paul", cards.getValue("Acme").contactName)
        assertTrue("Globex" !in cards)
        assertEquals(BoardAction.START_SEQUENCE, cards.getValue("Initech").action)
        assertEquals("Bob", cards.getValue("Initech").contactName)
    }
}
