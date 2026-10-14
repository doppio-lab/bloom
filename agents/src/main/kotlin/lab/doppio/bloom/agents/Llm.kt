package lab.doppio.bloom.agents

import ai.koog.agents.features.eventHandler.feature.EventHandler
import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.executor.ollama.client.ContextWindowStrategy
import ai.koog.prompt.executor.ollama.client.OllamaClient
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.structure.StructuredResponse
import lab.doppio.bloom.data.db.Repository

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
class Llm(config: LlmConfig) {
    val provider: LlmProvider = config.provider
    val executor: PromptExecutor
    val research: LLModel
    val writer: LLModel
    val fast: LLModel

    init {
        when (config.provider) {
            LlmProvider.ANTHROPIC -> {
                val key = requireNotNull(config.apiKey) { "ANTHROPIC_API_KEY est obligatoire avec LLM_PROVIDER=anthropic" }
                executor = MultiLLMPromptExecutor(AnthropicLLMClient(key))
                research = config.model?.let { AnthropicModels.Sonnet_5.copy(id = it) } ?: AnthropicModels.Sonnet_5
                fast = config.fastModel?.let { AnthropicModels.Haiku_4_5.copy(id = it) } ?: AnthropicModels.Haiku_4_5
            }
            LlmProvider.OLLAMA -> {
                executor = MultiLLMPromptExecutor(
                    OllamaClient(
                        baseUrl = config.baseUrl ?: DEFAULT_OLLAMA_URL,
                        // Otherwise Ollama keeps its small default window and truncates the pages read by the researcher
                        contextWindowStrategy = ContextWindowStrategy.Companion.Fixed(config.contextLength),
                    ),
                )
                research = localModel(LLMProvider.Ollama, config.model ?: DEFAULT_OLLAMA_MODEL, config.contextLength, OLLAMA_CAPABILITIES)
                // One model by default: loading two in memory costs more than the small one saves
                fast = config.fastModel?.let { localModel(LLMProvider.Ollama, it, config.contextLength, OLLAMA_CAPABILITIES) } ?: research
            }
            LlmProvider.OPENAI -> {
                val id = requireNotNull(config.model) { "LLM_MODEL est obligatoire avec LLM_PROVIDER=openai" }
                val settings = config.baseUrl?.let { OpenAIClientSettings(baseUrl = it) } ?: OpenAIClientSettings()
                // Local compatible servers need no key, but the header must be present
                executor = MultiLLMPromptExecutor(OpenAILLMClient(config.apiKey ?: "not-needed", settings))
                research = localModel(LLMProvider.OpenAI, id, config.contextLength, OPENAI_CAPABILITIES)
                fast = config.fastModel?.let { localModel(LLMProvider.OpenAI, it, config.contextLength, OPENAI_CAPABILITIES) } ?: research
            }
        }
        writer = research
    }

    /** Summary logged at startup, without secrets. */
    fun describe(): String = "${provider.name.lowercase()} (principal : ${research.id}, rapide : ${fast.id})"

    companion object {
        const val DEFAULT_OLLAMA_URL = "http://localhost:11434"
        const val DEFAULT_OLLAMA_MODEL = "mistral-small3.2"

        private val OLLAMA_CAPABILITIES = listOf(LLMCapability.Temperature, LLMCapability.Tools, LLMCapability.Schema.JSON.Standard)

        /** Chat Completions API: the only one every compatible server implements. */
        private val OPENAI_CAPABILITIES = listOf(
            LLMCapability.Temperature, LLMCapability.Tools, LLMCapability.ToolChoice, LLMCapability.Completion,
            LLMCapability.Schema.JSON.Standard, LLMCapability.OpenAIEndpoint.Completions,
        )

        private fun localModel(provider: LLMProvider, id: String, contextLength: Long, capabilities: List<LLMCapability>) =
            LLModel(provider = provider, id = id, capabilities = capabilities, contextLength = contextLength)
    }
}

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
    private val repo: Repository,
    /** Tokens allowed per day, all agents together. 0: no limit. */
    private val dailyTokenBudget: Long = 0,
    private val zone: java.time.ZoneId = java.time.ZoneId.of("Europe/Paris"),
) {
    /** Refuses a new run once the day's budget is spent: the caller retries later, nothing is lost. */
    private suspend fun checkBudget(agent: String) {
        if (dailyTokenBudget <= 0) return
        val startOfDay = java.time.LocalDate.now(zone).atStartOfDay(zone).toInstant()
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

    suspend fun <T> track(agent: String, accountId: Long?, model: LLModel, block: suspend () -> Pair<T, StructuredResponse<*>?>): T {
        checkBudget(agent)
        val start = System.currentTimeMillis()
        return try {
            val (value, response) = block()
            val meta = response?.message?.metaInfo
            repo.logAgentRun(agent, accountId, model.id, meta?.inputTokensCount ?: 0, meta?.outputTokensCount ?: 0, System.currentTimeMillis() - start, true, null)
            value
        } catch (e: Exception) {
            repo.logAgentRun(agent, accountId, model.id, 0, 0, System.currentTimeMillis() - start, false, e.message)
            throw e
        }
    }
}

class TokenBudgetExceeded(message: String) : IllegalStateException(message)
