package lab.doppio.bloom.data.persistence.agentrun

import java.time.Instant
import lab.doppio.bloom.core.model.AccountId
import lab.doppio.bloom.core.model.Limits
import lab.doppio.bloom.core.port.AgentRunRepository
import lab.doppio.bloom.core.port.AgentStats
import lab.doppio.bloom.data.persistence.dbQuery
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.core.sum

class ExposedAgentRunRepository : AgentRunRepository {

    override suspend fun logAgentRun(agent: String, accountId: AccountId?, model: String, inTok: Int, outTok: Int, durationMs: Long, success: Boolean, error: String?) {
        dbQuery {
            AgentRunsTable.insert {
                it[this.agent] = agent; it[this.accountId] = accountId; it[this.model] = model; it[inputTokens] = inTok; it[outputTokens] = outTok
                it[this.durationMs] = durationMs; it[this.success] = success; it[this.error] = error?.take(Limits.AGENT_RUN_ERROR_CHARS)
            }
        }
    }

    override suspend fun tokensSince(since: Instant): Long = dbQuery {
        AgentRunsTable.select(AgentRunsTable.inputTokens.sum(), AgentRunsTable.outputTokens.sum()).where { AgentRunsTable.createdAt greaterEq since }.firstOrNull()
            ?.let { (it[AgentRunsTable.inputTokens.sum()] ?: 0).toLong() + (it[AgentRunsTable.outputTokens.sum()] ?: 0).toLong() } ?: 0L
    }

    override suspend fun agentStats(since: Instant): List<AgentStats> = dbQuery {
        AgentRunsTable.selectAll().where { AgentRunsTable.createdAt greaterEq since }.toList()
            .groupBy { it[AgentRunsTable.agent] }
            .map { (agent, rows) ->
                AgentStats(
                    agent, rows.size, rows.count { !it[AgentRunsTable.success] },
                    rows.sumOf { it[AgentRunsTable.inputTokens].toLong() }, rows.sumOf { it[AgentRunsTable.outputTokens].toLong() },
                    rows.map { it[AgentRunsTable.durationMs] }.average().toLong(),
                )
            }
    }
}
