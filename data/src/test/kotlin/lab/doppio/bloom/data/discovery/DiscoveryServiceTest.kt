package lab.doppio.bloom.data.discovery

import kotlinx.coroutines.test.runTest
import lab.doppio.bloom.core.model.Account
import lab.doppio.bloom.core.model.AccountId
import lab.doppio.bloom.core.model.Fact
import lab.doppio.bloom.core.model.FactKind
import lab.doppio.bloom.core.model.Sector
import lab.doppio.bloom.core.model.Signal
import lab.doppio.bloom.core.model.SignalType
import lab.doppio.bloom.core.model.SourceResult
import lab.doppio.bloom.core.model.fact
import lab.doppio.bloom.core.port.AccountRepository
import lab.doppio.bloom.core.port.DiscoveryStateRepository
import lab.doppio.bloom.core.port.FactRepository
import lab.doppio.bloom.core.scoring.ScoreBreakdown
import lab.doppio.bloom.core.usecase.FakeAccountRepository
import lab.doppio.bloom.core.usecase.FakeDiscoveryStateRepository
import lab.doppio.bloom.core.usecase.FakeEventRepository
import lab.doppio.bloom.core.usecase.FakeFactRepository
import lab.doppio.bloom.core.usecase.FakeOfficerRepository
import lab.doppio.bloom.data.sources.MockHttp
import lab.doppio.bloom.data.sources.MutableClock
import lab.doppio.bloom.data.sources.discovery.CodeurSource
import lab.doppio.bloom.data.sources.facts.AppStoreSource
import lab.doppio.bloom.data.sources.facts.CompanyRegistrySource
import lab.doppio.bloom.data.sources.facts.FranceTravailSource
import lab.doppio.bloom.data.sources.fixture
import lab.doppio.bloom.data.sources.json
import lab.doppio.bloom.data.sources.xml
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The coordinator on a MockEngine (registry, App Store, France Travail, Codeur) and in-memory ports. */
class DiscoveryServiceTest {
    private val now = Instant.parse("2026-10-10T20:00:00Z")
    private val clock = MutableClock(now)

    private class Accounts : AccountRepository by FakeAccountRepository() {
        val merged = mutableListOf<Account>()
        val scores = mutableListOf<ScoreBreakdown>()
        override suspend fun mergeDiscovered(a: Account): Pair<AccountId, Boolean> { merged += a; return 42L to true }
        override suspend fun account(id: AccountId): Account? = merged.lastOrNull()?.copy(id = id)
        override suspend fun saveScore(s: ScoreBreakdown) { scores += s }
    }

    private class Facts : FactRepository by FakeFactRepository() {
        val facts = mutableListOf<Fact>()
        val signals = mutableListOf<Signal>()
        override suspend fun saveFacts(facts: List<Fact>) { this.facts += facts }
        override suspend fun saveSignals(signals: List<Signal>) { this.signals += signals }
        override suspend fun signalsOf(accountId: AccountId, maxAgeDays: Long): List<Signal> = signals.filter { it.accountId == accountId }
    }

    private class State : DiscoveryStateRepository by FakeDiscoveryStateRepository() {
        val marked = mutableListOf<Triple<String, Boolean, String?>>()
        override suspend fun markSeen(key: String, kept: Boolean, reason: String?) { marked += Triple(key, kept, reason) }
    }

    private class StubSource(val leads: List<Lead> = emptyList(), val failure: Exception? = null) : DiscoverySource {
        override val key = "stub"
        override val label = "Source de test"
        override suspend fun leads(now: Instant): List<Lead> = failure?.let { throw it } ?: leads
    }

    private val accounts = Accounts()
    private val facts = Facts()
    private val state = State()
    private val events = FakeEventRepository()

    /** The registry knows Swile only; the App Store search finds nothing; France Travail returns one iOS offer from Swile. */
    private val http = MockHttp { request ->
        val url = request.url
        when {
            url.host == "recherche-entreprises.api.gouv.fr" ->
                if (request.url.parameters["q"]?.contains("swile", ignoreCase = true) == true || request.url.parameters["q"] == "824012173") json(fixture("registry.json")) else json("""{"results":[]}""")
            url.host == "itunes.apple.com" -> json("""{"results":[]}""")
            url.encodedPath.endsWith("access_token") -> json("""{"access_token":"tok","expires_in":1500}""")
            url.host == "api.francetravail.io" -> json("""{"resultats":[{"id":"FT1","intitule":"Développeur iOS","description":"Swift","dateCreation":"2026-10-01T09:00:00+02:00","entreprise":{"nom":"Swile","url":"https://www.swile.co"}}]}""")
            url.host == "www.codeur.com" -> xml(fixture("codeur_feed.xml"))
            else -> error("unexpected request $url")
        }
    }

