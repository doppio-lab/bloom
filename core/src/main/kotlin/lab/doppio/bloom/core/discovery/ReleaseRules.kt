package lab.doppio.bloom.core.discovery

/** When a store listing counts as stalled. Shared by the App Store and Google Play sources so both read the same. */
object ReleaseRules {
    const val STALL_AFTER_DAYS = 120L

    /** Days past [STALL_AFTER_DAYS] it takes to reach the full strength. */
    private const val STALL_RAMP_DAYS = 240.0

    /** A stall signal never weighs less than this. */
    private const val STALL_MIN_STRENGTH = 0.2

    /** Strength of a RELEASE_STALL signal for an app last released [days] days ago. Null while the app is still fresh. */
    fun stallStrength(days: Long): Double? =
        days.takeIf { it > STALL_AFTER_DAYS }?.let { ((it - STALL_AFTER_DAYS) / STALL_RAMP_DAYS).coerceIn(STALL_MIN_STRENGTH, 1.0) }
}
