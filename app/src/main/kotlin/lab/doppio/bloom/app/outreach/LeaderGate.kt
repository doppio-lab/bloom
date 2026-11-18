package lab.doppio.bloom.app.outreach

import lab.doppio.bloom.core.port.InstanceLock
import org.slf4j.LoggerFactory

/**
 * Whether this instance may run the background loops. Only the holder of the [InstanceLock] works, or every email
 * would leave twice. The lock lives with its database connection, so each loop tick calls [ensureLeader]: when the
 * lock is gone (Postgres restart, dropped idle connection) the work is skipped and the lock is taken back when free.
 */
class LeaderGate(private val instanceLock: InstanceLock) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** True once this instance holds the loops' lock. */
    @Volatile var isLeader = false
        private set

    /** Takes the lock when it is free. */
    fun becomeLeader(): Boolean {
        isLeader = runCatching { instanceLock.tryHold() }.getOrDefault(false)
        return isLeader
    }

    /** Checks the lock is still held before any work, and tries to take it back when it is not. */
    fun ensureLeader(): Boolean {
        if (runCatching { instanceLock.isHeld() }.getOrDefault(false)) {
            isLeader = true
            return true
        }
        if (isLeader) log.error("Le verrou des boucles de fond a été perdu (connexion à la base coupée ?) : boucles suspendues")
        isLeader = false
        if (becomeLeader()) log.warn("Verrou des boucles de fond repris")
        return isLeader
    }
}
