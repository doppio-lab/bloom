package lab.doppio.bloom.data.sources

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SsrfTest {
    private val html = headersOf(HttpHeaders.ContentType, "text/html")

    @Test
    fun `une page sur un hôte interne n'est jamais demandée`() = runTest {
        val engine = MockEngine { respond("<title>secret</title>", headers = html) }
        val web = WebPageSource(HttpClient(engine))
        assertNull(web.read(1, "http://localhost:8080/admin"))
        assertNull(web.read(1, "http://169.254.169.254/latest/meta-data/"))
        assertNull(web.read(1, "https://metadata.google.internal/computeMetadata/v1/"))
        assertNull(web.read(1, "file:///etc/passwd"))
        assertNull(JobBoardSource(HttpClient(engine)).find("10.0.0.5"))
        assertEquals(0, engine.requestHistory.size, "nothing was fetched")
    }

    @Test
    fun `une redirection vers un hôte interne est abandonnée`() = runTest {
        val engine = MockEngine { req ->
            if (req.url.host == "velomoov.fr") respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "http://127.0.0.1:5432/"))
            else respond("<title>secret</title>", headers = html)
        }
        assertNull(WebPageSource(HttpClient(engine)).read(1, "https://velomoov.fr"))
        assertEquals(listOf("velomoov.fr"), engine.requestHistory.map { it.url.host })
    }

    @Test
    fun `une seule redirection publique est suivie`() = runTest {
        val engine = MockEngine { req ->
            when {
                req.url.host == "velomoov.fr" && req.url.encodedPath == "/" -> respond("", HttpStatusCode.MovedPermanently, headersOf(HttpHeaders.Location, "https://www.velomoov.fr/"))
                req.url.host == "www.velomoov.fr" && req.url.encodedPath == "/" -> respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "/accueil"))
                req.url.encodedPath == "/accueil" -> respond("<title>Velomoov</title>", headers = html)
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val web = WebPageSource(HttpClient(engine))
        assertEquals("Page lue : Velomoov", web.read(1, "https://www.velomoov.fr/")?.first?.statement, "one hop, relative Location")
        assertNull(web.read(1, "https://velomoov.fr/"), "two hops are not followed")
    }
}
