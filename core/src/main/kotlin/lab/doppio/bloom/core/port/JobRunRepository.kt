package lab.doppio.bloom.core.port

import java.time.Instant
import java.time.LocalDate

/** Scheduled jobs that must run once per period, across restarts and instances. */
interface JobRunRepository {
    /**
     * Claims a job for a period (a day, the Monday of a week, the 1st of a month). True only for the first
     * caller of that period. [now] defaults to the clock.
     */
    suspend fun claimJob(name: String, period: LocalDate, now: Instant? = null): Boolean

    suspend fun finishJob(name: String, error: String? = null, now: Instant? = null)
}
