package lab.doppio.bloom.core.usecase

import lab.doppio.bloom.core.model.AccountId
import lab.doppio.bloom.core.model.Deal
import lab.doppio.bloom.core.model.DealStage
import lab.doppio.bloom.core.model.EventType
import lab.doppio.bloom.core.model.Offer
import lab.doppio.bloom.core.port.AccountRepository
import lab.doppio.bloom.core.port.DealRepository
import lab.doppio.bloom.core.port.EventRepository
import lab.doppio.bloom.core.port.FactRepository
import lab.doppio.bloom.core.port.ReplyRepository

/**
 * Records a meeting from the board: the account gets a deal at the MEETING stage, the meeting counts towards the
 * objective once, and the reply that led to it leaves the queue. Later stages (proposal, amount) stay outside Bloom.
 */
class BookMeeting(
    private val accountRepo: AccountRepository,
    private val dealRepo: DealRepository,
    private val replyRepo: ReplyRepository,
    private val eventRepo: EventRepository,
    private val factRepo: FactRepository,
) {
    sealed interface Result {
        data object Booked : Result
        data object AlreadyBooked : Result
        data object NotFound : Result
    }

    suspend operator fun invoke(accountId: AccountId, replyId: Long?): Result {
        // Blacklisted accounts are missing from accountsByScore, so they answer NotFound
        val row = accountRepo.accountsByScore(AccountRepository.ALL).firstOrNull { it.account.id == accountId } ?: return Result.NotFound
        val deals = dealRepo.deals().filter { it.accountId == accountId }
        val result = if (deals.any { it.stage in BOOKED }) Result.AlreadyBooked else {
            val deal = deals.firstOrNull { it.stage == DealStage.QUALIFIED }?.copy(stage = DealStage.MEETING) ?: Deal(
                accountId = accountId,
                offer = factRepo.dossier(accountId)?.offer
                    ?: row.recommendedOffer
                    ?: Offer.DISCOVERY,
                stage = DealStage.MEETING, amountEur = 0, sourceSignal = factRepo.dominantSignal(accountId), expectedClose = null,
            )
            dealRepo.upsertDeal(deal)
            eventRepo.logEvent(EventType.MEETING_BOOKED, accountId)
            Result.Booked
        }
        replyId?.let { replyRepo.markReplyHandled(it) }
        return result
    }

    private companion object {
        val BOOKED = setOf(DealStage.MEETING, DealStage.DISCOVERY_SOLD, DealStage.PROPOSAL, DealStage.WON)
    }
}