    private fun service(
        sources: List<DiscoverySource> = emptyList(),
        config: DiscoveryConfig = DiscoveryConfig(chartGenres = emptyList()),
        jobs: Boolean = false,
        codeur: Boolean = false,
    ) = DiscoveryService(
        accountRepo = accounts, discoveryState = state, eventRepo = events, factRepo = facts, officerRepo = FakeOfficerRepository(),
        appStore = AppStoreSource(http.client, clock = clock), registry = CompanyRegistrySource(http.client, clock),
        jobs = if (jobs) FranceTravailSource(http.client, "id", "secret", clock) else null,
        config = config, sources = sources,
        codeur = if (codeur) CodeurSource(http.client, minBudgetEur = 0) else null,
        clock = clock,
    )

    private fun swileLead(name: String = "Swile", requireRegistry: Boolean = true) = Lead(
        name = name,
        seenKey = "stub:1",
        requireRegistry = requireRegistry,
        build = { accountId, at ->
            val f = fact(accountId, FactKind.PRESS, "$name : levée de fonds", "https://press.example/1", at = at)
            SourceResult(listOf(f), listOf(Signal(accountId, SignalType.FUNDING, 0.8, at.toString(), listOf(f.id))))
        },
    )

    @Test
    fun `a lead of an added source becomes an account with its registry facts and a score`() = runTest {
        val report = service(sources = listOf(StubSource(listOf(swileLead())))).run()

        assertEquals(1, report.newAccounts)
        assertEquals(mapOf("Source de test" to 1), report.bySource.filterKeys { it == "Source de test" })
        val account = accounts.merged.single()
        assertEquals("824012173", account.siren)
        assertEquals(500, account.headcount)
        assertEquals(Sector.BANK_INSURANCE, account.sector)
        assertEquals("Source de test", account.source)
        // The lead's own fact, then the three registry facts of a new account
        assertEquals(1, facts.facts.count { it.kind == FactKind.PRESS })
        assertEquals(3, facts.facts.count { it.kind == FactKind.COMPANY_REGISTRY })
        assertTrue(facts.facts.all { it.retrievedAt == now.toString() })
        assertEquals(SignalType.FUNDING, facts.signals.single().type)
        assertEquals(1, accounts.scores.size)
        assertEquals(Triple("stub:1", true, "stub"), state.marked.single())
        assertTrue("ACCOUNT_DISCOVERED" in events.types && "DISCOVERY_RUN" in events.types)
    }

    @Test
    fun `an excluded name is remembered and counted, no account is created`() = runTest {
        val report = service(
            sources = listOf(StubSource(listOf(swileLead()))),
            config = DiscoveryConfig(chartGenres = emptyList(), extraExclusions = setOf("Swile")),
        ).run()
        assertEquals(1, report.excluded)
        assertEquals(0, report.newAccounts)
        assertTrue(accounts.merged.isEmpty())
        assertEquals(false, state.marked.single().second)
    }

    @Test
    fun `a company missing from the registry is skipped when the lead requires it`() = runTest {
        val report = service(sources = listOf(StubSource(listOf(swileLead(name = "Inconnue SA"))))).run()
        assertEquals(0, report.newAccounts + report.excluded)
        assertTrue(accounts.merged.isEmpty())
        assertEquals(Triple("stub:1", false, "non trouvé dans l'annuaire français"), state.marked.single())
    }

    @Test
    fun `a failing source leaves a note and the run goes on`() = runTest {
        val report = service(sources = listOf(StubSource(failure = IllegalStateException("HTTP 500")), StubSource(listOf(swileLead())))).run()
        assertTrue("Source de test : HTTP 500" in report.notes)
        assertEquals(1, report.newAccounts)
    }

    @Test
    fun `job offers from France Travail create the hiring company and mark the offer seen`() = runTest {
        val report = service(jobs = true, config = DiscoveryConfig(chartGenres = emptyList(), jobKeywords = listOf("iOS"))).run()
        assertEquals(1, report.newAccounts)
        assertEquals(1, report.offersRead)
        val account = accounts.merged.single()
        assertEquals("Swile", account.name)
        assertEquals("swile.co", account.domain)
        assertEquals("offres d'emploi", account.source)
        assertTrue(facts.signals.any { it.type == SignalType.HIRING_MOBILE })
        assertEquals(Triple("ft:FT1", true, "Created"), state.marked.single())
    }

    @Test
    fun `without France Travail credentials the run says so`() = runTest {
        val report = service().run()
        assertTrue("offres d'emploi désactivées : FT_CLIENT_ID absent" in report.notes)
    }

    @Test
    fun `pollDemands counts the new Codeur projects, and nothing without the source`() = runTest {
        assertEquals(4, service(codeur = true).pollDemands())
        assertEquals(0, service(codeur = false).pollDemands())
    }
}
