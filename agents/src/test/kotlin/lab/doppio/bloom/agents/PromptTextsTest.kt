package lab.doppio.bloom.agents

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberFunctions
import kotlin.reflect.full.memberProperties
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The prompts are built from the guard and agent limits. These tests pin the rendered text, taken from the
 * code before the limits were named, so a change of wording or of a number shows up here.
 */
class PromptTextsTest {
    private fun golden(name: String): String =
        checkNotNull(javaClass.getResource("/prompts/$name.txt")) { "missing golden $name" }.readText()

    @Test
    fun `researcher prompt`() = assertEquals(golden("researcher"), Prompts.researcher)

    @Test
    fun `synthesis prompt`() = assertEquals(golden("synthesis"), Prompts.synthesis)

    @Test
    fun `writer prompt in French`() = assertEquals(golden("writer-fr"), Prompts.writer("fr", "{S}"))

    @Test
    fun `writer prompt in English`() = assertEquals(golden("writer-en"), Prompts.writer("en", "{S}"))

    @Test
    fun `reply classifier prompt`() = assertEquals(golden("reply-classifier"), Prompts.replyClassifier)

    @Test
    fun `coach prompt`() = assertEquals(golden("coach"), Prompts.coach)

    @Test
    fun `tool descriptions keep their numbers`() {
        fun fn(c: kotlin.reflect.KClass<*>, name: String) = c.memberFunctions.first { it.name == name }.findAnnotation<LLMDescription>()!!.value
        assertEquals("Liste les 15 comptes les mieux classés avec score, offre recommandée et raisons", fn(MetricsTools::class, "topAccounts"))
        assertEquals("Taux de réponse positive par type de signal à l'origine du contact, sur 90 jours", fn(MetricsTools::class, "replyRateBySignal"))
        assertEquals("Coût et fiabilité des agents sur 7 jours", fn(MetricsTools::class, "agentCosts"))
    }

    @Test
    fun `structured output descriptions keep their numbers`() {
        fun prop(c: kotlin.reflect.KClass<*>, name: String) = c.memberProperties.first { it.name == name }.findAnnotation<LLMDescription>()!!.value
        assertEquals("Résumé factuel, 3 phrases maximum", prop(ResearchAgent.DossierOutput::class, "summary"))
        assertEquals(
            "1 à 3 hypothèses de besoin, chacune avec les identifiants des faits qui l'appuient",
            prop(ResearchAgent.DossierOutput::class, "hypotheses"),
        )
        assertEquals("Objet de l'email, 2 à 6 mots. Vide pour LinkedIn et téléphone.", prop(WriterAgent.DraftOutput::class, "subject"))
    }

    @Test
    fun `agent limits keep their values`() {
        assertEquals(20, ResearchLimits.MAX_ITERATIONS)
        assertEquals(6, ResearchLimits.MAX_PAGES)
        assertEquals(5_000, ResearchLimits.PAGE_CHARS)
        assertEquals(12..300, ResearchLimits.QUOTE_MIN_CHARS..ResearchLimits.QUOTE_MAX_CHARS)
        assertEquals(2, WriterLimits.MAX_REPAIRS)
        assertEquals(3, WriterLimits.HUMAN_EXAMPLES)
        assertEquals(4_000, ReplyLimits.REPLY_CHARS)
        assertEquals(400, ReplyLimits.HARD_STOP_SCAN_CHARS)
        assertEquals(15, CoachLimits.MAX_ITERATIONS)
    }
}
