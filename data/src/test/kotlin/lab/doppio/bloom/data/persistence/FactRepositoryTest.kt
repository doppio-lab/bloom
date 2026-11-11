package lab.doppio.bloom.data.persistence

import kotlinx.coroutines.test.runTest
import lab.doppio.bloom.core.model.Account
import lab.doppio.bloom.core.model.Fact
import lab.doppio.bloom.core.model.FactKind
import lab.doppio.bloom.core.model.Signal
import lab.doppio.bloom.core.model.SignalType
import lab.doppio.bloom.data.TestDatabase
import lab.doppio.bloom.data.persistence.account.ExposedAccountRepository
import lab.doppio.bloom.data.persistence.fact.ExposedFactRepository
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class FactRepositoryTest {
    private val now = Instant.parse("2026-11-10T08:00:00Z")
    private val facts = ExposedFactRepository(Clock.fixed(now, ZoneOffset.UTC))
    private val accounts = ExposedAccountRepository()

    @BeforeTest
    fun clean() = TestDatabase.reset()

    private fun fact(id: String, at: String) = Fact(id, 1, FactKind.COMPANY_REGISTRY, "Acme : code NAF 62.01Z", "https://registry/acme", at)

    @Test
    fun `reading a known fact again refreshes its date so it does not age out`() = runTest {
        val account = accounts.upsertAccount(Account(name = "Acme"))
        facts.saveFacts(listOf(fact("f1", "2026-05-01T00:00:00Z").copy(accountId = account)))
        // 193 days old: outside the 120 day window
        assertEquals(0, facts.factsOf(account).size)

        facts.saveFacts(listOf(fact("f1", "2026-11-09T00:00:00Z").copy(accountId = account)))

        assertEquals("2026-11-09T00:00:00Z", facts.factsOf(account).single().retrievedAt)
    }

    @Test
    fun `an older reading never moves the date backwards`() = runTest {
        val account = accounts.upsertAccount(Account(name = "Acme"))
        facts.saveFacts(listOf(fact("f1", "2026-11-09T00:00:00Z").copy(accountId = account)))
        facts.saveFacts(listOf(fact("f1", "2026-11-01T00:00:00Z").copy(accountId = account)))
        assertEquals("2026-11-09T00:00:00Z", facts.factsOf(account).single().retrievedAt)
    }

    @Test
    fun `a state signal read again replaces the previous reading, an event signal accumulates`() = runTest {
        val account = accounts.upsertAccount(Account(name = "Acme"))
        fun s(type: SignalType, at: String) = Signal(account, type, 0.7, at, listOf("f"))

        facts.saveSignals(listOf(s(SignalType.HIRING_MOBILE, "2026-11-01T00:00:00Z"), s(SignalType.PUBLIC_TENDER, "2026-11-01T00:00:00Z")))
        facts.saveSignals(listOf(s(SignalType.HIRING_MOBILE, "2026-11-09T00:00:00Z"), s(SignalType.PUBLIC_TENDER, "2026-11-09T00:00:00Z")))

        val stored = facts.signalsOf(account)
        assertEquals(listOf("2026-11-09T00:00:00Z"), stored.filter { it.type == SignalType.HIRING_MOBILE }.map { it.observedAt })
        assertEquals(2, stored.count { it.type == SignalType.PUBLIC_TENDER })
    }
}
