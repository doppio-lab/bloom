package lab.doppio.bloom.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConfigTest {
    @Test
    fun `DRY_RUN accepts only true or false, case and spaces aside`() {
        assertTrue(parseDryRun("true "))
        assertFalse(parseDryRun("FALSE"))
        assertTrue(parseDryRun(null), "absent: nothing leaves")
    }

    @Test
    fun `DRY_RUN refuses anything else instead of reading it as false`() {
        for (raw in listOf("yes", "1", "", "no")) {
            val e = assertFailsWith<IllegalStateException>(raw) { parseDryRun(raw) }
            assertTrue("DRY_RUN" in e.message.orEmpty(), e.message)
        }
    }

    private fun lookup(vararg pairs: Pair<String, String?>): (String) -> String? = { mapOf(*pairs)[it] }

    @Test
    fun `a blank variable counts as absent`() {
        assertEquals("8080", envValue(lookup("PORT" to "  "), "PORT", "8080"))
        assertEquals("9090", envValue(lookup("PORT" to "9090"), "PORT", "8080"))
        val e = assertFailsWith<IllegalStateException> { envValue(lookup("PORT" to ""), "PORT") }
        assertTrue("PORT" in e.message.orEmpty())
    }

    @Test
    fun `a secret must be set, not the example value, and long enough`() {
        assertEquals("a-real-secret-of-16-chars", requireSecret(lookup("ADMIN_PASSWORD" to " a-real-secret-of-16-chars "), "ADMIN_PASSWORD"))
        for (bad in listOf(null, "", "   ", "change-me", "une-longue-chaine-aleatoire", "short-secret")) {
            val e = assertFailsWith<IllegalStateException>("value: $bad") { requireSecret(lookup("UNSUBSCRIBE_SECRET" to bad), "UNSUBSCRIBE_SECRET") }
            assertTrue("UNSUBSCRIBE_SECRET" in e.message.orEmpty(), e.message)
        }
    }
}
