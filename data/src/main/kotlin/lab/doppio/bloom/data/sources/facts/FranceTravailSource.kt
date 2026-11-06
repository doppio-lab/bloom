package lab.doppio.bloom.data.sources.facts

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import lab.doppio.bloom.core.discovery.CompanyNames
import lab.doppio.bloom.core.discovery.HiringRules
import lab.doppio.bloom.core.model.AccountId
import lab.doppio.bloom.core.model.Fact
import lab.doppio.bloom.core.model.FactKind
import lab.doppio.bloom.core.model.Signal
import lab.doppio.bloom.core.model.SignalType
import lab.doppio.bloom.core.model.SourceResult
import lab.doppio.bloom.core.model.fact
import lab.doppio.bloom.core.port.JobOfferFacts
import lab.doppio.bloom.data.sources.http.sourcesJson
import lab.doppio.bloom.data.sources.http.str
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime

/** France Travail : offres d'emploi (API Offres d'emploi v2, OAuth2 client credentials). */
class FranceTravailSource(
    private val http: HttpClient,
    private val clientId: String,
    private val clientSecret: String,
    private val clock: Clock = Clock.systemDefaultZone(),
) : JobOfferFacts {
    private var token: String? = null
    private var tokenExpiry: Instant = Instant.EPOCH

    private suspend fun accessToken(): String {
        token?.takeIf { clock.instant().isBefore(tokenExpiry) }?.let { return it }
        val res = http.submitForm(
            url = "https://entreprise.francetravail.fr/connexion/oauth2/access_token?realm=%2Fpartenaire",
            formParameters = Parameters.build {
                append("grant_type", "client_credentials")
                append("client_id", clientId)
                append("client_secret", clientSecret)
                append("scope", "api_offresdemploiv2 o2dsoffre")
            },
        ).body<JsonObject>()
        val t = res["access_token"].str() ?: error("France Travail : pas de jeton (${res})")
        val ttl = res["expires_in"]?.jsonPrimitive?.intOrNull ?: 1400
        token = t
        tokenExpiry = clock.instant().plusSeconds(ttl - 60L)
        return t
    }

    /**
     * Recherche d'offres. [publieeDepuis] accepte 1, 3, 7, 14 ou 31 jours. L'API plafonne à 150 résultats
     * par page et 1 150 par requête : on pagine jusqu'à [maxPages].
     */
    suspend fun search(motsCles: String, publieeDepuis: Int? = null, maxPages: Int = 3): List<JsonObject> = buildList {
        for (page in 0 until maxPages) {
            val res = http.get("https://api.francetravail.io/partenaire/offresdemploi/v2/offres/search") {
                header("Authorization", "Bearer ${accessToken()}")
                parameter("motsCles", motsCles)
                publieeDepuis?.let { parameter("publieeDepuis", it) }
                parameter("range", "${page * 150}-${page * 150 + 149}")
            }
            // Rejected credentials must be visible: silently reading zero offers hid a misconfiguration
            if (res.status.value == 401 || res.status.value == 403) error("France Travail a refusé les identifiants (HTTP ${res.status.value})")
            if (res.status.value == 204 || res.status.value >= 400) break
            val results = sourcesJson.parseToJsonElement(res.bodyAsText()).jsonObject["resultats"]?.jsonArray.orEmpty().map { it.jsonObject }
            addAll(results)
            if (results.size < 150) break
        }
    }

    fun companyName(offer: JsonObject): String? = offer["entreprise"]?.jsonObject?.get("nom").str()?.trim()?.takeIf { it.isNotBlank() }
    fun companyUrl(offer: JsonObject): String? = offer["entreprise"]?.jsonObject?.get("url").str()
    fun offerId(offer: JsonObject): String? = offer["id"].str()
    fun isMobileOffer(offer: JsonObject): Boolean = HiringRules.isMobile(offer["intitule"].str().orEmpty())

    /** Faits et signaux tirés d'offres déjà filtrées sur une entreprise. */
    fun factsFromOffers(accountId: AccountId, offers: List<JsonObject>, now: Instant = clock.instant()): SourceResult {
        val facts = mutableListOf<Fact>()
        val signals = mutableListOf<Signal>()
        offers.filter { isMobileOffer(it) }.forEach { o ->
            val id = offerId(o) ?: return@forEach
            val title = o["intitule"].str().orEmpty()
            val description = o["description"].str().orEmpty()
            val created = o["dateCreation"].str()?.let { runCatching { ZonedDateTime.parse(it).toInstant() }.getOrNull() }
            val url = o["origineOffre"]?.jsonObject?.get("urlOrigine").str()
                ?: "https://candidat.francetravail.fr/offres/recherche/detail/$id"
            val age = created?.let { Duration.between(it, now).toDays() }
            val f = fact(
                accountId, FactKind.JOB_POSTING,
                "Offre d'emploi « $title »" + (age?.let { " publiée il y a $it jours" } ?: ""),
                url,
                excerpt = description.take(400),
                at = now,
            )
            facts += f
            signals += Signal(accountId, SignalType.HIRING_MOBILE, 0.7, now.toString(), listOf(f.id))
            if (HiringRules.mentionsKmp(title + description)) signals += Signal(accountId, SignalType.HIRING_KMP, 0.9, now.toString(), listOf(f.id))
            HiringRules.stuckStrength(age)?.let { signals += Signal(accountId, SignalType.HIRING_STUCK, it, now.toString(), listOf(f.id)) }
            if (HiringRules.mentionsCrossPlatform(title + description)) {
                facts += fact(accountId, FactKind.JOB_POSTING, "L'offre « $title » mentionne une stack cross-platform (Flutter ou React Native)", url, at = now)
            }
        }
        return SourceResult(facts, signals)
    }

    /** Offres mobiles d'une entreprise donnée (pour rafraîchir un compte connu). */
    override suspend fun mobileJobs(accountId: AccountId, companyName: String, now: Instant): SourceResult {
        val offers = search("mobile,$companyName", maxPages = 1)
            .filter { o -> companyName(o)?.let { CompanyNames.sameCompany(it, companyName) } == true }
        return factsFromOffers(accountId, offers, now)
    }
}
