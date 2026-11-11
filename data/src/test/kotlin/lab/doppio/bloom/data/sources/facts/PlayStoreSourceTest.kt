package lab.doppio.bloom.data.sources.facts

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import lab.doppio.bloom.core.model.FactKind
import lab.doppio.bloom.core.model.SignalType
import lab.doppio.bloom.data.sources.MockHttp
import lab.doppio.bloom.data.sources.failure
import lab.doppio.bloom.data.sources.html
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Saved fragments of real listings (see data/src/test/resources/play). No test reaches the network. */
class PlayStoreSourceTest {
    private val now = Instant.parse("2026-11-10T10:00:00Z")

    private fun page(name: String) = checkNotNull(javaClass.getResource("/play/$name")).readText()
    private fun source(http: MockHttp) = PlayStoreSource(http.client)

    @Test
    fun `reads the rating and the update date of a saved listing`() = runTest {
        val http = MockHttp { html(page("com.comuto.html")) }
        val result = source(http).analyze(7, "com.comuto", now)

        assertEquals(
            listOf(
                "BlaBlaCar : Bus, Train, Covoit : note moyenne Google Play 4,5 sur 2632506 notes (fr)",
                "BlaBlaCar : Bus, Train, Covoit : dernière mise à jour Google Play il y a 5 jours",
            ),
            result.facts.map { it.statement },
        )
        assertTrue(result.facts.all { it.kind == FactKind.APP_METADATA && it.sourceUrl == "https://play.google.com/store/apps/details?id=com.comuto&hl=fr&gl=FR" && it.retrievedAt == now.toString() })
        assertTrue(result.signals.isEmpty(), "updated 5 days ago: no stalled release")
        assertEquals(listOf("https://play.google.com/store/apps/details?id=com.comuto&hl=fr&gl=FR"), http.urls)
    }

    @Test
    fun `a listing not updated for a long time gives the same release stall as the App Store`() = runTest {
        val later = Instant.parse("2027-05-10T10:00:00Z")
        val result = source(MockHttp { html(page("com.spotify.music.html")) }).analyze(7, "com.spotify.music", later)

        val stall = result.signals.single()
        assertEquals(SignalType.RELEASE_STALL, stall.type)
        // 182 days: (182 - 120) / 240
        assertEquals(0.2583, stall.strength, 0.001)
        assertEquals(listOf(result.facts.last().id), stall.factIds)
        assertEquals("Spotify: Musique & podcasts : note moyenne Google Play 4,4 sur 36624256 notes (fr)", result.facts.first().statement)
    }

    @Test
    fun `an unknown package, a block or a transport error gives nothing`() = runTest {
        assertTrue(source(MockHttp { failure(HttpStatusCode.NotFound) }).analyze(7, "com.nope.app", now).facts.isEmpty())
        assertTrue(source(MockHttp { failure(HttpStatusCode.TooManyRequests) }).analyze(7, "com.comuto", now).facts.isEmpty())
        assertTrue(source(MockHttp { error("connection reset") }).analyze(7, "com.comuto", now).facts.isEmpty())
    }

    @Test
    fun `a page whose layout is not recognised gives no fact`() = runTest {
        val consent = "<html><head><title>Avant d'accéder à Google</title></head><body><form>Accepter tout</form></body></html>"
        assertTrue(source(MockHttp { html(consent) }).analyze(7, "com.comuto", now).facts.isEmpty())

        // JSON-LD of another package: a redirect is never read as this app
        assertTrue(source(MockHttp { html(page("com.spotify.music.html")) }).analyze(7, "com.comuto", now).facts.isEmpty())
    }

    @Test
    fun `each half is optional, a missing half is simply absent`() = runTest {
        val withoutDate = page("com.comuto.html").replace("Date de mise à jour", "Autre libellé")
        assertEquals(1, source(MockHttp { html(withoutDate) }).analyze(7, "com.comuto", now).facts.size)

        val withoutRating = page("com.comuto.html").replace("\"aggregateRating\"", "\"renamed\"")
        assertEquals(listOf("BlaBlaCar : Bus, Train, Covoit : dernière mise à jour Google Play il y a 5 jours"), source(MockHttp { html(withoutRating) }).analyze(7, "com.comuto", now).facts.map { it.statement })

        val nonsenseRating = page("com.comuto.html").replace("4.542966842651367", "9.7")
        assertEquals(1, source(MockHttp { html(nonsenseRating) }).analyze(7, "com.comuto", now).facts.size)
    }

    @Test
    fun `an update date in the future is ignored`() = runTest {
        val future = page("com.comuto.html").replace("5 nov. 2026", "5 déc. 2026")
        assertEquals(1, source(MockHttp { html(future) }).analyze(7, "com.comuto", now).facts.size)
    }

    @Test
    fun `a value that is not an Android package name never reaches the network`() = runTest {
        val http = MockHttp { html(page("com.comuto.html")) }
        assertTrue(source(http).analyze(7, "com.comuto&hl=en", now).facts.isEmpty())
        assertTrue(source(http).analyze(7, "../etc", now).facts.isEmpty())
        assertTrue(http.requests.isEmpty())
    }

    @Test
    fun `french dates are read, anything else is not`() {
        assertEquals(LocalDate.of(2026, 11, 5), PlayListingParser.parseFrenchDate("5 nov. 2026"))
        assertEquals(LocalDate.of(2026, 2, 18), PlayListingParser.parseFrenchDate("18 février 2026"))
        assertEquals(LocalDate.of(2026, 7, 1), PlayListingParser.parseFrenchDate("1 juil. 2026"))
        assertNull(PlayListingParser.parseFrenchDate("Nov 5, 2026"))
        assertNull(PlayListingParser.parseFrenchDate("31 févr. 2026"))
        assertNull(PlayListingParser.parseFrenchDate("5 foo 2026"))
    }
}
