package lab.doppio.bloom.core.port

/** Only one instance may run the background loops, or every email would leave twice. */
interface InstanceLock {
    /** True when this instance holds the lock (taking it if free), false while another instance holds it. */
    fun tryHold(): Boolean

    /**
     * True while this instance still holds the lock. The lock lives with its database connection: a server restart or
     * a network cut frees it silently, so the loops ask again before each tick instead of trusting the first answer.
     */
    fun isHeld(): Boolean
}
