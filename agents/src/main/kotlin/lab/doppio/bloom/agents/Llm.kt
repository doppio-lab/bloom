package lab.doppio.bloom.agents

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.features.eventHandler.feature.EventHandler
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.executor.model.StructureFixingParser
import ai.koog.prompt.executor.ollama.client.ContextWindowStrategy
import ai.koog.prompt.executor.ollama.client.OllamaClient
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.structure.StructuredResponse
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import lab.doppio.bloom.data.db.Repository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

enum class LlmProvider {
    /** Anthropic API, paid. The default, meant for production. */
    ANTHROPIC,

    /** Local model served by Ollama: free, nothing leaves the machine. */
    OLLAMA,

    /** OpenAI or any server compatible with its API (LM Studio, llama.cpp, vLLM, OpenRouter, Mistral). */
    OPENAI,
    ;

    companion object {
        fun parse(value: String): LlmProvider = entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) }
            ?: throw IllegalArgumentException("LLM_PROVIDER inconnu : $value. Valeurs possibles : ${entries.joinToString { it.name.lowercase() }}")
    }
}

/**
 * @param model main model (research, writing, coach). Null: the provider's default.
 * @param fastModel reply classifier model. Null: the provider's default, otherwise the main model.
 * @param contextLength context window requested from Ollama and declared for custom models.
 */
data class LlmConfig(
    val provider: LlmProvider,
    val apiKey: String? = null,
    val baseUrl: String? = null,
    val model: String? = null,
    val fastModel: String? = null,
    val contextLength: Long = 16_384,
)

/**
 * Provider and per-task model selection. The writer and the researcher need judgement,
 * the reply classifier has to be fast and cheap.
 */
class Llm private constructor(
    val provider: LlmProvider,
    rawExecutor: PromptExecutor,
    val research: LLModel,
    val fast: LLModel,
) {
    constructor(config: LlmConfig) : this(parts(config))

    private constructor(parts: Parts) : this(parts.provider, parts.executor, parts.research, parts.fast)

    /** Every call goes through [MeteredExecutor] so the tracker sees its tokens, even when the call fails later. */
    val executor: PromptExecutor = MeteredExecutor(rawExecutor)
    val writer: LLModel = research

    /** Repairs a malformed structured answer with the fast model, twice at most, instead of failing the run. */
    val fixingParser: StructureFixingParser = StructureFixingParser(model = fast, retries = 2)

    /** Summary logged at startup, without secrets. */
    fun describe(): String = "${provider.name.lowercase()} (principal : ${research.id}, rapide : ${fast.id})"

    private class Parts(val provider: LlmProvider, val executor: PromptExecutor, val research: LLModel, val fast: LLModel)

    companion object {
        const val DEFAULT_OLLAMA_URL = "http://localhost:11434"
        const val DEFAULT_OLLAMA_MODEL = "mistral-small3.2"

        private val OLLAMA_CAPABILITIES = listOf(LLMCapability.Temperature, LLMCapability.Tools, LLMCapability.Schema.JSON.Standard)

        /** Chat Completions API: the only one every compatible server implements. */
        private val OPENAI_CAPABILITIES = listOf(
            LLMCapability.Temperature, LLMCapability.Tools, LLMCapability.ToolChoice, LLMCapability.Completion,
            LLMCapability.Schema.JSON.Standard, LLMCapability.OpenAIEndpoint.Completions,
        )

        /** An Llm over any executor (Koog's mock executor, a hand-written fake): the agents never notice. */
        internal fun forTesting(executor: PromptExecutor, main: LLModel, fast: LLModel = main, provider: LlmProvider = LlmProvider.OPENAI): Llm =
            Llm(provider, executor, main, fast)

        private fun parts(config: LlmConfig): Parts = when (config.provider) {
            LlmProvider.ANTHROPIC -> {
                val key = requireNotNull(config.apiKey) { "ANTHROPIC_API_KEY est obligatoire avec LLM_PROVIDER=anthropic" }
                Parts(
                    provider = config.provider,
                    executor = MultiLLMPromptExecutor(AnthropicLLMClient(key)),
                    research = config.model?.let { AnthropicModels.Sonnet_5.copy(id = it) } ?: AnthropicModels.Sonnet_5,
                    fast = config.fastModel?.let { AnthropicModels.Haiku_4_5.copy(id = it) } ?: AnthropicModels.Haiku_4_5,
                )
            }
            LlmProvider.OLLAMA -> {
                val research = localModel(LLMProvider.Ollama, config.model ?: DEFAULT_OLLAMA_MODEL, config.contextLength, OLLAMA_CAPABILITIES)
                Parts(
                    provider = config.provider,
                    executor = MultiLLMPromptExecutor(
                        OllamaClient(
                            baseUrl = config.baseUrl ?: DEFAULT_OLLAMA_URL,
                            // Otherwise Ollama keeps its small default window and truncates the pages read by the researcher
                            contextWindowStrategy = ContextWindowStrategy.Companion.Fixed(config.contextLength),
                        ),
                    ),
                    research = research,
                    // One model by default: loading two in memory costs more than the small one saves
                    fast = config.fastModel?.let { localModel(LLMProvider.Ollama, it, config.contextLength, OLLAMA_CAPABILITIES) } ?: research,
                )
            }
            LlmProvider.OPENAI -> {
                val id = requireNotNull(config.model) { "LLM_MODEL est obligatoire avec LLM_PROVIDER=openai" }
                val settings = config.baseUrl?.let { OpenAIClientSettings(baseUrl = it) } ?: OpenAIClientSettings()
                val research = localModel(LLMProvider.OpenAI, id, config.contextLength, OPENAI_CAPABILITIES)
                Parts(
                    provider = config.provider,
                    // Local compatible servers need no key, but the header must be present
                    executor = MultiLLMPromptExecutor(OpenAILLMClient(config.apiKey ?: "not-needed", settings)),
                    research = research,
                    fast = config.fastModel?.let { localModel(LLMProvider.OpenAI, it, config.contextLength, OPENAI_CAPABILITIES) } ?: research,
                )
            }
        }

        private fun localModel(provider: LLMProvider, id: String, contextLength: Long, capabilities: List<LLMCapability>) =
            LLModel(provider = provider, id = id, capabilities = capabilities, contextLength = contextLength)
    }
}

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

