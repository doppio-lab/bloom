package lab.doppio.bloom.agents

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlin.reflect.KClass
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberFunctions
import kotlin.reflect.full.memberProperties
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Every description the model reads about a tool or a structured output (class, function, parameter, field),
 * pinned in one golden file taken from the code before the texts moved out of the annotations' call sites.
 */
class DescriptionTextsTest {
    private fun render(): String = buildString {
        val classes: List<KClass<*>> = listOf(
            SiteResearchTools::class, MetricsTools::class, WriterAgent.DraftOutput::class,
            ResearchAgent.DossierOutput::class, ResearchAgent.Hypothesis::class, ReplyClassifierAgent.Classification::class,
        )
        for (c in classes) {
            val name = c.simpleName
            c.findAnnotation<LLMDescription>()?.let { appendLine("$name: ${it.value}") }
            c.memberFunctions.filter { it.findAnnotation<LLMDescription>() != null }.sortedBy { it.name }.forEach { f ->
                appendLine("$name.${f.name}: ${f.findAnnotation<LLMDescription>()!!.value}")
                f.parameters.forEach { p -> p.findAnnotation<LLMDescription>()?.let { appendLine("$name.${f.name}(${p.name}): ${it.value}") } }
            }
            c.memberProperties.filter { it.findAnnotation<LLMDescription>() != null }.sortedBy { it.name }.forEach { p ->
                appendLine("$name.${p.name}: ${p.findAnnotation<LLMDescription>()!!.value}")
            }
        }
    }

    @Test
    fun `tool and structured output descriptions are the pinned ones`() {
        val golden = checkNotNull(javaClass.getResource("/prompts/descriptions.txt")) { "missing golden descriptions" }.readText()
        assertEquals(golden, render())
    }
}
