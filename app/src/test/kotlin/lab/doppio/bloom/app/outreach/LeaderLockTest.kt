package lab.doppio.bloom.app.outreach

import kotlinx.coroutines.test.runTest
import lab.doppio.bloom.data.db.Repository
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LeaderLockTest {
    @Test
    fun `a tick gives up leadership when the lock connection is gone and takes it back when it can`() = runTest {
        var held = true
        var reacquire = true
        val orchestrator = TestOrchestrator.build(
            Repository(), FakeSender(), TestOrchestrator.mailConfig(Mailbox("a@doppio-studio.fr", "pw", "A")),
            instanceLock = { reacquire }, lockStillHeld = { held },
        )
        orchestrator.becomeLeader()
        assertTrue(orchestrator.isLeader)
        assertTrue(orchestrator.ensureLeader(), "the lock is still held")

        held = false
        reacquire = false
        assertFalse(orchestrator.ensureLeader(), "the connection died and another instance holds the lock: no work this tick")
        assertFalse(orchestrator.isLeader)

        reacquire = true
        held = true
        assertTrue(orchestrator.ensureLeader(), "the lock was taken again")
        assertTrue(orchestrator.isLeader)
    }
}
