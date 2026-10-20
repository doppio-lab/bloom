package lab.doppio.bloom.agents

import ai.koog.agents.testing.tools.mockLLMAnswer
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import lab.doppio.bloom.core.model.ReplyIntent
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReplyClassifierAgentTest {
    private val log = InMemoryRunLog()
    private val tracker = RunTracker(log)

    @Test
    fun `an opt-out is decided by the code without calling the model`() = runTest {
        // Any call would get a non-JSON answer and fail the structured parsing
        val llm = TestSupport.llm { mockLLMAnswer("le modèle ne doit pas être appelé").asDefaultResponse }
        val result = ReplyClassifierAgent(llm, tracker).classify("Re: app mobile", "Merci de me désinscrire de vos envois.", 1L)
        assertEquals(ReplyIntent.UNSUBSCRIBE, result.intent)
        assertEquals(1.0, result.confidence)
        assertNull(result.suggestedReply)
        assertTrue(log.runs.isEmpty(), "no LLM run must be logged on the hard-stop path")
    }

    @Test
    fun `an interested reply goes to the model and its tokens are logged`() = runTest {
        val answer = Json.encodeToString(
            ReplyClassifierAgent.Classification.serializer(),
            ReplyClassifierAgent.Classification("NOT_NOW", 0.8, "2027-01-15", "", "Revenir en janvier.", "Merci, je reviens vers vous en janvier."),
        )
        val llm = TestSupport.llm { mockLLMAnswer(answer).asDefaultResponse }
        val result = ReplyClassifierAgent(llm, tracker).classify(null, "Pas maintenant, revenez en janvier.", 1L, today = LocalDate.of(2026, 10, 20))
        assertEquals(ReplyIntent.NOT_NOW, result.intent)
        assertEquals(LocalDate.of(2027, 1, 15), result.followUpOn)
        val run = log.runs.single()
        assertEquals("reply-classifier", run.agent)
        assertTrue(run.success)
        assertTrue(run.inTok > 0 && run.outTok > 0, "the metered executor must report the tokens of the call")
    }
}
