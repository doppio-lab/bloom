package lab.doppio.bloom.data.sources.facts

import io.ktor.client.HttpClient
import java.time.Clock
import java.time.Duration
import lab.doppio.bloom.core.model.AccountId
import lab.doppio.bloom.core.model.Fact
import lab.doppio.bloom.core.model.FactKind
import lab.doppio.bloom.core.model.Limits
import lab.doppio.bloom.core.model.fact
import lab.doppio.bloom.core.port.WebPages
import lab.doppio.bloom.data.sources.FactTemplates
import lab.doppio.bloom.data.sources.http.RateLimiter
import lab.doppio.bloom.data.sources.http.fetchPublic
import lab.doppio.bloom.data.sources.http.withoutRedirects
import org.jsoup.Jsoup

/** Pages web publiques de l'entreprise (site, page carrières, blog tech). */
class WebPageSource(client: HttpClient, private val clock: Clock = Clock.systemDefaultZone()) : WebPages {
    private val http = client.withoutRedirects()
    private val limiter = RateLimiter(MIN_INTERVAL, clock)

    private companion object {
        val MIN_INTERVAL: Duration = Duration.ofSeconds(1)
    }

    /** Renvoie le texte visible d'une page, tronqué. Le fait créé contient l'URL exacte lue. */
    override suspend fun read(accountId: AccountId, url: String, maxChars: Int): Pair<Fact, String>? {
        // An error page (404, 5xx), a private host, a PDF or a binary is not the company's page: it gives no fact,
        // like a transport error. At most 2 MB of the body are read.
        val html = runCatching { limiter.run { http.fetchPublic(url) } }.getOrNull() ?: return null
        val doc = Jsoup.parse(html, url)
        doc.select("script, style, nav, footer, noscript, svg").remove()
        val title = doc.title().ifBlank { url }
        val text = doc.body().text().orEmpty().take(maxChars)
        val f = fact(accountId, FactKind.WEB_PAGE, FactTemplates.webPage(title), url, excerpt = text.take(Limits.FACT_EXCERPT_CHARS), at = clock.instant())
        return f to text
    }
}
