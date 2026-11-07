package lab.doppio.bloom.core.usecase

import lab.doppio.bloom.core.model.DiscoveryReport
import lab.doppio.bloom.core.port.Discovery
import java.util.concurrent.atomic.AtomicBoolean

/** One discovery run at a time, whether it is the night run or "Chercher maintenant". */
class RunDiscovery(private val discovery: Discovery) {
    private val flag = AtomicBoolean(false)

    val running: Boolean get() = flag.get()

    /** Null when a run is already going on. */
    suspend operator fun invoke(): DiscoveryReport? {
        if (!flag.compareAndSet(false, true)) return null
        return try { discovery.run() } finally { flag.set(false) }
    }
}
