package lab.doppio.bloom.agents

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RunTrackerTest {
    private val model = TestSupport.model

    @Test
    fun `a failed run still records the tokens the model consumed`() = runTest {
        val log = InMemoryRunLog()
        val tracker = RunTracker(log, dailyTokenBudget = 100)
        val failure = assertFailsWith<IllegalStateException> {
            tracker.track<Unit>("writer", 1L, model) {
                // What the metered executor reports when the model answered but the answer could not be parsed
                TokenCounter.current()?.add(80, 40)
                error("réponse illisible")
            }
        }
        assertEquals("réponse illisible", failure.message)
        val run = log.runs.single()
        assertFalse(run.success)
        assertEquals("réponse illisible", run.error)
        assertEquals(80, run.inTok)
        assertEquals(40, run.outTok)
    }

    @Test
    fun `the daily budget counts failed runs and refuses the next one`() = runTest {
        val log = InMemoryRunLog()
        val tracker = RunTracker(log, dailyTokenBudget = 100)
        runCatching {
            tracker.track<Unit>("writer", 1L, model) {
                TokenCounter.current()?.add(80, 40)
                error("réponse illisible")
            }
        }
        assertFailsWith<TokenBudgetExceeded> { tracker.track("writer", 1L, model) { Unit to null } }
        assertEquals(1, log.runs.size, "a refused run is not logged")
    }

    @Test
    fun `a successful run logs every call of the run`() = runTest {
        val log = InMemoryRunLog()
        val tracker = RunTracker(log)
        val value = tracker.track("reply-classifier", null, model) {
            TokenCounter.current()?.add(10, 5)
            TokenCounter.current()?.add(7, 3)
            "ok" to null
        }
        assertEquals("ok", value)
        val run = log.runs.single()
        assertTrue(run.success)
        assertEquals(17, run.inTok)
        assertEquals(8, run.outTok)
    }
}
