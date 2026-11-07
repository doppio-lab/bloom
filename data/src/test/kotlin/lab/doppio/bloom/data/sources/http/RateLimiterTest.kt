package lab.doppio.bloom.data.sources.http

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import lab.doppio.bloom.data.sources.MutableClock
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class RateLimiterTest {
    private val clock = MutableClock(Instant.parse("2026-11-06T10:00:00Z"))

    @Test
    fun `le premier appel passe sans attendre`() = runTest {
        val limiter = RateLimiter(Duration.ofSeconds(3), clock)
        assertEquals("a", limiter.run { "a" })
        assertEquals(0, currentTime)
    }

    @Test
    fun `deux appels au meme instant sont espaces de l'intervalle`() = runTest {
        val limiter = RateLimiter(Duration.ofSeconds(3), clock)
        limiter.run { }
        limiter.run { }
        assertEquals(3_000, currentTime)
    }

    @Test
    fun `on n'attend que le temps restant de l'intervalle`() = runTest {
        val limiter = RateLimiter(Duration.ofSeconds(3), clock)
        limiter.run { }
        clock.advanceSeconds(2)
        limiter.run { }
        assertEquals(1_000, currentTime)
    }

    @Test
    fun `apres l'intervalle il n'y a plus d'attente`() = runTest {
        val limiter = RateLimiter(Duration.ofSeconds(3), clock)
        limiter.run { }
        clock.advanceSeconds(10)
        limiter.run { }
        assertEquals(0, currentTime)
    }

    @Test
    fun `un appel en echec rend la main et laisse passer le suivant`() = runTest {
        val limiter = RateLimiter(Duration.ofSeconds(1), clock)
        runCatching { limiter.run { error("boom") } }
        assertEquals("ok", limiter.run { "ok" })
    }
}
