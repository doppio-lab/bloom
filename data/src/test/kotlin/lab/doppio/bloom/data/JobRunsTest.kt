package lab.doppio.bloom.data

import kotlinx.coroutines.test.runTest
import lab.doppio.bloom.data.db.Db
import lab.doppio.bloom.data.db.Repository
import java.time.LocalDate
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JobRunsTest {
    private val repo = Repository()

    @BeforeTest
    fun clean() = TestDatabase.reset()

    @Test
    fun `une tâche ne tourne qu'une fois par période, même après un redémarrage`() = runTest {
        val monday = LocalDate.parse("2026-10-12")
        assertTrue(repo.claimJob("coach", monday))
        assertFalse(repo.claimJob("coach", monday), "second claim in the same week")
        assertFalse(repo.claimJob("coach", monday.minusWeeks(1)), "an older period never runs again")
        assertTrue(repo.claimJob("coach", monday.plusWeeks(1)))
        assertTrue(repo.claimJob("discovery", monday), "jobs are independent")
    }

    @Test
    fun `une seule instance tient le verrou des boucles`() {
        TestDatabase.reset()
        assertTrue(Db.tryHoldSingleInstanceLock())
        assertTrue(Db.tryHoldSingleInstanceLock(), "the holder keeps it")
        assertFalse(TestDatabase.otherSessionGetsInstanceLock())
    }
}
