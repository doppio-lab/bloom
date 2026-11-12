package lab.doppio.bloom.data.sources.facts

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import lab.doppio.bloom.core.model.FactKind
import lab.doppio.bloom.core.model.SignalType
import lab.doppio.bloom.data.sources.MockHttp
import lab.doppio.bloom.data.sources.failure
import lab.doppio.bloom.data.sources.json
import lab.doppio.bloom.data.sources.param
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppStoreSourceTest {
    private val now = Instant.parse("2026-11-06T10:00:00Z")

    private fun lookup(results: String = """[{
        "trackName":"Mon App","trackViewUrl":"https://apps.apple.com/fr/app/mon-app/id123","averageUserRating":4.6,"userRatingCount":1200,
        "version":"3.2","currentVersionReleaseDate":"2026-01-01T08:00:00Z","sellerName":"Mon App SAS","sellerUrl":"https://monapp.fr","primaryGenreId":6015}]""") =
        """{"resultCount":1,"results":$results}"""

    private fun review(rating: Int, content: String, version: String = "3.2", updated: String = "2026-10-20T10:00:00-07:00") =
        """{"im:rating":{"label":"$rating"},"title":{"label":"Avis $rating"},"content":{"label":"$content"},"im:version":{"label":"$version"},"updated":{"label":"$updated"}}"""

    /** 6 one-star reviews that complain about crashes and 4 five-star ones: 10 reviews in the last 90 days. */
    private val firstPage = """{"feed":{"entry":[${(List(6) { review(1, "l'app plante au démarrage") } + List(4) { review(5, "super") }).joinToString(",")}]}}"""

    private fun source(http: MockHttp) = AppStoreSource(http.client)

    private fun serving() = MockHttp { request ->
        val url = request.url.toString()
        when {
            "/lookup" in url -> json(lookup())
            "page=1/" in url -> json(firstPage)
            else -> json("""{"feed":{}}""")
        }
    }

    @Test
    fun `analyze reads the lookup then two pages of reviews`() = runTest {
        val http = serving()
        source(http).analyze(7, 123, now)
        assertEquals(
            listOf(
                "https://itunes.apple.com/lookup?id=123&country=fr",
                "https://itunes.apple.com/fr/rss/customerreviews/page=1/id=123/sortBy=mostRecent/json",
                "https://itunes.apple.com/fr/rss/customerreviews/page=2/id=123/sortBy=mostRecent/json",
            ),
            http.urls,
        )
    }

    @Test
    fun `analyze turns the metadata and the reviews into facts and signals`() = runTest {
        val result = source(serving()).analyze(7, 123, now)

        assertEquals("Mon App", result.app?.name)
        assertEquals("Mon App SAS", result.app?.sellerName)
        assertEquals(6015, result.app?.genreId)
        val statements = result.facts.map { it.statement }
        assertTrue("Mon App : note moyenne App Store 4,6 sur 1200 notes (fr)" in statements)
        assertTrue("Mon App : dernière version 3.2 publiée il y a 309 jours" in statements)
        assertTrue(statements.any { it.startsWith("Mon App : moyenne des 10 avis des 90 derniers jours 2,6, contre 4,6") })
        assertTrue(statements.any { it.contains("6 avis sur 10") && it.contains("(version la plus citée : 3.2)") })
        assertTrue(result.facts.all { it.accountId == 7L && it.retrievedAt == now.toString() })
        val issue = result.facts.single { it.kind == FactKind.APP_REVIEW_ISSUE }
        assertEquals("Mon App : 6 avis sur 10 des 90 derniers jours parlent de plantages", issue.statement)
        // The six complaints are the same review: one quoted fact, dated in Paris time, sourced to the store page
        val quote = result.facts.single { it.kind == FactKind.APP_REVIEW_QUOTE }
        assertEquals("Avis App Store 1/5 du 20/10/2026, version 3.2 : « Avis 1 »", quote.statement)
        assertEquals("l'app plante au démarrage", quote.excerpt)
        assertEquals("https://apps.apple.com/fr/app/id123?see-all=reviews", quote.sourceUrl)
        assertEquals(
            setOf(SignalType.RELEASE_STALL, SignalType.RATING_DROP, SignalType.QUALITY_COMPLAINTS),
            result.signals.map { it.type }.toSet(),
        )
        assertTrue(result.signals.all { it.factIds.isNotEmpty() })
    }

    @Test
    fun `an app that is not found gives an empty result`() = runTest {
        val result = source(MockHttp { json("""{"resultCount":0,"results":[]}""") }).analyze(7, 123, now)
        assertTrue(result.facts.isEmpty() && result.signals.isEmpty() && result.app == null)
    }

    @Test
    fun `a failing lookup is propagated to the caller`() = runTest {
        val outcome = runCatching { source(MockHttp { failure() }).analyze(7, 123, now) }
        assertTrue(outcome.isFailure)
    }

    @Test
    fun `failing review pages only remove the review facts`() = runTest {
        val http = MockHttp { request -> if ("/lookup" in request.url.toString()) json(lookup()) else failure() }
        val result = source(http).analyze(7, 123, now)
        assertEquals(2, result.facts.size)
        assertEquals(setOf(SignalType.RELEASE_STALL), result.signals.map { it.type }.toSet())
        assertEquals(emptyList(), source(http).recentReviews(123))
    }

    @Test
    fun `recentReviews parses one page and stops at an empty one`() = runTest {
        val reviews = source(serving()).recentReviews(123, pages = 5)
        assertEquals(10, reviews.size)
        assertEquals(1, reviews.first().rating)
        assertEquals("3.2", reviews.first().version)
        assertEquals(Instant.parse("2026-10-20T17:00:00Z"), reviews.first().updated)
    }

    @Test
    fun `a feed with a single entry is read as an object, not an array`() = runTest {
        val http = MockHttp { request ->
            if ("page=1/" in request.url.toString()) json("""{"feed":{"entry":${review(2, "bug")}}}""") else json("""{"feed":{}}""")
        }
        assertEquals(1, source(http).recentReviews(123).size)
    }

    @Test
    fun `topChart reads the ranking of a genre`() = runTest {
        val feed = """{"feed":{"entry":[
            {"id":{"attributes":{"im:id":"111"}},"im:name":{"label":"Alpha"},"im:artist":{"label":"Alpha SAS"},"category":{"attributes":{"im:id":"6015"}}},
            {"id":{"attributes":{"im:id":"x"}},"im:name":{"label":"Broken"}}]}}"""
        val http = MockHttp { json(feed) }
        val apps = source(http).topChart(6015, limit = 50, chart = "topgrossingapplications")
        assertEquals(listOf("https://itunes.apple.com/fr/rss/topgrossingapplications/limit=50/genre=6015/json"), http.urls)
        assertEquals(1, apps.size)
        assertEquals(AppStoreSource.ChartApp(111, "Alpha", "Alpha SAS", 6015), apps.single())
        assertEquals(emptyList(), source(MockHttp { failure() }).topChart(6015))
    }

    @Test
    fun `searchByCompany sends the term and keeps the results that have an id`() = runTest {
        val http = MockHttp {
            json("""{"results":[{"trackId":5,"trackName":"Alpha","sellerName":"Alpha SAS","sellerUrl":"https://alpha.fr","primaryGenreId":6000},{"trackName":"No id"}]}""")
        }
        val apps = source(http).searchByCompany("Alpha", limit = 3)
        val request = http.requests.single()
        assertEquals("https://itunes.apple.com/search", request.url.toString().substringBefore('?'))
        assertEquals(listOf("Alpha", "software", "fr", "3"), listOf("term", "entity", "country", "limit").map { request.param(it) })
        assertEquals(listOf(5L), apps.map { it.appId })
        assertEquals(emptyList(), source(MockHttp { failure(HttpStatusCode.BadGateway) }).searchByCompany("Alpha"))
    }
}
