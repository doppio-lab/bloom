package lab.doppio.bloom.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class EmailAddressTest {
    @Test
    fun `a single valid address is trimmed and lowercased`() {
        assertEquals("alex@velomoov.fr", EmailAddress.normalize("alex@velomoov.fr"))
        assertEquals("alex.martin+bloom@velomoov.fr", EmailAddress.normalize("  Alex.Martin+bloom@Velomoov.FR "))
        assertEquals("o'neil@sub.example.co.uk", EmailAddress.normalize("O'Neil@sub.example.co.uk"))
    }

    @Test
    fun `lists, display names, spaces and header injection are refused`() {
        for (bad in listOf(null, "", "   ", "nope", "@velomoov.fr", "alex@", "a@b", "a@b.fr, c@d.fr", "a@b.fr,c@d.fr", "a,b@c.fr", "a@b.fr;c@d.fr",
            "Alex <a@b.fr>", "<a@b.fr>", "a b@c.fr", "a@b.fr\r\nBcc: x@y.fr", "a@b.fr\nx", "\"a\"@b.fr", "a..b@c.fr", ".a@b.fr", "a@-b.fr", "a@b@c.fr")) {
            assertNull(EmailAddress.normalize(bad), "should refuse: $bad")
        }
    }

    @Test
    fun `a blank value is no email, an invalid one is an error`() {
        assertNull(EmailAddress.orNull(null))
        assertNull(EmailAddress.orNull("  "))
        assertEquals("a@b.fr", EmailAddress.orNull(" A@B.fr "))
        assertEquals("Adresse email invalide : « a@b.fr, c@d.fr »", assertFailsWith<IllegalArgumentException> { EmailAddress.orNull(" a@b.fr, c@d.fr ") }.message)
    }

    @Test
    fun `a subject never carries a line break`() {
        assertEquals("Objet Bcc: x@y.fr", EmailAddress.singleLine("Objet\r\nBcc: x@y.fr"))
        assertEquals("Objet simple", EmailAddress.singleLine("Objet simple"))
    }
}
