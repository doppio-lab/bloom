package lab.doppio.bloom.data

import kotlinx.coroutines.test.runTest
import lab.doppio.bloom.core.model.Account
import lab.doppio.bloom.core.model.FactKind
import lab.doppio.bloom.core.model.Signal
import lab.doppio.bloom.core.model.SignalType
import lab.doppio.bloom.data.db.DiscoverySeen
import lab.doppio.bloom.data.db.Repository
import lab.doppio.bloom.data.db.Signals
import lab.doppio.bloom.data.db.dbQuery
import lab.doppio.bloom.data.sources.fact
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class FactsAndSignalsTest {
    private val repo = Repository()

    @BeforeTest
    fun clean() = TestDatabase.reset()

    @Test
    fun `un fait déjà enregistré n'est pas écrasé`() = runTest {
        val accountId = repo.upsertAccount(Account(name = "Velomoov"))
        val f = fact(accountId, FactKind.WEB_PAGE, "Page lue : Velomoov", "https://velomoov.fr", excerpt = "first")
        repo.saveFacts(listOf(f))
        repo.saveFacts(listOf(f.copy(excerpt = "second")))
        val saved = repo.factsOf(accountId).single()
        assertEquals(40, saved.id.length)
        assertEquals("first", saved.excerpt)
    }

    @Test
    fun `un signal identique observé dans les 24 heures n'est pas réenregistré`() = runTest {
        val accountId = repo.upsertAccount(Account(name = "Velomoov"))
        val t0 = Instant.now().minus(2, ChronoUnit.DAYS)
        val s = Signal(accountId, SignalType.HIRING_MOBILE, 0.7, t0.toString(), listOf("abc"))

        repo.saveSignals(listOf(s))
        repo.saveSignals(listOf(s.copy(observedAt = t0.plus(3, ChronoUnit.HOURS).toString())))
        assertEquals(1, repo.signalsOf(accountId).size, "same account, type and facts within 24 h")

        repo.saveSignals(listOf(s.copy(observedAt = t0.plus(25, ChronoUnit.HOURS).toString())))
        assertEquals(2, repo.signalsOf(accountId).size, "a day later it is a new observation")

        repo.saveSignals(listOf(s.copy(factIds = listOf("def"))))
        repo.saveSignals(listOf(s.copy(type = SignalType.HIRING_KMP)))
        assertEquals(4, repo.signalsOf(accountId).size, "other facts or another type are other signals")
    }

    @Test
    fun `les signaux de plus de 180 jours sont purgés`() = runTest {
        val accountId = repo.upsertAccount(Account(name = "Velomoov"))
        val now = Instant.now()
        repo.saveSignals(listOf(
            Signal(accountId, SignalType.HIRING_MOBILE, 0.7, now.minus(200, ChronoUnit.DAYS).toString(), listOf("old")),
            Signal(accountId, SignalType.HIRING_MOBILE, 0.7, now.minus(10, ChronoUnit.DAYS).toString(), listOf("new")),
        ))

        assertEquals(1, repo.purgeOldSignals(now = now))

        assertEquals(listOf("new"), dbQuery { Signals.selectAll().map { it[Signals.factIds] } }.map { it.trim('[', ']', '"') })
    }

    @Test
    fun `les clés de découverte plus vieilles que toute fenêtre de relecture sont purgées`() = runTest {
        val now = Instant.now()
        repo.markSeen("ft:old", true)
        repo.markSeen("ft:recent", true)
        dbQuery { DiscoverySeen.update({ DiscoverySeen.key eq "ft:old" }) { it[checkedAt] = now.minus(400, ChronoUnit.DAYS) } }

        assertEquals(1, repo.purgeOldDiscoverySeen(now = now))

        assertEquals(listOf("ft:recent"), dbQuery { DiscoverySeen.selectAll().map { it[DiscoverySeen.key] } })
    }
}
