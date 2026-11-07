package lab.doppio.bloom.data.sources.facts

import kotlinx.coroutines.test.runTest
import lab.doppio.bloom.core.discovery.BoardProvider
import lab.doppio.bloom.core.discovery.BoardRef
import lab.doppio.bloom.core.model.SignalType
import lab.doppio.bloom.data.sources.MockHttp
import lab.doppio.bloom.data.sources.MutableClock
import lab.doppio.bloom.data.sources.failure
import lab.doppio.bloom.data.sources.fixture
import lab.doppio.bloom.data.sources.html
import lab.doppio.bloom.data.sources.json
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JobBoardSourceTest {
    private val now = Instant.parse("2026-10-10T20:00:00Z")
    private fun source(http: MockHttp) = JobBoardSource(http.client, MutableClock(now))

    @Test
    fun `postings reads the public API of the board`() = runTest {
        val http = MockHttp { json(fixture("greenhouse.json")) }
        val postings = source(http).postings(BoardRef(BoardProvider.GREENHOUSE, "doctolib"))
        assertEquals(3, postings.size)
        assertEquals(1, http.requests.size)
        assertTrue(http.urls.single().contains("doctolib"))
    }

    @Test
    fun `an unreachable or malformed board gives no postings`() = runTest {
        val ref = BoardRef(BoardProvider.LEVER, "swile")
        assertEquals(emptyList(), source(MockHttp { failure() }).postings(ref))
        assertEquals(emptyList(), source(MockHttp { json("<not json") }).postings(ref))
    }

    @Test
    fun `find follows the links of the company site until a board shows up`() = runTest {
        val http = MockHttp { request ->
            if (request.url.encodedPath == "/careers") html("""<a href="https://jobs.lever.co/swile">Postuler</a>""") else failure()
        }
        assertEquals(BoardRef(BoardProvider.LEVER, "swile"), source(http).find("swile.co"))
        assertEquals(listOf("https://swile.co", "https://swile.co/careers"), http.urls)
        assertNull(source(MockHttp { html("<p>rien</p>") }).find("swile.co"))
    }

    @Test
    fun `result turns the mobile postings into facts at the given time`() = runTest {
        val http = MockHttp { json(fixture("greenhouse.json")) }
        val source = source(http)
        val postings = source.postings(BoardRef(BoardProvider.GREENHOUSE, "doctolib"))
        val result = source.result(7, postings, now)
        assertEquals(1, result.facts.size)
        assertTrue(result.facts.single().statement.startsWith("Offre d'emploi « "))
        assertEquals(now.toString(), result.facts.single().retrievedAt)
        assertTrue(SignalType.HIRING_MOBILE in result.signals.map { it.type })
    }
}
