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
import lab.doppio.bloom.data.discovery.SeenKeys
import lab.doppio.bloom.data.sources.FactTemplates
import lab.doppio.bloom.data.sources.http.RateLimiter
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** BODACC : annonces légales, augmentations de capital (open data, sans clé). */
class BodaccSource(
    private val http: HttpClient,
    private val minCapitalEur: Long = DEFAULT_MIN_CAPITAL_EUR,
    private val lookbackDays: Long = LOOKBACK_DAYS,
    private val maxPages: Int = MAX_PAGES,
    clock: Clock = Clock.systemDefaultZone(),
) : DiscoverySource {
    override val key = KEY
    override val label = "BODACC"
    private val limiter = RateLimiter(MIN_INTERVAL, clock)

    override suspend fun leads(now: Instant): List<Lead> {
        val since = now.atZone(paris).toLocalDate().minusDays(lookbackDays)
        val where = "familleavis=\"modification\" and search(modificationsgenerales,\"augmentation\") and dateparution>=date'$since'"
        val notices = buildList {
            for (page in 0 until maxPages) {
                val batch = limiter.run { http.records(URL, where, limit = OPEN_DATA_PAGE_SIZE, offset = page * OPEN_DATA_PAGE_SIZE) }
                addAll(batch.mapNotNull { Bodacc.parse(it) })
                if (batch.size < OPEN_DATA_PAGE_SIZE) break
            }
        }
        return notices.filter { Bodacc.isCandidate(it, minCapitalEur) }.map { n ->
            Lead(
                name = n.name,
                seenKey = SeenKeys.of(KEY, n.id),
                siren = n.siren,
                minHeadcount = MIN_HEADCOUNT,
                requireSectorOrApp = true,
                build = { accountId, at ->
                    // The notice gives the capital after the change, never the amount raised: the fact says exactly that
                    val f = fact(
                        accountId, FactKind.LEGAL_NOTICE,
                        FactTemplates.capitalIncrease(n.name, day(n.publishedOn), euros(n.capitalEur ?: 0)),
                        n.url,
                        excerpt = n.description,
                        at = at,
                    )
                    val strength = ((n.capitalEur ?: 0) / STRENGTH_FULL_CAPITAL_EUR).coerceIn(MIN_STRENGTH, 1.0)
                    SourceResult(listOf(f), listOf(Signal(accountId, SignalType.FUNDING, strength, startOfDay(n.publishedOn, at).toString(), listOf(f.id))))
                },
            )
        }
    }

    companion object {
        const val KEY = "bodacc"

        /** A capital below this is a micro-company or a holding: not a company that buys apps. */
        const val DEFAULT_MIN_CAPITAL_EUR = 100_000L

        /** Notices from the last week: the source runs nightly and the platform lags by days. */
        const val LOOKBACK_DAYS = 7L

        /** Pages of [OPEN_DATA_PAGE_SIZE] notices read per run. */
        const val MAX_PAGES = 5

        /** Companies the registry shows below this headcount are dropped: too small to buy an app project. */
        const val MIN_HEADCOUNT = 10

        /** A capital of this amount or more gives the full strength; below it the strength scales down. */
        const val STRENGTH_FULL_CAPITAL_EUR = 1_000_000.0

        const val MIN_STRENGTH = 0.4
        private val MIN_INTERVAL: Duration = Duration.ofMillis(500)
        private const val URL = "https://bodacc-datadila.opendatasoft.com/api/explore/v2.1/catalog/datasets/annonces-commerciales/records"
    }
}
