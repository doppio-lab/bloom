package lab.doppio.bloom.core.usecase

import kotlinx.coroutines.test.runTest
import lab.doppio.bloom.core.model.Account
import lab.doppio.bloom.core.model.Deal
import lab.doppio.bloom.core.model.DealStage
import lab.doppio.bloom.core.model.Dossier
import lab.doppio.bloom.core.model.Offer
import lab.doppio.bloom.core.port.AccountRow
import kotlin.test.Test
import kotlin.test.assertEquals

class BookMeetingTest {
    private val accounts = FakeAccountRepository(listOf(AccountRow(Account(10, "Acme"), 70, 0, 0, 0, Offer.REGIE, emptyList(), null)))
    private val deals = FakeDealRepository()
    private val replies = FakeReplyRepository()
    private val events = FakeEventRepository()
    private val facts = FakeFactRepository()
    private val book = BookMeeting(accounts, deals, replies, events, facts)

    @Test
    fun `noter un rendez-vous crée l'affaire, compte le rendez-vous et classe la réponse`() = runTest {
        assertEquals(BookMeeting.Result.Booked, book(10, replyId = 7))
        val deal = deals.deals.single()
        assertEquals(DealStage.MEETING, deal.stage)
        assertEquals(Offer.REGIE, deal.offer)
        assertEquals(0L, deal.amountEur)
        assertEquals(listOf("MEETING_BOOKED"), events.types)
        assertEquals(listOf(7L), replies.handled)
    }

    @Test
    fun `l'offre du dossier passe avant l'offre recommandée`() = runTest {
        facts.dossier = Dossier(10, "", emptyList(), "", Offer.TRAINING_KMP)
        book(10, replyId = null)
        assertEquals(Offer.TRAINING_KMP, deals.deals.single().offer)
    }

    @Test
    fun `une affaire qualifiée passe au rendez-vous au lieu d'en créer une autre`() = runTest {
        deals.deals += Deal(1, 10, Offer.FORFAIT, DealStage.QUALIFIED, 12000, null, null)
        book(10, replyId = null)
        assertEquals(listOf(DealStage.MEETING), deals.deals.map { it.stage })
        assertEquals(12000L, deals.deals.single().amountEur)
    }

    @Test
    fun `un rendez-vous déjà noté n'est pas compté deux fois, la réponse est quand même classée`() = runTest {
        deals.deals += Deal(1, 10, Offer.FORFAIT, DealStage.PROPOSAL, 40000, null, null)
        assertEquals(BookMeeting.Result.AlreadyBooked, book(10, replyId = 7))
        assertEquals(1, deals.deals.size)
        assertEquals(emptyList(), events.types)
        assertEquals(listOf(7L), replies.handled)
    }

    @Test
    fun `un compte inconnu ne crée rien`() = runTest {
        assertEquals(BookMeeting.Result.NotFound, book(99, replyId = 7))
        assertEquals(emptyList(), deals.deals)
        assertEquals(emptyList(), replies.handled)
    }
}
