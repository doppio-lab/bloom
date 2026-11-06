package lab.doppio.bloom.data.sources.discovery

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import lab.doppio.bloom.core.discovery.Press
import lab.doppio.bloom.core.model.FactIds
import lab.doppio.bloom.core.model.FactKind
import lab.doppio.bloom.core.model.Signal
import lab.doppio.bloom.core.model.SignalType
import lab.doppio.bloom.core.model.SourceResult
import lab.doppio.bloom.core.model.fact
import lab.doppio.bloom.data.discovery.DiscoverySource
import lab.doppio.bloom.data.discovery.Lead
import java.time.Duration
import java.time.Instant

/** Presse : titres qui annoncent une levée de fonds (flux RSS publics). Seuls le titre et le lien sont repris. */
class PressSource(
    private val http: HttpClient,
    private val feeds: List<String> = listOf(
        "https://www.frenchweb.fr/feed", "https://www.maddyness.com/feed/", "https://www.journaldunet.com/rss/",
        "https://www.usine-digitale.fr/rss", "https://siecledigital.fr/feed/",
    ),
    private val maxAgeDays: Long = 14,
) : DiscoverySource {
    override val key = "press"
    override val label = "presse"

    override suspend fun leads(now: Instant): List<Lead> {
        // One feed down does not hide the others: the source fails only when none of them answers
        val read = feeds.map { feed ->
            runCatching {
                val res = http.get(feed)
                if (!res.status.isSuccess()) error("HTTP ${res.status.value} sur $feed")
                Press.parseFeed(res.bodyAsText())
            }
        }
        if (read.all { it.isFailure }) error(read.first().exceptionOrNull()?.message ?: "aucun flux lisible")
        val items = read.flatMap { it.getOrDefault(emptyList()) }
        return items
            .filter { it.publishedAt == null || Duration.between(it.publishedAt, now).toDays() <= maxAgeDays }
            .mapNotNull { item -> Press.funding(item.title)?.let { item to it } }
            .map { (item, round) ->
                Lead(
                    name = round.company,
                    seenKey = "press:${FactIds.of(item.link, round.company)}",
                    // A headline gives a trade name only: two companies with that name means we cannot tell which one
                    requireUniqueMatch = true,
                    build = { accountId, at ->
                        val published = item.publishedAt ?: at
                        val f = fact(
                            accountId, FactKind.PRESS,
                            "${round.company} : levée de ${round.amount} annoncée le ${published.atZone(paris).toLocalDate().format(frDate)}",
                            item.link,
                            excerpt = item.title,
                            at = at,
                        )
                        SourceResult(listOf(f), listOf(Signal(accountId, SignalType.FUNDING, 0.8, published.toString(), listOf(f.id))))
                    },
                )
            }
    }
}
