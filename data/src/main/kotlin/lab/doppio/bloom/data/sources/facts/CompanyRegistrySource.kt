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
import lab.doppio.bloom.data.sources.http.RateLimiter
import lab.doppio.bloom.data.sources.http.str
import java.time.Clock
import java.time.Duration

/** Annuaire des entreprises (API Recherche d'entreprises, data.gouv, sans clé). */
class CompanyRegistrySource(private val http: HttpClient, private val clock: Clock = Clock.systemDefaultZone()) : CompanyRegistryFacts {
    private val limiter = RateLimiter(Duration.ofMillis(200), clock)

    data class Company(val siren: String, val name: String, val headcountRange: String?, val naf: String?, val createdOn: String?)

    private suspend fun records(query: String): List<JsonObject> {
        val res = limiter.run {
            http.get("https://recherche-entreprises.api.gouv.fr/search") {
                parameter("q", query)
                parameter("per_page", 5)
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

    fun pageUrl(siren: String): String = "https://annuaire-entreprises.data.gouv.fr/entreprise/$siren"

    /** Exact lookup: the search endpoint accepts a SIREN as query. */
    suspend fun bySiren(siren: String): Company? = search(siren).firstOrNull { it.siren == siren }

    override suspend fun facts(accountId: AccountId, siren: String): List<Fact> {
        val company = bySiren(siren) ?: return emptyList()
        val url = pageUrl(siren)
        val at = clock.instant()
        return listOfNotNull(
            company.headcountRange?.let { fact(accountId, FactKind.COMPANY_REGISTRY, "${company.name} : tranche d'effectif INSEE $it (${headcountLabel(it)})", url, at = at) },
            company.naf?.let { fact(accountId, FactKind.COMPANY_REGISTRY, "${company.name} : code NAF $it", url, at = at) },
            company.createdOn?.let { fact(accountId, FactKind.COMPANY_REGISTRY, "${company.name} : créée le $it", url, at = at) },
        )
    }

    /** Codes de tranche d'effectif INSEE vers libellé et estimation basse, pour le scoring. */
    fun headcountLabel(code: String): String = when (code) {
        "00" -> "0 salarié"; "01" -> "1 ou 2"; "02" -> "3 à 5"; "03" -> "6 à 9"; "11" -> "10 à 19"
        "12" -> "20 à 49"; "21" -> "50 à 99"; "22" -> "100 à 199"; "31" -> "200 à 249"; "32" -> "250 à 499"
        "41" -> "500 à 999"; "42" -> "1 000 à 1 999"; "51" -> "2 000 à 4 999"; "52" -> "5 000 à 9 999"; "53" -> "10 000 et plus"
        else -> "inconnu"
    }

    fun headcountLowerBound(code: String?): Int? = when (code) {
        "00" -> 0; "01" -> 1; "02" -> 3; "03" -> 6; "11" -> 10; "12" -> 20; "21" -> 50; "22" -> 100
        "31" -> 200; "32" -> 250; "41" -> 500; "42" -> 1000; "51" -> 2000; "52" -> 5000; "53" -> 10000
        else -> null
    }
}
