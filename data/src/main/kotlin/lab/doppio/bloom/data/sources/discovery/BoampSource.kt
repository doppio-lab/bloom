package lab.doppio.bloom.data.sources.discovery

import io.ktor.client.HttpClient
import lab.doppio.bloom.core.discovery.Boamp
import lab.doppio.bloom.data.discovery.DiscoverySource
import lab.doppio.bloom.data.discovery.Lead
import java.time.Instant

/** BOAMP : appels d'offres publics qui demandent une application mobile (open data, sans clé). */
class BoampSource(private val http: HttpClient, private val lookbackDays: Long = LOOKBACK_DAYS) : DiscoverySource {
    override val key = KEY
    override val label = "marché public"

    override suspend fun leads(now: Instant): List<Lead> {
        val since = now.atZone(paris).toLocalDate().minusDays(lookbackDays)
        val where = "(objet like \"%application mobile%\" or objet like \"%appli mobile%\" or objet like \"%application smartphone%\") and dateparution>=date'$since'"
        return http.records(URL, where, limit = OPEN_DATA_PAGE_SIZE).mapNotNull { Boamp.parse(it) }
            .filter { Boamp.isOpenMobileTender(it, now) }
            .map { tenderLead(it, KEY) }
    }

    companion object {
        const val KEY = "boamp"

        /** A tender stays open for weeks: reading the last 45 days catches the ones still open. */
        const val LOOKBACK_DAYS = 45L
        private const val URL = "https://boamp-datadila.opendatasoft.com/api/explore/v2.1/catalog/datasets/boamp/records"
    }
}
