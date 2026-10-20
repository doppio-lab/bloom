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
}
