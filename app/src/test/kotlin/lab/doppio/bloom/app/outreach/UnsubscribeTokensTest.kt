package lab.doppio.bloom.app.outreach

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UnsubscribeTokensTest {
    private val tokens = UnsubscribeTokens("secret-long-enough-0")

    @Test
    fun `a token gives back its contact`() {
        assertEquals(42L, tokens.verify(tokens.tokenFor(42)))
    }

    @Test
    fun `a forged, truncated or foreign token gives nothing`() {
        val token = tokens.tokenFor(42)
        assertNull(tokens.verify("42.0000000000000000000"))
        assertNull(tokens.verify(token.dropLast(1)))
        assertNull(tokens.verify(token + "0"))
        assertNull(tokens.verify("43." + token.substringAfter('.')), "the signature is bound to the contact id")
        assertNull(tokens.verify("nope"))
        assertNull(tokens.verify(UnsubscribeTokens("another-secret-0000").tokenFor(42)))
    }
}
