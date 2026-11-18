package lab.doppio.bloom.app.outreach

import lab.doppio.bloom.core.usecase.FakeInstanceLock
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LeaderGateTest {
    @Test
    fun `a tick gives up leadership when the lock is gone and takes it back when it is free`() {
        val lock = FakeInstanceLock(held = false)
        val gate = LeaderGate(lock)
        assertTrue(gate.becomeLeader())
        assertTrue(gate.isLeader)
        assertTrue(gate.ensureLeader(), "the lock is still held")

        // The connection died and another instance holds the lock: no work this tick
        lock.held = false
        lock.free = false
        assertFalse(gate.ensureLeader())
        assertFalse(gate.isLeader)

        // The other instance is gone: the lock is taken again
        lock.free = true
        assertTrue(gate.ensureLeader())
        assertTrue(gate.isLeader)
    }

    @Test
    fun `an instance that never got the lock does no work`() {
        val gate = LeaderGate(FakeInstanceLock(held = false, free = false))
        assertFalse(gate.becomeLeader())
        assertFalse(gate.ensureLeader())
        assertFalse(gate.isLeader)
    }
}
