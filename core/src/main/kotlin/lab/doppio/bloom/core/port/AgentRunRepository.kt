package lab.doppio.bloom.core.port

import java.time.Instant
import lab.doppio.bloom.core.model.AccountId

/** One row per LLM call: tokens, duration, outcome. */
interface AgentRunRepository {
    suspend fun logAgentRun(agent: String, accountId: AccountId?, model: String, inTok: Int, outTok: Int, durationMs: Long, success: Boolean, error: String?)

    /** Input plus output tokens of every agent run since [since]. */
    suspend fun tokensSince(since: Instant): Long

    suspend fun agentStats(since: Instant): List<AgentStats>
}
