package lab.doppio.bloom.data.sources.facts

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
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
import lab.doppio.bloom.data.discovery.DiscoverySource
import lab.doppio.bloom.data.sources.FactTemplates
import lab.doppio.bloom.data.sources.http.sourcesJson
import lab.doppio.bloom.data.sources.http.str

/** France Travail : offres d'emploi (API Offres d'emploi v2, OAuth2 client credentials). */
class FranceTravailSource(
    private val http: HttpClient,
    private val clientId: String,
    private val clientSecret: String,
    private val clock: Clock = Clock.systemDefaultZone(),
) : JobOfferFacts {
    private val log = LoggerFactory.getLogger(javaClass)
    private val tokenMutex = Mutex()
    private var token: String? = null
    private var tokenExpiry: Instant = Instant.EPOCH

    companion object {
        private const val TOKEN_URL = "https://entreprise.francetravail.fr/connexion/oauth2/access_token?realm=%2Fpartenaire"
        private const val SEARCH_URL = "https://api.francetravail.io/partenaire/offresdemploi/v2/offres/search"
        private const val OFFER_PAGE_URL = "https://candidat.francetravail.fr/offres/recherche/detail"

        /** Offers are read again after this many days: a posting is stable, and the key is the offer id. */
        const val RECHECK_AFTER_DAYS = DiscoverySource.DEFAULT_RECHECK_DAYS

        /** Token lifetime assumed when the API does not state one. */
        private const val DEFAULT_TOKEN_TTL_SECONDS = 1400

        /** The token is renewed this long before it expires. */
        private const val TOKEN_MARGIN_SECONDS = 60L

        /** The API caps a page at 150 results (and 1 150 per request). */
        private const val PAGE_SIZE = 150

        /** A throttled page (429) is asked again after the delay the API gives, at most this long. */
        private const val RETRY_AFTER_MAX_SECONDS = 5L

        /** Pages read per search. */
        private const val MAX_PAGES = 3

        /** Characters of the offer description kept as the fact excerpt. */
        private const val EXCERPT_CHARS = 400
    }

    /** Searches run concurrently: the lock makes them share one token instead of each asking for its own. */
    private suspend fun accessToken(): String = tokenMutex.withLock {
        token?.takeIf { clock.instant().isBefore(tokenExpiry) }?.let { return it }
        val res = http.submitForm(
            url = TOKEN_URL,
            formParameters = Parameters.build {
                append("grant_type", "client_credentials")
                append("client_id", clientId)
                append("client_secret", clientSecret)
                append("scope", "api_offresdemploiv2 o2dsoffre")
            },
        ).body<JsonObject>()
        val t = res["access_token"].str() ?: error("France Travail : pas de jeton (${res})")
        val ttl = res["expires_in"]?.jsonPrimitive?.intOrNull ?: DEFAULT_TOKEN_TTL_SECONDS
        token = t
        tokenExpiry = clock.instant().plusSeconds(ttl - TOKEN_MARGIN_SECONDS)
        return t
    }

    /**
     * Recherche d'offres. [publieeDepuis] accepte 1, 3, 7, 14 ou 31 jours. L'API plafonne à 150 résultats
     * par page et 1 150 par requête : on pagine jusqu'à [maxPages].
     */
    suspend fun search(motsCles: String, publieeDepuis: Int? = null, maxPages: Int = MAX_PAGES): List<JsonObject> = buildList {
        for (page in 0 until maxPages) {
            suspend fun request() = http.get(SEARCH_URL) {
                header("Authorization", "Bearer ${accessToken()}")
                parameter("motsCles", motsCles)
                publieeDepuis?.let { parameter("publieeDepuis", it) }
                parameter("range", "${page * PAGE_SIZE}-${page * PAGE_SIZE + PAGE_SIZE - 1}")
            }
            var res = request()
            // Throttled: wait what the API asks (a few seconds at most) and try the page once more instead of dropping it
            if (res.status.value == 429) {
                val wait = res.headers["Retry-After"]?.toLongOrNull()?.coerceIn(0, RETRY_AFTER_MAX_SECONDS) ?: RETRY_AFTER_MAX_SECONDS
                log.warn("France Travail limite le débit (429), nouvel essai dans {} s", wait)
                delay(wait * 1_000)
                res = request()
            }
            // Rejected credentials must be visible: silently reading zero offers hid a misconfiguration
            if (res.status.value == 401 || res.status.value == 403) error("France Travail a refusé les identifiants (HTTP ${res.status.value})")
            if (res.status.value == 204 || res.status.value >= 400) break
            val results = sourcesJson.parseToJsonElement(res.bodyAsText()).jsonObject["resultats"]?.jsonArray.orEmpty().map { it.jsonObject }
            addAll(results)
            if (results.size < PAGE_SIZE) break
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
                ?: "$OFFER_PAGE_URL/$id"
            val age = created?.let { Duration.between(it, now).toDays() }
            val f = fact(
                accountId, FactKind.JOB_POSTING,
                FactTemplates.jobOffer(title, age),
                url,
                excerpt = description.take(EXCERPT_CHARS),
                at = now,
            )
            facts += f
            // The need dates from the day the posting went up, not from the day we read it
            val observed = HiringRules.observedAt(created, now)
            signals += Signal(accountId, SignalType.HIRING_MOBILE, HiringRules.MOBILE_STRENGTH, observed.toString(), listOf(f.id))
            if (HiringRules.mentionsKmp(title + description)) signals += Signal(accountId, SignalType.HIRING_KMP, HiringRules.KMP_STRENGTH, observed.toString(), listOf(f.id))
            HiringRules.stuckStrength(age)?.let { signals += Signal(accountId, SignalType.HIRING_STUCK, it, now.toString(), listOf(f.id)) }
            if (HiringRules.indicatesCrossPlatformStack(title + " " + description)) {
                facts += fact(accountId, FactKind.JOB_POSTING, FactTemplates.crossPlatformStack(title), url, at = now)
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
