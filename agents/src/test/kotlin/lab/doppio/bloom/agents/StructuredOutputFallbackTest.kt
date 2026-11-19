package lab.doppio.bloom.agents

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.http.client.KoogHttpClientException
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.clients.LLMClientException
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import lab.doppio.bloom.core.model.ReplyIntent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Instant as KInstant

/**
 * Some local servers cannot constrain the output to a schema (Ollama answers 501 for a model served by its MLX
 * runner). The structured agents then describe the format in the prompt instead of failing.
 */
class StructuredOutputFallbackTest {
    private val schemaModel = LLModel(
        provider = LLMProvider.Ollama,
        id = "mlx-model",
        capabilities = listOf(LLMCapability.Temperature, LLMCapability.Schema.JSON.Standard),
        contextLength = 8_192,
    )

    /** Refuses with [status] every request that carries a schema, answers [answer] to the others. */
    private class SchemaRefusingExecutor(private val status: Int, private val answer: String) : PromptExecutor() {
        /** One entry per call: true when the request asked the server for a schema. */
        val withSchema = mutableListOf<Boolean>()

        override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant {
            val schema = prompt.params.schema != null
            withSchema += schema
            if (schema) {
                val http = KoogHttpClientException(clientName = "OllamaClient", statusCode = status, errorBody = """{"error":"structured output is unavailable"}""")
                throw LLMClientException(clientName = "OllamaClient", message = http.message, cause = http)
            }
            return Message.Assistant(answer, ResponseMetaInfo(KInstant.fromEpochSeconds(0), inputTokensCount = 10, outputTokensCount = 5))
        }

        override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> = emptyFlow()
        override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult = error("not used")
        override fun close() {}
    }

    private val interested = """{"intent":"INTERESTED","confidence":0.9,"followUpOn":"","referral":"","summary":"Intéressée","suggestedReply":""}"""
    private val runs = FakeAgentRunRepository()

    private fun classifier(executor: PromptExecutor) =
        ReplyClassifierAgent(Llm("ollama", executor, research = schemaModel, fast = schemaModel), RunTracker(runs, clock = fixedClock), fixedClock)

    @Test
    fun `un serveur qui refuse le schéma reçoit le format dans le prompt et la réponse est lue`() = runTest {
        val executor = SchemaRefusingExecutor(status = 501, answer = interested)

        val result = classifier(executor).classify("Re: votre message", "Oui, parlons-en.", accountId = 1)

        assertEquals(ReplyIntent.INTERESTED, result.intent)
        assertEquals(listOf(true, false), executor.withSchema, "one refused call with a schema, then the same call without")
        val run = runs.runs.single()
        assertEquals(true, run.success, "the refused attempt is not a failed run")
        assertEquals(10 to 5, run.inTok to run.outTok)
    }

    @Test
    fun `le refus est retenu, les appels suivants ne redemandent pas de schéma`() = runTest {
        val executor = SchemaRefusingExecutor(status = 501, answer = interested)
        val agent = classifier(executor)

        agent.classify("Re: votre message", "Oui, parlons-en.", accountId = 1)
        agent.classify("Re: votre message", "Toujours partante.", accountId = 1)

        assertEquals(listOf(true, false, false), executor.withSchema)
    }

    @Test
    fun `une autre erreur du serveur n'est pas rejouée`() = runTest {
        val executor = SchemaRefusingExecutor(status = 500, answer = interested)

        assertFailsWith<LLMClientException> { classifier(executor).classify("Re: votre message", "Oui, parlons-en.", accountId = 1) }

        assertEquals(listOf(true), executor.withSchema)
        assertEquals(false, runs.runs.single().success)
    }
}
