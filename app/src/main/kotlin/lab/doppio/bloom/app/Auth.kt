package lab.doppio.bloom.app

import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** Constant-time comparison of two secrets: a plain == stops at the first wrong byte. */
internal fun secretsMatch(expected: String, given: String): Boolean =
    MessageDigest.isEqual(expected.toByteArray(Charsets.UTF_8), given.toByteArray(Charsets.UTF_8))

/**
 * Holds off a host after too many failed logins in the last minute. The RateLimit plugin counts requests,
 * not failures, so this small sliding window sits next to it. Memory: one short list per failing host.
 */
internal class FailedAuthLimiter(private val maxPerMinute: Int = 20) {
    private val failures = ConcurrentHashMap<String, ArrayDeque<Instant>>()

    private fun recent(host: String, now: Instant): ArrayDeque<Instant> {
        val window = failures.getOrPut(host) { ArrayDeque() }
        synchronized(window) { while (window.isNotEmpty() && window.first().isBefore(now.minusSeconds(60))) window.removeFirst() }
        return window
    }

    fun allow(host: String, now: Instant = Instant.now()): Boolean {
        val window = recent(host, now)
        return synchronized(window) { window.size < maxPerMinute }
    }

    fun recordFailure(host: String, now: Instant = Instant.now()) {
        val window = recent(host, now)
        synchronized(window) { window.addLast(now) }
    }
}
