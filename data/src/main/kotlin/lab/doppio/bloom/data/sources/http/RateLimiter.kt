package lab.doppio.bloom.data.sources.http

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** Limiteur simple : Apple tolère environ 20 appels par minute sur l'API Search/Lookup. */
class RateLimiter(private val minInterval: Duration, private val clock: Clock = Clock.systemDefaultZone()) {
    private val mutex = Mutex()
    private var last = Instant.EPOCH
    suspend fun <T> run(block: suspend () -> T): T = mutex.withLock {
        val wait = Duration.between(clock.instant(), last.plus(minInterval)).toMillis()
        if (wait > 0) delay(wait)
        last = clock.instant()
        block()
    }
}
