package lab.doppio.bloom.agents

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SiteResearchToolsTest {
    private val page = "Bienvenue chez Exemple. Notre équipe grandit vite et recrute des développeurs mobiles."

    private fun tools(domain: String = "exemple.fr") = SiteResearchTools(1L, domain) { page }

    @Test
    fun `a recorded fact states the verbatim quote, never the model's interpretation`() = runTest {
        val t = tools()
        t.readPage("https://exemple.fr/")
        val answer = t.recordQuote("https://exemple.fr/", "Notre équipe grandit vite", "L'équipe compte 40 développeurs", "HIRING")
        val fact = t.recorded.single()
        assertEquals("Notre équipe grandit vite", fact.statement)
        assertFalse("40" in fact.statement, "a figure invented by the model must not become a sourced fact")
        assertEquals("Notre équipe grandit vite", fact.excerpt)
        assertTrue(answer.startsWith("Enregistré"), answer)
    }

    @Test
    fun `a quote that is not on the page is refused`() = runTest {
        val t = tools()
        t.readPage("https://exemple.fr/")
        val answer = t.recordQuote("https://exemple.fr/", "Nous avons 40 développeurs", "Effectif", "HIRING")
        assertTrue(answer.startsWith("Refusé"), answer)
        assertTrue(t.recorded.isEmpty())
    }
}
