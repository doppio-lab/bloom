package lab.doppio.bloom.core.model

import kotlinx.serialization.Serializable

/** Declared in the order of progress: GetBoard compares ordinals to find the most advanced deal. Keep LOST last. */
@Serializable
enum class DealStage { QUALIFIED, MEETING, DISCOVERY_SOLD, PROPOSAL, WON, LOST }

@Serializable
data class Deal(
    val id: Long = 0,
    val accountId: AccountId,
    val offer: Offer,
    val stage: DealStage,
    val amountEur: Long,
    val sourceSignal: SignalType?,
    val expectedClose: String?,
)
