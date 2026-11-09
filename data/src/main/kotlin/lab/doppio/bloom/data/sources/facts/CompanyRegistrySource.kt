package lab.doppio.bloom.data.sources.facts

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import lab.doppio.bloom.core.discovery.CompanyNames
import lab.doppio.bloom.core.discovery.Officer
import lab.doppio.bloom.core.discovery.Registry
import lab.doppio.bloom.core.model.AccountId
import lab.doppio.bloom.core.model.Fact
import lab.doppio.bloom.core.model.FactKind
import lab.doppio.bloom.core.model.fact
import lab.doppio.bloom.core.port.CompanyRegistryFacts
import lab.doppio.bloom.data.sources.FactTemplates
import lab.doppio.bloom.data.sources.http.RateLimiter
import lab.doppio.bloom.data.sources.http.str
import java.time.Clock
import java.time.Duration

/** Annuaire des entreprises (API Recherche d'entreprises, data.gouv, sans clé). */
class CompanyRegistrySource(private val http: HttpClient, private val clock: Clock = Clock.systemDefaultZone()) : CompanyRegistryFacts {
    private val limiter = RateLimiter(MIN_INTERVAL, clock)

    private companion object {
        const val SEARCH_URL = "https://recherche-entreprises.api.gouv.fr/search"
        const val COMPANY_PAGE_URL = "https://annuaire-entreprises.data.gouv.fr/entreprise"

        val MIN_INTERVAL: Duration = Duration.ofMillis(200)

        /** Candidates read per query: enough to tell two companies with the same name apart. */
        const val RESULTS_PER_QUERY = 5
    }

    data class Company(val siren: String, val name: String, val headcountRange: String?, val naf: String?, val createdOn: String?)

    private suspend fun records(query: String): List<JsonObject> {
        val res = limiter.run {
            http.get(SEARCH_URL) {
                parameter("q", query)
                parameter("per_page", RESULTS_PER_QUERY)
            }.body<JsonObject>()
        }
        return res["results"]?.jsonArray.orEmpty().map { it.jsonObject }
    }

    suspend fun search(query: String): List<Company> = records(query).mapNotNull { r ->
        Company(
            siren = r["siren"].str() ?: return@mapNotNull null,
            name = r["nom_complet"].str().orEmpty(),
            headcountRange = r["tranche_effectif_salarie"].str(),
            naf = r["activite_principale"].str(),
            createdOn = r["date_creation"].str(),
        )
    }

    /**
     * Companies that carry this name. The name is tried as written, then without its legal form and
     * punctuation, then part by part: the search finds nothing for "Finary SAS" but finds "Finary".
     */
    suspend fun matches(name: String): List<Company> {
        for (query in CompanyNames.registryQueries(name)) {
            val found = runCatching { search(query) }.getOrDefault(emptyList()).filter { CompanyNames.sameCompany(it.name, query) }
            if (found.isNotEmpty()) return found
        }
        return emptyList()
    }

    /** Legal officers of a company, as published by the registry. Natural persons only. */
    suspend fun officers(siren: String): List<Officer> =
        records(siren).firstOrNull { it["siren"].str() == siren }?.let { Registry.officers(it) }.orEmpty()

    fun pageUrl(siren: String): String = "$COMPANY_PAGE_URL/$siren"

    /** Exact lookup: the search endpoint accepts a SIREN as query. */
    suspend fun bySiren(siren: String): Company? = search(siren).firstOrNull { it.siren == siren }

    override suspend fun facts(accountId: AccountId, siren: String): List<Fact> {
        val company = bySiren(siren) ?: return emptyList()
        val url = pageUrl(siren)
        val at = clock.instant()
        return listOfNotNull(
            company.headcountRange?.let { fact(accountId, FactKind.COMPANY_REGISTRY, FactTemplates.registryHeadcount(company.name, it, headcountLabel(it)), url, at = at) },
            company.naf?.let { fact(accountId, FactKind.COMPANY_REGISTRY, FactTemplates.registryNaf(company.name, it), url, at = at) },
            company.createdOn?.let { fact(accountId, FactKind.COMPANY_REGISTRY, FactTemplates.registryCreated(company.name, it), url, at = at) },
        )
    }

    /** Codes de tranche d'effectif INSEE vers libellé et estimation basse, pour le scoring. */
    fun headcountLabel(code: String): String = FactTemplates.headcountBand(code)

    fun headcountLowerBound(code: String?): Int? = when (code) {
        "00" -> 0; "01" -> 1; "02" -> 3; "03" -> 6; "11" -> 10; "12" -> 20; "21" -> 50; "22" -> 100
        "31" -> 200; "32" -> 250; "41" -> 500; "42" -> 1000; "51" -> 2000; "52" -> 5000; "53" -> 10000
        else -> null
    }
}
