package lab.doppio.bloom.data.sources.facts

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.test.runTest
import lab.doppio.bloom.core.model.FactKind
import lab.doppio.bloom.data.sources.MockHttp
import lab.doppio.bloom.data.sources.MutableClock
import lab.doppio.bloom.data.sources.failure
import lab.doppio.bloom.data.sources.html
import java.io.IOException
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebPageSourceTest {
    private val now = Instant.parse("2026-11-06T10:00:00Z")
    private val page = """<html><head><title>Carrières Acme</title><script>var secret = 1;</script><style>p { color: red }</style></head>
        <body><nav>Menu principal</nav><h1>Rejoignez-nous</h1><p>Nous recrutons un développeur Kotlin.</p>
        <footer>Mentions légales</footer><noscript>Activez JavaScript</noscript></body></html>"""

    private fun source(http: MockHttp) = WebPageSource(http.client, MutableClock(now))

    @Test
    fun `read returns the visible text and a fact that cites the exact URL`() = runTest {
        val http = MockHttp { html(page) }
        val (fact, text) = assertNotNull(source(http).read(7, "https://acme.fr/carrieres", 4_000))
        assertEquals(listOf("https://acme.fr/carrieres"), http.urls)
        assertEquals("Rejoignez-nous Nous recrutons un développeur Kotlin.", text)
        assertEquals("Page lue : Carrières Acme", fact.statement)
        assertEquals(FactKind.WEB_PAGE, fact.kind)
        assertEquals("https://acme.fr/carrieres", fact.sourceUrl)
        assertEquals(now.toString(), fact.retrievedAt)
        assertEquals(text, fact.excerpt)
        for (hidden in listOf("secret", "Menu principal", "Mentions légales", "Activez", "color")) assertFalse(hidden in text)
    }

    @Test
    fun `read truncates the text to maxChars and the excerpt to 500`() = runTest {
        val long = "<html><body><p>${"a".repeat(2_000)}</p></body></html>"
        val (fact, text) = assertNotNull(source(MockHttp { html(long) }).read(7, "https://acme.fr", 1_200))
        assertEquals(1_200, text.length)
        assertEquals(500, fact.excerpt!!.length)
        assertEquals(10, source(MockHttp { html(long) }).read(7, "https://acme.fr", 10)!!.second.length)
    }

    @Test
    fun `a page without a title is named after its URL`() = runTest {
        val (fact, _) = source(MockHttp { html("<html><body>Bonjour</body></html>") }).read(7, "https://acme.fr", 100)!!
        assertEquals("Page lue : https://acme.fr", fact.statement)
    }

    @Test
    fun `a transport error gives null`() = runTest {
        val client = HttpClient(MockEngine { throw IOException("connexion refusée") })
        assertNull(WebPageSource(client, MutableClock(now)).read(7, "https://acme.fr", 100))
    }

    @Test
    fun `an HTTP error page gives null, its body is never read as the company's page`() = runTest {
        assertNull(source(MockHttp { failure() }).read(7, "https://acme.fr", 100))
    }
}
