package lab.doppio.bloom.data.persistence

import kotlinx.coroutines.test.runTest
import lab.doppio.bloom.data.TestDatabase
import lab.doppio.bloom.data.persistence.job.ExposedJobRunRepository
import java.time.LocalDate
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JobRunRepositoryTest {
    private val jobs = ExposedJobRunRepository()

    @BeforeTest
    fun clean() = TestDatabase.reset()

    @Test
    fun `une tâche ne tourne qu'une fois par période, même après un redémarrage`() = runTest {
        val monday = LocalDate.parse("2026-10-12")
        assertTrue(jobs.claimJob("coach", monday))
        assertFalse(jobs.claimJob("coach", monday), "second claim in the same week")
        assertFalse(jobs.claimJob("coach", monday.minusWeeks(1)), "an older period never runs again")
        assertTrue(jobs.claimJob("coach", monday.plusWeeks(1)))
        assertTrue(jobs.claimJob("discovery", monday), "jobs are independent")
    }

    @Test
    fun `une seule instance tient le verrou des boucles`() {
        TestDatabase.reset()
        assertTrue(Db.tryHoldSingleInstanceLock())
        assertTrue(Db.tryHoldSingleInstanceLock(), "the holder keeps it")
        assertFalse(TestDatabase.otherSessionGetsInstanceLock())
    }
}
