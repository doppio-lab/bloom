package lab.doppio.bloom.data.persistence

import lab.doppio.bloom.core.port.InstanceLock

/** [InstanceLock] backed by the Postgres advisory lock held by [Db]. */
class PostgresInstanceLock : InstanceLock {
    override fun tryHold(): Boolean = Db.tryHoldSingleInstanceLock()
}