/** What the tracker needs from the database (the agent_runs table). Internal so tests can use an in-memory log. */
internal interface RunLog {
    suspend fun logAgentRun(agent: String, accountId: Long?, model: String, inTok: Int, outTok: Int, durationMs: Long, success: Boolean, error: String?)
    suspend fun tokensSince(since: Instant): Long
}

private class RepositoryRunLog(private val repo: Repository) : RunLog {
    override suspend fun logAgentRun(agent: String, accountId: Long?, model: String, inTok: Int, outTok: Int, durationMs: Long, success: Boolean, error: String?) {
        repo.logAgentRun(agent, accountId, model, inTok, outTok, durationMs, success, error)
    }

    override suspend fun tokensSince(since: Instant): Long = repo.tokensSince(since)
}

/** Mesure chaque exécution d'agent (durée, tokens, échec) dans agent_runs pour le dashboard. */
class RunTracker internal constructor(
    private val repo: RunLog,
    /** Tokens allowed per day, all agents together. 0: no limit. */
    private val dailyTokenBudget: Long = 0,
    private val zone: ZoneId = ZoneId.of("Europe/Paris"),
) {
    constructor(repo: Repository, dailyTokenBudget: Long = 0, zone: ZoneId = ZoneId.of("Europe/Paris")) :
        this(RepositoryRunLog(repo), dailyTokenBudget, zone)

    /** Refuses a new run once the day's budget is spent: the caller retries later, nothing is lost. */
    private suspend fun checkBudget(agent: String) {
        if (dailyTokenBudget <= 0) return
        val startOfDay = LocalDate.now(zone).atStartOfDay(zone).toInstant()
        val used = repo.tokensSince(startOfDay)
        if (used >= dailyTokenBudget) throw TokenBudgetExceeded("Budget de $dailyTokenBudget tokens du jour atteint ($used) : $agent attend demain")
    }

    /** For tool-using agents: the tokens come from [counter], summed over every call of the run. */
    suspend fun <T> trackAgent(agent: String, accountId: Long?, model: LLModel, counter: TokenCounter, block: suspend () -> T): T {
        checkBudget(agent)
        val start = System.currentTimeMillis()
        return try {
            block().also {
                repo.logAgentRun(agent, accountId, model.id, counter.inputTokens, counter.outputTokens, System.currentTimeMillis() - start, true, null)
            }
        } catch (e: Exception) {
            repo.logAgentRun(agent, accountId, model.id, counter.inputTokens, counter.outputTokens, System.currentTimeMillis() - start, false, e.message)
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
            repo.logAgentRun(agent, accountId, model.id, inTok, outTok, System.currentTimeMillis() - start, true, null)
            value
        } catch (e: Exception) {
            // The calls made before the failure were paid for: they count against the budget
            repo.logAgentRun(agent, accountId, model.id, counter.inputTokens, counter.outputTokens, System.currentTimeMillis() - start, false, e.message)
            throw e
        }
    }
}

class TokenBudgetExceeded(message: String) : IllegalStateException(message)
