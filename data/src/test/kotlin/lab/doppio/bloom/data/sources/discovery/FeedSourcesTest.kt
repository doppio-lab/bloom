package lab.doppio.bloom.data.sources.discovery

import io.ktor.http.HttpHeaders
import kotlinx.coroutines.test.runTest
import lab.doppio.bloom.core.model.FactKind
import lab.doppio.bloom.core.model.SignalType
import lab.doppio.bloom.data.sources.MockHttp
import lab.doppio.bloom.data.sources.MutableClock
import lab.doppio.bloom.data.sources.failure
import lab.doppio.bloom.data.sources.fixture
import lab.doppio.bloom.data.sources.json
import lab.doppio.bloom.data.sources.param
import lab.doppio.bloom.data.sources.xml
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Codeur and the press feeds (RSS), then GitHub (REST), against recorded responses. */
class FeedSourcesTest {
    private val now = Instant.parse("2026-10-10T20:00:00Z")
    private val clock = MutableClock(now)
    private val at = Instant.parse("2026-10-11T08:00:00Z")

    // --- Codeur ---------------------------------------------------------------------------------

    @Test
    fun `Codeur reads the plain feed and filters the projects here`() = runTest {
        val http = MockHttp { xml(fixture("codeur_feed.xml")) }
        val projects = CodeurSource(http.client).mobileProjects()
        assertEquals(listOf("https://www.codeur.com/projects.rss"), http.urls)
        assertEquals(1, projects.size)
        assertEquals("1 000 € à 10 000 €", projects.single().budget)
        assertEquals(4, CodeurSource(MockHttp { xml(fixture("codeur_feed.xml")) }.client, minBudgetEur = 0).mobileProjects().size)
    }

    @Test
    fun `Codeur fails on an HTTP error`() = runTest {
        val error = assertFailsWith<IllegalStateException> { CodeurSource(MockHttp { failure() }.client).mobileProjects() }
        assertEquals("Codeur HTTP 500", error.message)
    }

    // --- Press ----------------------------------------------------------------------------------

    private val feed = "https://feed.example/rss"
    private val brokenFeed = "https://broken.example/rss"

    @Test
    fun `Press builds a funding lead from a headline`() = runTest {
        val http = MockHttp { xml(fixture("press_feed.xml")) }
        val leads = PressSource(http.client, feeds = listOf(feed)).leads(now)
        assertEquals(listOf(feed), http.urls)

        val lead = leads.first { it.name == "VERSO" }
        assertTrue(lead.seenKey.startsWith("press:"))
        assertTrue(lead.requireUniqueMatch)
        val result = lead.build(7, at)
        val fact = result.facts.single()
        assertEquals(FactKind.PRESS, fact.kind)
        assertEquals("VERSO : levée de 5,4 millions d’euros annoncée le 09/10/2026".replace('’', '\''), fact.statement.replace('’', '\''))
        assertTrue(fact.sourceUrl.startsWith("https://www.frenchweb.fr/verso-leve"))
        assertTrue(fact.excerpt!!.startsWith("VERSO lève"))
        assertEquals(at.toString(), fact.retrievedAt)
        val signal = result.signals.single()
        assertEquals(SignalType.FUNDING, signal.type)
        assertEquals(0.8, signal.strength)
        assertEquals("2026-10-09T05:04:01Z", signal.observedAt)
        assertTrue(leads.map { it.name }.containsAll(listOf("CLEAVR", "VATES")))
    }

    @Test
    fun `Press ignores headlines older than the maximum age`() = runTest {
        val old = PressSource(MockHttp { xml(fixture("press_feed.xml")) }.client, feeds = listOf(feed), maxAgeDays = 14)
        assertTrue(old.leads(Instant.parse("2026-11-20T00:00:00Z")).isEmpty())
    }

    @Test
    fun `one feed down does not hide the others`() = runTest {
        val http = MockHttp { request -> if (request.url.host == "broken.example") failure() else xml(fixture("press_feed.xml")) }
        val leads = PressSource(http.client, feeds = listOf(brokenFeed, feed)).leads(now)
        assertTrue(leads.any { it.name == "VERSO" })
    }

    @Test
    fun `Press with no feed configured reads nothing and does not fail`() = runTest {
        val http = MockHttp { failure() }
        assertEquals(emptyList(), PressSource(http.client, feeds = emptyList()).leads(now))
        assertEquals(emptyList(), http.urls)
    }

