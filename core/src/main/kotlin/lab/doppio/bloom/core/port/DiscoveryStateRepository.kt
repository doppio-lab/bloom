package lab.doppio.bloom.core.port

import java.time.Instant
import lab.doppio.bloom.core.discovery.BoardRef
import lab.doppio.bloom.core.model.AccountId
import lab.doppio.bloom.core.model.Demand

/** What discovery remembers between runs: keys already examined, marketplace demands, job boards per account. */
interface DiscoveryStateRepository {
    suspend fun wasSeen(key: String, withinDays: Long = 30): Boolean

    suspend fun markSeen(key: String, kept: Boolean, reason: String? = null)

    /** Saves a demand the first time it is seen. True when it is new. */
    suspend fun saveDemand(d: Demand): Boolean

    suspend fun openDemands(limit: Int = 30): List<Demand>

    /** True when the demand exists. */
    suspend fun dismissDemand(key: String): Boolean

    /** Demands are only useful while the buyer is choosing: drop them after 90 days. [now] defaults to the clock. */
    suspend fun purgeOldDemands(now: Instant? = null): Int

    /** Job board remembered for an account. Null: never looked. A null ref inside: looked, none found. */
    suspend fun jobBoard(accountId: AccountId): Pair<BoardRef?, Instant>?

    suspend fun saveJobBoard(accountId: AccountId, ref: BoardRef?)
}
