package lab.doppio.bloom.agents

import ai.koog.agents.testing.tools.mockLLMAnswer
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import lab.doppio.bloom.core.guard.MessageGuard
import lab.doppio.bloom.core.model.Channel
import lab.doppio.bloom.core.model.Fact
import lab.doppio.bloom.core.model.FactKind
import lab.doppio.bloom.core.model.Offer
import lab.doppio.bloom.core.model.Sector
import lab.doppio.bloom.data.db.Repository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WriterAgentTest {
    private val facts = listOf(Fact("abc", 1L, FactKind.WEB_PAGE, "Notre équipe grandit vite", "https://exemple.fr/", "2026-10-20T00:00:00Z"))

    private fun draft(body: String) = Json.encodeToString(
        WriterAgent.DraftOutput.serializer(),
        WriterAgent.DraftOutput(subject = "équipe mobile", body = body, citedFactIds = listOf("abc"), referenceKey = "", rationale = "test"),
    )

    // "synergie" is on the banned list: a BLOCK violation the loop has to repair
    private val violating = draft("Votre équipe grandit vite [F:abc]. Une synergie avec Doppio ?")
    private val clean = draft("Votre équipe grandit vite [F:abc]. Vous développez en natif ?")

    private suspend fun compose(llm: Llm, log: InMemoryRunLog, maxRepairs: Int = 2) =
        WriterAgent(llm, Repository(), RunTracker(log), senderName = "Renaud", maxRepairs = maxRepairs).compose(
            promptId = "draft-test",
            accountId = 1L,
            context = "Destinataire : Prénom Nom.\nFaits disponibles :\n[abc] Notre équipe grandit vite",
            channel = Channel.EMAIL,
            stepIndex = 0,
            facts = facts,
            sector = Sector.OTHER,
            offer = Offer.DISCOVERY,
            language = "fr",
        )

    @Test
    fun `a draft refused by the guard is sent back with the violations and the repaired one is kept`() = runTest {
        val log = InMemoryRunLog()
        val llm = TestSupport.llm {
            mockLLMAnswer(violating).asDefaultResponse
            mockLLMAnswer(clean) onCondition { request -> "refusé par le contrôle qualité" in request && "synergie" in request }
        }
        val (output, violations, attempts) = compose(llm, log)
        assertEquals(2, attempts)
        assertTrue(MessageGuard.isPublishable(violations), violations.toString())
        assertFalse("synergie" in output.body)
        assertEquals(2, log.runs.size, "each attempt is one tracked run")
    }

    @Test
    fun `the repair loop stops after maxRepairs and keeps the last report`() = runTest {
        val log = InMemoryRunLog()
        val llm = TestSupport.llm { mockLLMAnswer(violating).asDefaultResponse }
        val (_, violations, attempts) = compose(llm, log, maxRepairs = 2)
        assertEquals(3, attempts, "first draft plus two repairs")
        assertFalse(MessageGuard.isPublishable(violations))
        assertTrue(violations.any { it.rule == "vocabulaire" })
        assertEquals(3, log.runs.size)
    }
}
