package lab.doppio.bloom.core.usecase

import kotlinx.coroutines.test.runTest
import lab.doppio.bloom.core.model.SendingPolicy
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RunPeriodicJobsTest {
    private val jobs = FakeJobRunRepository()
    private val discovery = FakeDiscovery()
    private val events = FakeEventRepository()
    private val clock = clockAt(WEDNESDAY_MORNING)
    private val research = FakeResearcher()
    private val run = RunPeriodicJobs(
        jobs, discovery, RunDiscovery(discovery),
        ResearchBatch(FakeAccountRepository(), FakeFactRepository(), research, clock),
        FakeCoach(), events, FakeContactRepository(), FakeOfficerRepository(), FakeDiscoveryStateRepository(),
        SendingPolicy(), clock,
    )

    private fun at(date: String, hour: Int) = ZonedDateTime.of(LocalDate.parse(date), java.time.LocalTime.of(hour, 0), ZoneId.of("Europe/Paris"))
    private val names get() = jobs.claimed.map { it.first }.toSet()

    @Test
    fun `discovery runs only between 2h and 4h, followed by the research claim`() = runTest {
        run(at("2026-11-04", 1)) // Wednesday
        assertEquals(0, discovery.runs)
        run(at("2026-11-04", 3))
        assertEquals(1, discovery.runs)
        assertTrue("discovery" in names && "research" in names)
        assertTrue(jobs.finished.containsAll(listOf("discovery" to null, "research" to null)))
    }

    @Test
    fun `from 5h the research runs on its own once a day`() = runTest {
        run(at("2026-11-04", 6))
        assertEquals(0, discovery.runs)
        assertEquals(setOf("research"), names - setOf("coach", "gdpr-purge"))
        run(at("2026-11-04", 7))
        assertEquals(1, jobs.claimed.count { it.first == "research" })
    }

    @Test
    fun `the Codeur feed is polled at every tick`() = runTest {
        run(at("2026-11-04", 12))
        run(at("2026-11-04", 12))
        assertEquals(2, discovery.polls)
    }

    @Test
    fun `the coach memo starts Monday at 7h`() = runTest {
        run(at("2026-11-02", 6))
        assertTrue("coach" !in names)
        run(at("2026-11-02", 7))
        assertTrue("coach" in names)
    }

    @Test
    fun `the coach memo is logged with the text, once per week`() = runTest {
        run(at("2026-11-02", 8)) // Monday 8h
        run(at("2026-11-04", 8)) // Wednesday, same week
        assertEquals(1, events.types.count { it == "WEEKLY_MEMO" })
        assertEquals("\"note du lundi\"", events.events.first { it.first == "WEEKLY_MEMO" }.second)
    }

    @Test
    fun `the GDPR purge starts on the 1st at 4h and runs once for the month`() = runTest {
        run(at("2026-12-01", 3)) // Tuesday the 1st, before 4h
        assertTrue("gdpr-purge" !in names)
        run(at("2026-12-01", 4))
        run(at("2026-12-15", 10))
        assertEquals(1, events.types.count { it == "GDPR_PURGE" })
        assertEquals("""{"contacts":2,"officers":1,"demands":3}""", events.events.first { it.first == "GDPR_PURGE" }.second)
    }

    @Test
    fun `a job already claimed is not run again`() = runTest {
        jobs.claimed += "discovery" to LocalDate.parse("2026-11-04")
        run(at("2026-11-04", 3))
        assertEquals(0, discovery.runs)
    }

    @Test
    fun `a failing job is finished with its error and does not stop the others`() = runTest {
        val failing = object : lab.doppio.bloom.core.port.Coach { override suspend fun weeklyMemo(): String = error("coach down") }
        val r = RunPeriodicJobs(
            jobs, discovery, RunDiscovery(discovery), ResearchBatch(FakeAccountRepository(), FakeFactRepository(), research, clock),
            failing, events, FakeContactRepository(), FakeOfficerRepository(), FakeDiscoveryStateRepository(), SendingPolicy(), clock,
        )
        r(at("2026-11-02", 8))
        assertEquals("coach down", jobs.finished.first { it.first == "coach" }.second)
        assertTrue(jobs.finished.any { it.first == "gdpr-purge" && it.second == null })
    }
}
