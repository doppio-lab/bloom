package lab.doppio.bloom.agents

import kotlinx.coroutines.test.runTest
import lab.doppio.bloom.core.model.ReplyIntent
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReplyClassifierAgentTest {
    private val runRepo = FakeAgentRunRepository()

    private fun classifier(executor: CannedExecutor) = ReplyClassifierAgent(fakeLlm(executor), RunTracker(runRepo, clock = fixedClock), fixedClock)

    private fun answer(intent: String, confidence: Double = 0.9, followUpOn: String = "", referral: String = "", suggestedReply: String = "") =
        """{"intent":"$intent","confidence":$confidence,"followUpOn":"$followUpOn","referral":"$referral","summary":"Résumé","suggestedReply":"$suggestedReply"}"""

    @Test
    fun `the structured answer becomes a classification`() = runTest {
        val executor = CannedExecutor(answer("meeting_request", 0.8, followUpOn = "2026-12-01", suggestedReply = "Jeudi 10h ?"))
        val result = classifier(executor).classify("Re: Crash au paiement", "Oui, appelez-moi jeudi.", accountId = 5)

        assertEquals(ReplyIntent.MEETING_REQUEST, result.intent)
        assertEquals(0.8, result.confidence)
        assertEquals(LocalDate.parse("2026-12-01"), result.followUpOn)
        assertNull(result.referral)
        assertEquals("Résumé", result.summary)
        assertEquals("Jeudi 10h ?", result.suggestedReply)
        assertEquals(1, executor.calls)
        // The model is told today's date from the injected clock
        assertTrue("Date du jour : 2026-11-06" in executor.prompts.single().messages.first().textContent())
        assertEquals(listOf("reply-classifier"), runRepo.runs.map { it.agent })
        assertEquals(10, runRepo.runs.single().inTok)
    }

    @Test
    fun `an unknown intent falls back to OTHER, confidence is clamped and a bad date is dropped`() = runTest {
        val executor = CannedExecutor(answer("maybe_later", confidence = 1.7, followUpOn = "bientôt", referral = "Jean Dupont, CTO"))
        val result = classifier(executor).classify(null, "Peut-être.", accountId = null)

        assertEquals(ReplyIntent.OTHER, result.intent)
        assertEquals(1.0, result.confidence)
        assertNull(result.followUpOn)
        assertEquals("Jean Dupont, CTO", result.referral)
        assertNull(result.suggestedReply)
    }

    @Test
    fun `an explicit opt-out is decided by code without calling the model`() = runTest {
        val executor = CannedExecutor(answer("INTERESTED"))
        val result = classifier(executor).classify("Re: x", "Merci de me désinscrire de vos envois.", accountId = 5)

        assertEquals(ReplyIntent.UNSUBSCRIBE, result.intent)
        assertEquals(1.0, result.confidence)
        assertEquals(0, executor.calls)
        assertTrue(runRepo.runs.isEmpty())
    }

    @Test
    fun `the quoted thread is not sent to the model`() = runTest {
        val executor = CannedExecutor(answer("NOT_NOW"))
        classifier(executor).classify("Re: x", "Pas maintenant.\nLe 3 nov., Renaud a écrit :\n> Bonjour, on peut auditer l'app", accountId = 5)
        val sent = executor.userText(0)
        assertTrue("Pas maintenant." in sent)
        assertFalse("auditer" in sent)
    }
}

class ReplyHelpersTest {
    @Test
    fun `trimQuoted cuts at the first quoted line or attribution`() {
        assertEquals("Oui.\nMerci", trimQuoted("Oui.\nMerci\n> citation\nsuite"))
        assertEquals("Oui.", trimQuoted("Oui.\nLe 3 nov., Renaud a écrit :\ntexte"))
        assertEquals("Yes.", trimQuoted("Yes.\nOn Mon, Renaud wrote:\ntext"))
    }

    @Test
    fun `trimQuoted caps the length`() {
        assertEquals(4_000, trimQuoted("x".repeat(10_000)).length)
    }

    @Test
    fun `hard stops are caught in the first 400 characters only`() {
        listOf("Désinscrivez-moi", "Please unsubscribe me", "Ne plus me contacter svp", "retirez-moi de la liste", "remove me", "opt out", "Stop.").forEach {
            assertTrue(isHardStop(it), it)
        }
        assertFalse(isHardStop("Intéressé, rappelez-moi la semaine prochaine."))
        assertFalse(isHardStop("a".repeat(401) + " unsubscribe"))
    }
}
