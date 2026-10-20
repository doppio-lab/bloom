package lab.doppio.bloom.data.sources

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import lab.doppio.bloom.core.discovery.BoardProvider
import lab.doppio.bloom.core.discovery.BoardRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebPageSourceTest {
    private fun client(contentType: String, body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        HttpClient(MockEngine { respond(body, status, headersOf(HttpHeaders.ContentType, contentType)) })

    @Test
    fun `une page de 3 Mo est tronquée à 2 Mo avant d'être lue`() = runTest {
        val html = "<html><body>" + "a".repeat(3 * 1024 * 1024) + "</body></html>"
        val read = WebPageSource(client("text/html; charset=utf-8", html)).read(1, "https://velomoov.fr", maxChars = Int.MAX_VALUE)
        assertNotNull(read)
        assertTrue(read.second.length <= 2 * 1024 * 1024, "read ${read.second.length} chars")
        assertTrue(read.second.length >= 2 * 1024 * 1024 - 100, "read ${read.second.length} chars")
    }

    @Test
    fun `seul du texte ou du HTML est lu`() = runTest {
        assertNull(WebPageSource(client("application/pdf", "%PDF-1.4")).read(1, "https://velomoov.fr/plaquette.pdf"))
        assertNull(WebPageSource(client("application/octet-stream", "data")).read(1, "https://velomoov.fr/app.bin"))
        assertNull(WebPageSource(HttpClient(MockEngine { respond("no type") })).read(1, "https://velomoov.fr"), "no content type")
        assertEquals("Page lue : Velomoov", WebPageSource(client("text/html", "<title>Velomoov</title>")).read(1, "https://velomoov.fr")?.first?.statement)
        assertEquals("plain text", WebPageSource(client("text/plain", "plain text")).read(1, "https://velomoov.fr/robots.txt")?.second)
    }

    @Test
    fun `une page carrières n'est lue que si c'est du HTML, l'API du job board que si c'est du JSON`() = runTest {
        val html = """<a href="https://boards.greenhouse.io/velomoov">Jobs</a>"""
        assertEquals(BoardRef(BoardProvider.GREENHOUSE, "velomoov"), JobBoardSource(client("text/html", html)).find("velomoov.fr"))
        assertNull(JobBoardSource(client("application/pdf", html)).find("velomoov.fr"))

        val json = javaClass.getResource("/fixtures/greenhouse.json")!!.readText()
        assertTrue(JobBoardSource(client("application/json; charset=utf-8", json)).postings(BoardRef(BoardProvider.GREENHOUSE, "velomoov")).isNotEmpty())
        assertTrue(JobBoardSource(client("text/html", json)).postings(BoardRef(BoardProvider.GREENHOUSE, "velomoov")).isEmpty())
    }
}
