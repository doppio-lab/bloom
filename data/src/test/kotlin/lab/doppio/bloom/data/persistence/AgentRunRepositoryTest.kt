package lab.doppio.bloom.data.persistence

import kotlinx.coroutines.test.runTest
import lab.doppio.bloom.data.TestDatabase
import lab.doppio.bloom.data.persistence.agentrun.ExposedAgentRunRepository
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class AgentRunRepositoryTest {
    private val runs = ExposedAgentRunRepository()

    @BeforeTest
    fun clean() = TestDatabase.reset()

    @Test
    fun `les tokens du jour additionnent entrée et sortie de tous les agents`() = runTest {
        val start = Instant.now().minusSeconds(5)
        assertEquals(0, runs.tokensSince(start))
        runs.logAgentRun("writer", null, "m", 1000, 200, 10, true, null)
        runs.logAgentRun("research-explore", null, "m", 3000, 500, 10, false, "boom")
        assertEquals(4700, runs.tokensSince(start))
    }

    @Test
    fun `les statistiques regroupent les exécutions par agent`() = runTest {
        runs.logAgentRun("writer", null, "m", 100, 10, 20, true, null)
        runs.logAgentRun("writer", null, "m", 100, 10, 40, false, "boom")

        val writer = runs.agentStats(Instant.now().minusSeconds(60)).single()

        assertEquals("writer", writer.agent)
        assertEquals(2, writer.runs)
        assertEquals(1, writer.failures)
        assertEquals(30, writer.avgMs)
    }
}
