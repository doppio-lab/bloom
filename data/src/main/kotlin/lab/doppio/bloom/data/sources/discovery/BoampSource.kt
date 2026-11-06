package lab.doppio.bloom.data.sources.discovery

import io.ktor.client.HttpClient
import lab.doppio.bloom.core.discovery.Boamp
import lab.doppio.bloom.data.discovery.DiscoverySource
import lab.doppio.bloom.data.discovery.Lead
import java.time.Instant

/** BOAMP : appels d'offres publics qui demandent une application mobile (open data, sans clé). */
class BoampSource(private val http: HttpClient, private val lookbackDays: Long = 45) : DiscoverySource {
    override val key = "boamp"
    override val label = "marché public"

    override suspend fun leads(now: Instant): List<Lead> {
        val since = now.atZone(paris).toLocalDate().minusDays(lookbackDays)
        val where = "(objet like \"%application mobile%\" or objet like \"%appli mobile%\" or objet like \"%application smartphone%\") and dateparution>=date'$since'"
        return http.records(URL, where, limit = 100).mapNotNull { Boamp.parse(it) }
            .filter { Boamp.isOpenMobileTender(it, now) }
            .map { tenderLead(it, "boamp") }
    }

    private companion object {
        const val URL = "https://boamp-datadila.opendatasoft.com/api/explore/v2.1/catalog/datasets/boamp/records"
    }
}
