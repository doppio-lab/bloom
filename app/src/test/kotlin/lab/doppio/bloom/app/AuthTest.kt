package lab.doppio.bloom.app

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthTest {
    @Test
    fun `secrets are compared whole, including length`() {
        assertTrue(secretsMatch("a-real-secret-of-16-chars", "a-real-secret-of-16-chars"))
        assertFalse(secretsMatch("a-real-secret-of-16-chars", "a-real-secret-of-16-charsX"))
        assertFalse(secretsMatch("a-real-secret-of-16-chars", "A-real-secret-of-16-chars"))
        assertFalse(secretsMatch("", "x"))
    }

    @Test
    fun `a host that fails to log in twenty times in a minute is held off until the minute passes`() {
        val limiter = FailedAuthLimiter(maxPerMinute = 20)
        val t0 = Instant.parse("2026-10-13T08:00:00Z")
        repeat(20) { limiter.recordFailure("10.0.0.1", t0.plusSeconds(it.toLong())) }
        assertFalse(limiter.allow("10.0.0.1", t0.plusSeconds(30)))
        assertTrue(limiter.allow("10.0.0.2", t0.plusSeconds(30)), "another host is not affected")
        assertTrue(limiter.allow("10.0.0.1", t0.plusSeconds(61)), "the oldest failures have aged out")
    }
}
