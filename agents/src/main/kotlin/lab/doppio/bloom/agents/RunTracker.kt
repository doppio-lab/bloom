package lab.doppio.bloom.agents

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.features.eventHandler.feature.EventHandler
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.structure.StructuredResponse
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import lab.doppio.bloom.core.model.BloomTime
import lab.doppio.bloom.core.port.AgentRunRepository
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Adds up the tokens of every LLM call of a run: a tool-using agent makes many calls and the result
 * of agent.run() carries none of them, and a structured call that fails after the model answered
 * has still been paid for.
 *
 * Two ways to feed it: as AIAgent's [feature], or as a coroutine context element that
 * [MeteredExecutor] looks up on every call.
 */
class TokenCounter : AbstractCoroutineContextElement(TokenCounter) {
    private val input = AtomicInteger()
    private val output = AtomicInteger()

    /** Passed as AIAgent's installFeatures. */
    val feature: ai.koog.agents.core.agent.GraphAIAgent.FeatureContext.() -> Unit = {
        install(EventHandler) {
            onLLMCallCompleted { ctx -> add(ctx.response?.metaInfo) }
        }
    }

    fun add(inputTokens: Int, outputTokens: Int) {
        input.addAndGet(inputTokens)
        output.addAndGet(outputTokens)
    }

    fun add(meta: ResponseMetaInfo?) = add(meta?.inputTokensCount ?: 0, meta?.outputTokensCount ?: 0)

    val inputTokens: Int get() = input.get()
    val outputTokens: Int get() = output.get()
    val totalTokens: Int get() = inputTokens + outputTokens

    companion object Key : CoroutineContext.Key<TokenCounter> {
        /** The counter of the run in progress, installed by [RunTracker.track]. */
        suspend fun current(): TokenCounter? = currentCoroutineContext()[TokenCounter]
    }
}

/** Reports the tokens of every call to the [TokenCounter] of the calling coroutine, when there is one. */
internal class MeteredExecutor(private val delegate: PromptExecutor) : PromptExecutor() {
    override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant =
        delegate.execute(prompt, model, tools).also { TokenCounter.current()?.add(it.metaInfo) }

    override suspend fun executeMultipleChoices(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): List<Message.Assistant> =
        delegate.executeMultipleChoices(prompt, model, tools).also { choices -> choices.forEach { TokenCounter.current()?.add(it.metaInfo) } }

    override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> =
        delegate.executeStreaming(prompt, model, tools)

    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult = delegate.moderate(prompt, model)

    override suspend fun models(): List<LLModel> = delegate.models()

    override fun getStandardJsonSchemaGenerator(model: LLModel) = delegate.getStandardJsonSchemaGenerator(model)

    override fun getBasicJsonSchemaGenerator(model: LLModel) = delegate.getBasicJsonSchemaGenerator(model)

    override fun close() = delegate.close()
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

    /**
     * For single structured calls. A [TokenCounter] is installed in the coroutine context so that
     * [MeteredExecutor] reports every call of the run, including a call whose answer could not be parsed.
     */
    suspend fun <T> track(agent: String, accountId: Long?, model: LLModel, block: suspend () -> Pair<T, StructuredResponse<*>?>): T {
        checkBudget(agent)
        val counter = TokenCounter()
        val start = System.currentTimeMillis()
        return try {
            val (value, response) = withContext(counter) { block() }
            // The counter sees every call; the final message is the fallback for an executor that is not metered
            val meta = response?.message?.metaInfo
            val inTok = if (counter.totalTokens > 0) counter.inputTokens else meta?.inputTokensCount ?: 0
            val outTok = if (counter.totalTokens > 0) counter.outputTokens else meta?.outputTokensCount ?: 0
            agentRunRepo.logAgentRun(agent, accountId, model.id, inTok, outTok, System.currentTimeMillis() - start, true, null)
            value
        } catch (e: Exception) {
            // The calls made before the failure were paid for: they count against the budget
            agentRunRepo.logAgentRun(agent, accountId, model.id, counter.inputTokens, counter.outputTokens, System.currentTimeMillis() - start, false, e.message)
            throw e
        }
    }
}

class TokenBudgetExceeded(message: String) : IllegalStateException(message)
