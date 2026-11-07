package lab.doppio.bloom.data.sources.discovery

import io.ktor.client.HttpClient
import lab.doppio.bloom.core.discovery.Bodacc
import lab.doppio.bloom.core.model.FactKind
import lab.doppio.bloom.core.model.Signal
import lab.doppio.bloom.core.model.SignalType
import lab.doppio.bloom.core.model.SourceResult
import lab.doppio.bloom.core.model.fact
import lab.doppio.bloom.data.discovery.DiscoverySource
import lab.doppio.bloom.data.discovery.Lead
import lab.doppio.bloom.data.sources.http.RateLimiter
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** BODACC : annonces légales, augmentations de capital (open data, sans clé). */
class BodaccSource(
    private val http: HttpClient,
    private val minCapitalEur: Long = 100_000,
    private val lookbackDays: Long = 7,
    private val maxPages: Int = 5,
    clock: Clock = Clock.systemDefaultZone(),
) : DiscoverySource {
    override val key = "bodacc"
    override val label = "BODACC"
    private val limiter = RateLimiter(Duration.ofMillis(500), clock)

    override suspend fun leads(now: Instant): List<Lead> {
        val since = now.atZone(paris).toLocalDate().minusDays(lookbackDays)
        val where = "familleavis=\"modification\" and search(modificationsgenerales,\"augmentation\") and dateparution>=date'$since'"
        val notices = buildList {
            for (page in 0 until maxPages) {
                val batch = limiter.run { http.records(URL, where, limit = 100, offset = page * 100) }
                addAll(batch.mapNotNull { Bodacc.parse(it) })
                if (batch.size < 100) break
            }
        }
        return notices.filter { Bodacc.isCandidate(it, minCapitalEur) }.map { n ->
            Lead(
                name = n.name,
                seenKey = "bodacc:${n.id}",
                siren = n.siren,
                minHeadcount = 10,
                requireSectorOrApp = true,
                build = { accountId, at ->
                    // The notice gives the capital after the change, never the amount raised: the fact says exactly that
                    val f = fact(
                        accountId, FactKind.LEGAL_NOTICE,
                        "${n.name} : augmentation de capital publiée au BODACC le ${day(n.publishedOn)}, capital porté à ${euros(n.capitalEur ?: 0)}",
                        n.url,
                        excerpt = n.description,
                        at = at,
                    )
                    val strength = ((n.capitalEur ?: 0) / 1_000_000.0).coerceIn(0.4, 1.0)
                    SourceResult(listOf(f), listOf(Signal(accountId, SignalType.FUNDING, strength, startOfDay(n.publishedOn, at).toString(), listOf(f.id))))
                },
            )
        }
    }

    private companion object {
        const val URL = "https://bodacc-datadila.opendatasoft.com/api/explore/v2.1/catalog/datasets/annonces-commerciales/records"
    }
}
