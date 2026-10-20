package lab.doppio.bloom.app.outreach

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EmailInputTest {
    @Test
    fun `a single valid address is trimmed and lowercased`() {
        assertEquals("alex@velomoov.fr", normalizeEmail("alex@velomoov.fr"))
        assertEquals("alex.martin+bloom@velomoov.fr", normalizeEmail("  Alex.Martin+bloom@Velomoov.FR "))
    }

    @Test
    fun `lists, display names, spaces and header injection are refused`() {
        for (bad in listOf(null, "", "   ", "nope", "@velomoov.fr", "alex@", "a@b.fr, c@d.fr", "a@b.fr,c@d.fr", "a@b.fr;c@d.fr",
            "Alex <a@b.fr>", "<a@b.fr>", "a b@c.fr", "a@b.fr\r\nBcc: x@y.fr", "a@b.fr\nx", "\"a\"@b.fr")) {
            assertNull(normalizeEmail(bad), "should refuse: $bad")
        }
    }

    @Test
    fun `a subject never carries a line break`() {
        assertEquals("Objet Bcc: x@y.fr", cleanSubject("Objet\r\nBcc: x@y.fr"))
        assertEquals("Objet simple", cleanSubject("Objet simple"))
    }
}
