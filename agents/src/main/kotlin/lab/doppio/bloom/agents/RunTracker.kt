package lab.doppio.bloom.agents

import ai.koog.agents.features.eventHandler.feature.EventHandler
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.structure.StructuredResponse
import lab.doppio.bloom.core.model.BloomTime
import lab.doppio.bloom.core.port.AgentRunRepository

/**
 * Adds up the tokens of every LLM call a tool-using agent makes: a run is many calls, and the result
 * of agent.run() carries none of them.
 */
class TokenCounter {
    private val input = java.util.concurrent.atomic.AtomicInteger()
    private val output = java.util.concurrent.atomic.AtomicInteger()

    /** Passed as AIAgent's installFeatures. */
    val feature: ai.koog.agents.core.agent.GraphAIAgent.FeatureContext.() -> Unit = {
        install(EventHandler) {
            onLLMCallCompleted { ctx ->
                input.addAndGet(ctx.response?.metaInfo?.inputTokensCount ?: 0)
                output.addAndGet(ctx.response?.metaInfo?.outputTokensCount ?: 0)
            }
        }
    }

    val inputTokens: Int get() = input.get()
    val outputTokens: Int get() = output.get()
}

/** Mesure chaque exécution d'agent (durée, tokens, échec) dans agent_runs pour le dashboard. */
class RunTracker(
    private val agentRunRepo: AgentRunRepository,
    /** Tokens allowed per day, all agents together. 0: no limit. */
    private val dailyTokenBudget: Long = NO_TOKEN_LIMIT,
    private val zone: java.time.ZoneId = BloomTime.PARIS,
    private val clock: java.time.Clock = java.time.Clock.systemDefaultZone(),
) {
    companion object {
        /** A daily budget of 0 means no limit. */
        const val NO_TOKEN_LIMIT = 0L
    }

    /** Refuses a new run once the day's budget is spent: the caller retries later, nothing is lost. */
    private suspend fun checkBudget(agent: String) {
        if (dailyTokenBudget <= 0) return
        val startOfDay = java.time.LocalDate.now(clock.withZone(zone)).atStartOfDay(zone).toInstant()
        val used = agentRunRepo.tokensSince(startOfDay)
        if (used >= dailyTokenBudget) throw TokenBudgetExceeded("Budget de $dailyTokenBudget tokens du jour atteint ($used) : $agent attend demain")
    }

    /** For tool-using agents: the tokens come from [counter], summed over every call of the run. */
    suspend fun <T> trackAgent(agent: String, accountId: Long?, model: LLModel, counter: TokenCounter, block: suspend () -> T): T {
        checkBudget(agent)
        val start = System.currentTimeMillis()
        return try {
            block().also {
                agentRunRepo.logAgentRun(agent, accountId, model.id, counter.inputTokens, counter.outputTokens, System.currentTimeMillis() - start, true, null)
            }
        } catch (e: Exception) {
            agentRunRepo.logAgentRun(agent, accountId, model.id, counter.inputTokens, counter.outputTokens, System.currentTimeMillis() - start, false, e.message)
            throw e
        }
    }

    suspend fun <T> track(agent: String, accountId: Long?, model: LLModel, block: suspend () -> Pair<T, StructuredResponse<*>?>): T {
        checkBudget(agent)
        val start = System.currentTimeMillis()
        return try {
            val (value, response) = block()
            val meta = response?.message?.metaInfo
            agentRunRepo.logAgentRun(agent, accountId, model.id, meta?.inputTokensCount ?: 0, meta?.outputTokensCount ?: 0, System.currentTimeMillis() - start, true, null)
            value
        } catch (e: Exception) {
            agentRunRepo.logAgentRun(agent, accountId, model.id, 0, 0, System.currentTimeMillis() - start, false, e.message)
            throw e
        }
    }
}

class TokenBudgetExceeded(message: String) : IllegalStateException(message)
