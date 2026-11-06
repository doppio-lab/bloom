package lab.doppio.bloom.core.port

/** Only one instance may run the background loops, or every email would leave twice. */
interface InstanceLock {
    /** True when this instance holds the lock (taking it if free), false while another instance holds it. */
    fun tryHold(): Boolean
}
