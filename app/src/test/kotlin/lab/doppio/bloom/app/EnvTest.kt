package lab.doppio.bloom.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EnvTest {
    @Test
    fun `dry run accepts only true and false`() {
        assertEquals(true, parseFlag("DRY_RUN", null, default = true))
        assertEquals(true, parseFlag("DRY_RUN", "", default = true))
        assertEquals(true, parseFlag("DRY_RUN", "true", default = false))
        assertEquals(false, parseFlag("DRY_RUN", "false", default = true))
        listOf("yes", "1", "TRUE", "true ", "False").forEach { value ->
            assertFailsWith<IllegalStateException>(value) { parseFlag("DRY_RUN", value, default = true) }
        }
    }

    @Test
    fun `secrets must be set, changed from the example and long enough`() {
        listOf(null, "", "   ", "change-me", "une-longue-chaine-aleatoire", "too-short-15-ch").forEach { value ->
            assertFailsWith<IllegalArgumentException>(value.toString()) { requireSecret("ADMIN_PASSWORD", value) }
        }
        val good = "0123456789abcdef"
        assertEquals(good, requireSecret("ADMIN_PASSWORD", good))
    }
}
