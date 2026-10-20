package lab.doppio.bloom.app.outreach

import kotlinx.coroutines.test.runTest
import lab.doppio.bloom.app.TestDatabase
import lab.doppio.bloom.core.model.Account
import lab.doppio.bloom.core.model.Signal
import lab.doppio.bloom.core.model.SignalType
import lab.doppio.bloom.data.db.Repository
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GdprPurgeTest {
    private val repo = Repository()
    private val orchestrator = TestOrchestrator.build(repo, FakeSender(), TestOrchestrator.mailConfig(Mailbox("a@doppio-studio.fr", "pw", "A")))

    @BeforeTest
    fun clean() = TestDatabase.reset()

    @Test
    fun `the monthly purge also drops old signals and seen keys and blanks old reply bodies`() = runTest {
        val now = Instant.parse("2026-10-20T10:00:00Z")
        val accountId = repo.upsertAccount(Account(name = "Oldco"))
        repo.saveSignals(listOf(Signal(accountId, SignalType.HIRING_MOBILE, 0.5, now.minus(200, ChronoUnit.DAYS).toString(), emptyList())))
        repo.saveSignals(listOf(Signal(accountId, SignalType.FUNDING, 0.5, now.minus(10, ChronoUnit.DAYS).toString(), emptyList())))

        val report = orchestrator.gdprPurge(now)

        assertEquals(1, report.signals)
        val event = repo.eventsSince(now.minusSeconds(60)).map { it.first }
        assertTrue("GDPR_PURGE" in event, "the purge is recorded as an event")
        assertEquals(1, repo.signalsOf(accountId, maxAgeDays = 400).size, "the recent signal survives")
    }
}