    @Test
    fun `Press fails only when every feed fails`() = runTest {
        val error = assertFailsWith<IllegalStateException> { PressSource(MockHttp { failure() }.client, feeds = listOf(feed, brokenFeed)).leads(now) }
        assertEquals("HTTP 500 sur $feed", error.message)
    }

    // --- GitHub ---------------------------------------------------------------------------------

    private class Seen {
        val marked = mutableListOf<Triple<String, Boolean, String>>()
        val known = mutableSetOf<String>()
    }

    private fun github(http: MockHttp, seen: Seen = Seen(), token: String? = null, maxLookups: Int = 25) =
        GitHubSource(
            http.client, token,
            wasSeen = { it in seen.known },
            markSeen = { key, kept, reason -> seen.marked += Triple(key, kept, reason) },
            maxLookups = maxLookups,
            clock = clock,
        )

    private fun githubApi(org: String = fixture("github_org.json")) = MockHttp { request ->
        if (request.url.encodedPath.startsWith("/search/repositories")) json(fixture("github_repos.json")) else json(org)
    }

    @Test
    fun `GitHub searches the two KMP topics pushed in the last 60 days, then reads each organisation`() = runTest {
        val http = githubApi()
        github(http, token = "ghp_token").leads(now)
        assertEquals(
            listOf("/search/repositories", "/search/repositories", "/orgs/xemantic"),
            http.requests.map { it.url.encodedPath },
        )
        val topics = http.requests.take(2)
        assertEquals(listOf("topic:kotlin-multiplatform pushed:>2026-08-11", "topic:compose-multiplatform pushed:>2026-08-11"), topics.map { it.param("q") })
        assertEquals(listOf("updated", "100"), listOf("sort", "per_page").map { topics.first().param(it) })
        assertEquals("api.github.com", http.requests.first().url.host)
        assertTrue(http.requests.all { it.headers[HttpHeaders.Authorization] == "Bearer ghp_token" })
        assertTrue(http.requests.all { it.headers[HttpHeaders.Accept] == "application/vnd.github+json" })
    }

    @Test
    fun `GitHub never sends an Authorization header without a token`() = runTest {
        val http = githubApi()
        github(http).leads(now)
        assertTrue(http.requests.all { HttpHeaders.Authorization !in it.headers.names() })
    }

    @Test
    fun `GitHub maps a French organisation to a lead with its repository as proof`() = runTest {
        val lead = github(githubApi()).leads(now).single()
        assertEquals("insert-koin.io", lead.name)
        assertEquals("github:xemantic", lead.seenKey)
        assertEquals("insert-koin.io", lead.domain)
        assertTrue(lead.requireUniqueMatch)

        val result = lead.build(7, at)
        val fact = result.facts.single()
        assertEquals(FactKind.CODE_REPOSITORY, fact.kind)
        assertEquals("insert-koin.io : dépôt public Kotlin Multiplatform mis à jour ces 60 derniers jours", fact.statement)
        assertEquals("https://github.com/xemantic/xemantic-typescript-compiler", fact.sourceUrl)
        assertEquals("https://github.com/InsertKoinIO", fact.excerpt)
        assertEquals(SignalType.KMP_ADOPTION, result.signals.single().type)
        assertEquals(at.toString(), result.signals.single().observedAt)
    }

    @Test
    fun `GitHub remembers organisations outside France or without a website`() = runTest {
        val seen = Seen()
        val abroad = """{"login":"xemantic","name":"Xemantic","blog":"https://xemantic.com","location":"Berlin","html_url":"https://github.com/xemantic","type":"Organization"}"""
        assertEquals(emptyList(), github(githubApi(abroad), seen).leads(now))
        assertEquals(Triple("github:xemantic", false, "organisation hors France ou sans site"), seen.marked.single())
    }

    @Test
    fun `GitHub skips organisations already seen and respects the lookup budget`() = runTest {
        val seen = Seen().apply { known += "github:xemantic" }
        val http = githubApi()
        assertEquals(emptyList(), github(http, seen).leads(now))
        assertTrue(http.requests.none { it.url.encodedPath.startsWith("/orgs/") })

        val noBudget = githubApi()
        assertEquals(emptyList(), github(noBudget, maxLookups = 0).leads(now))
        assertTrue(noBudget.requests.none { it.url.encodedPath.startsWith("/orgs/") })
    }

    @Test
    fun `GitHub fails on an HTTP error`() = runTest {
        val error = assertFailsWith<IllegalStateException> { github(MockHttp { failure(io.ktor.http.HttpStatusCode.Forbidden) }).leads(now) }
        assertEquals("GitHub HTTP 403 sur /search/repositories", error.message)
    }
}
