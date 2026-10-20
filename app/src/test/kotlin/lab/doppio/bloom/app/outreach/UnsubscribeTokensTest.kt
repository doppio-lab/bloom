package lab.doppio.bloom.app.outreach

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UnsubscribeTokensTest {
    private val tokens = UnsubscribeTokens("a-secret-long-enough-for-tests")

    @Test
    fun `a token comes back to its contact`() {
        assertEquals(42L, tokens.verify(tokens.tokenFor(42)))
    }

    @Test
    fun `a tampered token, another secret or a non-numeric id are refused`() {
        val good = tokens.tokenFor(42)
        assertNull(tokens.verify("43." + good.substringAfter('.')))
        assertNull(tokens.verify(good.dropLast(1) + "0"))
        assertNull(UnsubscribeTokens("another-secret-long-enough").verify(good))
        assertNull(tokens.verify("abc.def"))
        assertNull(tokens.verify(""))
    }
}
