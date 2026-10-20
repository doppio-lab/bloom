package lab.doppio.bloom.agents

import ai.koog.agents.testing.tools.MockExecutorDSLBuilder
import ai.koog.agents.testing.tools.getMockExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.tokenizer.Tokenizer
import java.time.Instant

/** In-memory agent_runs: the budget is computed from what was logged, as the real table does. */
internal class InMemoryRunLog : RunLog {
    class Run(val agent: String, val inTok: Int, val outTok: Int, val success: Boolean, val error: String?)

    val runs = mutableListOf<Run>()

    override suspend fun logAgentRun(agent: String, accountId: Long?, model: String, inTok: Int, outTok: Int, durationMs: Long, success: Boolean, error: String?) {
        runs += Run(agent, inTok, outTok, success, error)
    }

    override suspend fun tokensSince(since: Instant): Long = runs.sumOf { (it.inTok + it.outTok).toLong() }
}

internal object TestSupport {
    /** Declares native JSON output, as the production models do, so the prompt reaches the fake unchanged. */
    val model = LLModel(
        provider = LLMProvider.OpenAI,
        id = "fake",
        capabilities = listOf(LLMCapability.Temperature, LLMCapability.Tools, LLMCapability.Schema.JSON.Standard),
        contextLength = 16_384,
    )

    /** One token per word, enough to check that tokens are accounted for. */
    private val wordTokenizer = object : Tokenizer {
        override fun countTokens(text: String): Int = text.split(Regex("\\s+")).count { it.isNotBlank() }
    }

    /** An Llm whose model is Koog's mock executor, configured by [init]. */
    fun llm(init: MockExecutorDSLBuilder.() -> Unit): Llm =
        Llm.forTesting(getMockExecutor(tokenizer = wordTokenizer, init = init), model)
}
