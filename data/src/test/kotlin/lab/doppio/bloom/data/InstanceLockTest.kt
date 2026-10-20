package lab.doppio.bloom.data

import lab.doppio.bloom.data.db.Db
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InstanceLockTest {
    @AfterTest
    fun release() = Db.releaseSingleInstanceLock()

    @Test
    fun `le verrou est tenu tant que sa connexion vit`() {
        TestDatabase.reset()
        assertTrue(Db.tryHoldSingleInstanceLock())
        assertTrue(Db.lockHeld())
        assertFalse(TestDatabase.otherSessionGetsInstanceLock(), "another instance cannot take it")

        TestDatabase.killInstanceLockHolder()

        assertFalse(Db.lockHeld(), "a dead connection no longer holds the lock")
        assertTrue(TestDatabase.otherSessionGetsInstanceLock(), "the lock is free again")
    }

    @Test
    fun `relâcher le verrou le rend aux autres instances`() {
        TestDatabase.reset()
        assertTrue(Db.tryHoldSingleInstanceLock())
        Db.releaseSingleInstanceLock()
        assertFalse(Db.lockHeld())
        assertTrue(TestDatabase.otherSessionGetsInstanceLock())
        assertTrue(Db.tryHoldSingleInstanceLock(), "and can be taken back")
    }
}
