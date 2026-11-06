package lab.doppio.bloom.data.sources.facts

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import lab.doppio.bloom.core.model.AccountId
import lab.doppio.bloom.core.model.Fact
import lab.doppio.bloom.core.model.FactKind
import lab.doppio.bloom.core.model.fact
import lab.doppio.bloom.core.port.WebPages
import lab.doppio.bloom.data.sources.http.RateLimiter
import org.jsoup.Jsoup
import java.time.Clock
import java.time.Duration

/** Pages web publiques de l'entreprise (site, page carrières, blog tech). */
class WebPageSource(private val http: HttpClient, private val clock: Clock = Clock.systemDefaultZone()) : WebPages {
    private val limiter = RateLimiter(Duration.ofSeconds(1), clock)

    /** Renvoie le texte visible d'une page, tronqué. Le fait créé contient l'URL exacte lue. */
    override suspend fun read(accountId: AccountId, url: String, maxChars: Int): Pair<Fact, String>? {
        val html = runCatching { limiter.run { http.get(url).bodyAsText() } }.getOrNull() ?: return null
        val doc = Jsoup.parse(html, url)
        doc.select("script, style, nav, footer, noscript, svg").remove()
        val title = doc.title().ifBlank { url }
        val text = doc.body().text().orEmpty().take(maxChars)
        val f = fact(accountId, FactKind.WEB_PAGE, "Page lue : $title", url, excerpt = text.take(500), at = clock.instant())
        return f to text
    }
}
