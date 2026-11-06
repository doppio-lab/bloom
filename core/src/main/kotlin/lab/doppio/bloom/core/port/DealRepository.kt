package lab.doppio.bloom.core.port

import lab.doppio.bloom.core.model.Deal

interface DealRepository {
    /** Inserts when [Deal.id] is 0, updates otherwise. Returns the id. */
    suspend fun upsertDeal(d: Deal): Long

    suspend fun deals(): List<Deal>
}
