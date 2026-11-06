package lab.doppio.bloom.agents

import kotlinx.coroutines.test.runTest
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RunTrackerTest {
    private val repo = FakeAgentRunRepository()

    private fun tracker(budget: Long) = RunTracker(repo, dailyTokenBudget = budget, zone = ZoneId.of("Europe/Paris"), clock = fixedClock)

    @Test
    fun `a run is refused once the daily budget is spent and nothing is logged`() = runTest {
        repo.tokensToday = 1_000
        var ran = false
        val error = assertFailsWith<TokenBudgetExceeded> {
            tracker(1_000).track("writer", 1, testModel) { ran = true; "x" to null }
        }
        assertTrue("writer" in error.message.orEmpty())
        assertTrue(!ran)
        assertTrue(repo.runs.isEmpty())
    }

    @Test
    fun `a run under the budget goes through and the day starts at local midnight`() = runTest {
        repo.tokensToday = 999
        val value = tracker(1_000).track("writer", 1, testModel) { "ok" to null }
        assertEquals("ok", value)
        // 2026-11-06 00:00 in Paris (UTC+1)
        assertEquals(Instant.parse("2026-11-05T23:00:00Z"), repo.lastTokensSince)
        assertEquals(1, repo.runs.size)
        assertTrue(repo.runs.single().success)
    }

    @Test
    fun `a budget of zero means no limit`() = runTest {
        repo.tokensToday = 10_000_000
        tracker(0).track("writer", null, testModel) { "ok" to null }
        assertEquals(null, repo.lastTokensSince)
        assertEquals(1, repo.runs.size)
    }

    @Test
    fun `tool-using runs are refused by the same budget`() = runTest {
        repo.tokensToday = 5
        assertFailsWith<TokenBudgetExceeded> {
            tracker(5).trackAgent("weekly-coach", null, testModel, TokenCounter()) { "memo" }
        }
        assertTrue(repo.runs.isEmpty())
    }

    @Test
    fun `a failing run is logged with its error and rethrown`() = runTest {
        val error = assertFailsWith<IllegalStateException> {
            tracker(0).track<String>("reply-classifier", 2, testModel) { error("JSON invalide") }
        }
        assertEquals("JSON invalide", error.message)
        val run = repo.runs.single()
        assertEquals(false, run.success)
        assertEquals("JSON invalide", run.error)
        assertEquals("reply-classifier", run.agent)
    }
}
