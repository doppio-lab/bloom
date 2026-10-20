package lab.doppio.bloom.data.sources

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import lab.doppio.bloom.core.discovery.CompanyNames
import lab.doppio.bloom.core.discovery.HiringRules
import lab.doppio.bloom.core.discovery.Officer
import lab.doppio.bloom.core.discovery.Registry
import lab.doppio.bloom.core.model.AccountId
import lab.doppio.bloom.core.model.Fact
import lab.doppio.bloom.core.model.FactKind
import lab.doppio.bloom.core.model.Signal
import lab.doppio.bloom.core.model.SignalType
import org.jsoup.Jsoup
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * Sources de faits. Chaque client renvoie des [Fact] sourcés et, quand c'est possible,
 * des [Signal] calculés de façon déterministe. Aucun LLM ici.
 */

val sourcesJson = Json { ignoreUnknownKeys = true; isLenient = true }

fun defaultHttpClient(): HttpClient = HttpClient(CIO) {
    install(ContentNegotiation) { json(sourcesJson) }
    install(HttpTimeout) { requestTimeoutMillis = 20_000 }
    defaultRequest { header("User-Agent", "DoppioBloom/0.1 (+https://doppio-lab.com)") }
}

object FactIds {
    /** Full SHA-1 of the source and the statement: the id is a global primary key, 32 bits would collide. */
    fun of(sourceUrl: String, statement: String): String =
        MessageDigest.getInstance("SHA-1").digest("$sourceUrl|$statement".toByteArray())
            .joinToString("") { "%02x".format(it) }
}

/** Publication date as written in a statement: stable from one night to the next, unlike an age in days. */
internal fun publishedOn(at: Instant): String = at.atZone(ZoneId.of("Europe/Paris")).toLocalDate().format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))

fun fact(accountId: AccountId, kind: FactKind, statement: String, sourceUrl: String, excerpt: String? = null, now: Instant = Instant.now()) =
    Fact(FactIds.of(sourceUrl, statement), accountId, kind, statement, sourceUrl, now.toString(), excerpt?.take(500))

data class AppMeta(val appId: Long, val name: String, val sellerName: String?, val sellerUrl: String?, val genreId: Int?)

data class SourceResult(val facts: List<Fact>, val signals: List<Signal>, val app: AppMeta? = null)

private fun JsonElement?.str(): String? = (this as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
private fun JsonObject.label(key: String): String? = this[key]?.jsonObject?.get("label").str()

/** Limiteur simple : Apple tolère environ 20 appels par minute sur l'API Search/Lookup. */
class RateLimiter(private val minInterval: Duration) {
    private val mutex = Mutex()
    private var last = Instant.EPOCH
    suspend fun <T> run(block: suspend () -> T): T = mutex.withLock {
        val wait = Duration.between(Instant.now(), last.plus(minInterval)).toMillis()
        if (wait > 0) kotlinx.coroutines.delay(wait)
        last = Instant.now()
        block()
    }
}

// ---------------------------------------------------------------------------------------------
// App Store : métadonnées (Lookup API) et avis récents (flux RSS public)
// ---------------------------------------------------------------------------------------------

class AppStoreSource(private val http: HttpClient, private val country: String = "fr") {
    private val limiter = RateLimiter(Duration.ofSeconds(3))

    data class Review(val rating: Int, val title: String, val content: String, val version: String?, val updated: Instant?)

    private val complaintKeywords = listOf(
        "crash", "plante", "bug", "beug", "lent", "lente", "freeze", "fige", "se ferme", "ne fonctionne plus",
        "connexion", "déconnect", "impossible", "batterie", "mise à jour", "écran blanc", "slow", "doesn't work", "login",
    )

    suspend fun analyze(accountId: AccountId, appId: Long, now: Instant = Instant.now()): SourceResult {
        val facts = mutableListOf<Fact>()
        val signals = mutableListOf<Signal>()

        val lookupUrl = "https://itunes.apple.com/lookup?id=$appId&country=$country"
        val lookup = limiter.run { sourcesJson.parseToJsonElement(http.get(lookupUrl).bodyAsText()).jsonObject }
        val app = lookup["results"]?.jsonArray?.firstOrNull()?.jsonObject ?: return SourceResult(emptyList(), emptyList())
        val name = app["trackName"].str() ?: "l'app"
        val storeUrl = app["trackViewUrl"].str() ?: lookupUrl
        val avg = app["averageUserRating"]?.jsonPrimitive?.doubleOrNull
        val count = app["userRatingCount"]?.jsonPrimitive?.intOrNull
        val version = app["version"].str()
        val releaseDate = app["currentVersionReleaseDate"].str()?.let { runCatching { Instant.parse(it) }.getOrNull() }
        val meta = AppMeta(
            appId = appId, name = name, sellerName = app["sellerName"].str() ?: app["artistName"].str(),
            sellerUrl = app["sellerUrl"].str(), genreId = app["primaryGenreId"]?.jsonPrimitive?.intOrNull,
        )

        if (avg != null && count != null) {
            facts += fact(accountId, FactKind.APP_METADATA, "$name : note moyenne App Store ${"%.1f".format(avg).replace('.', ',')} sur $count notes ($country)", storeUrl, now = now)
        }
        if (releaseDate != null) {
            val days = Duration.between(releaseDate, now).toDays()
            val f = fact(accountId, FactKind.APP_METADATA, "$name : dernière version $version publiée le ${publishedOn(releaseDate)}", storeUrl, now = now)
            facts += f
            if (days > 120) {
                signals += Signal(accountId, SignalType.RELEASE_STALL, ((days - 120) / 240.0).coerceIn(0.2, 1.0), now.toString(), listOf(f.id))
            }
        }

        val reviews = recentReviews(appId)
        val recent = reviews.filter { r -> r.updated?.let { Duration.between(it, now).toDays() <= 90 } ?: false }
        val rssUrl = "https://itunes.apple.com/$country/rss/customerreviews/page=1/id=$appId/sortBy=mostRecent/json"

        if (recent.size >= 10 && avg != null) {
            val recentAvg = recent.map { it.rating }.average()
            val drop = avg - recentAvg
            val f = fact(
                accountId, FactKind.APP_REVIEW,
                "$name : moyenne des ${recent.size} avis des 90 derniers jours ${"%.1f".format(recentAvg).replace('.', ',')}, contre ${"%.1f".format(avg).replace('.', ',')} en moyenne globale",
                rssUrl,
                now = now,
            )
            facts += f
            if (drop >= 0.4) signals += Signal(accountId, SignalType.RATING_DROP, (drop / 1.5).coerceIn(0.2, 1.0), now.toString(), listOf(f.id))
        }

        val complaints = recent.filter { r -> r.rating <= 2 && complaintKeywords.any { k -> k in (r.title + " " + r.content).lowercase() } }
        if (recent.size >= 5 && complaints.isNotEmpty()) {
            val share = complaints.size.toDouble() / recent.size
            val versions = complaints.mapNotNull { it.version }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
            val f = fact(
                accountId, FactKind.APP_REVIEW,
                "$name : ${complaints.size} avis sur ${recent.size} des 90 derniers jours signalent un problème technique" +
                    (versions?.let { " (version la plus citée : $it)" } ?: ""),
                rssUrl,
                excerpt = complaints.take(3).joinToString(" | ") { "${it.rating}/5 « ${it.title} » ${it.content.take(140)}" },
                now = now,
            )
            facts += f
            if (share >= 0.15) signals += Signal(accountId, SignalType.QUALITY_COMPLAINTS, (share * 2).coerceIn(0.2, 1.0), now.toString(), listOf(f.id))
        }
        return SourceResult(facts, signals, meta)
    }

    data class ChartApp(val appId: Long, val name: String, val artist: String, val genreId: Int?)

    /** Classement d'un genre sur le storefront (gratuites par défaut, ou « topgrossingapplications »). Flux public sans clé. */
    suspend fun topChart(genreId: Int, limit: Int = 200, chart: String = "topfreeapplications"): List<ChartApp> {
        val url = "https://itunes.apple.com/$country/rss/$chart/limit=$limit/genre=$genreId/json"
        val feed = runCatching { limiter.run { sourcesJson.parseToJsonElement(http.get(url).bodyAsText()).jsonObject } }
            .getOrNull()?.get("feed")?.jsonObject ?: return emptyList()
        val entries = when (val e = feed["entry"]) {
            is JsonArray -> e.map { it.jsonObject }
            is JsonObject -> listOf(e)
            else -> emptyList()
        }
        return entries.mapNotNull { e ->
            val id = e["id"]?.jsonObject?.get("attributes")?.jsonObject?.get("im:id").str()?.toLongOrNull() ?: return@mapNotNull null
            ChartApp(
                appId = id,
                name = e.label("im:name").orEmpty(),
                artist = e.label("im:artist").orEmpty(),
                genreId = e["category"]?.jsonObject?.get("attributes")?.jsonObject?.get("im:id").str()?.toIntOrNull(),
            )
        }
    }

    /** Recherche d'apps par nom d'entreprise : sert à retrouver l'app et le site d'une entreprise qui recrute. */
    suspend fun searchByCompany(company: String, limit: Int = 5): List<AppMeta> {
        val res = runCatching {
            limiter.run {
                sourcesJson.parseToJsonElement(
                    http.get("https://itunes.apple.com/search") {
                        parameter("term", company)
                        parameter("entity", "software")
                        parameter("country", country)
                        parameter("limit", limit)
                    }.bodyAsText(),
                ).jsonObject
            }
        }.getOrNull() ?: return emptyList()
        return res["results"]?.jsonArray.orEmpty().map { it.jsonObject }.mapNotNull { r ->
            AppMeta(
                appId = r["trackId"]?.jsonPrimitive?.content?.toLongOrNull() ?: return@mapNotNull null,
                name = r["trackName"].str().orEmpty(),
                sellerName = r["sellerName"].str() ?: r["artistName"].str(),
                sellerUrl = r["sellerUrl"].str(),
                genreId = r["primaryGenreId"]?.jsonPrimitive?.intOrNull,
            )
        }
    }

    /** Le flux RSS plafonne à 10 pages de 50 avis par storefront ; 2 pages suffisent pour 90 jours. */
    suspend fun recentReviews(appId: Long, pages: Int = 2): List<Review> = buildList {
        for (page in 1..pages) {
            val url = "https://itunes.apple.com/$country/rss/customerreviews/page=$page/id=$appId/sortBy=mostRecent/json"
            val feed = runCatching { limiter.run { sourcesJson.parseToJsonElement(http.get(url).bodyAsText()).jsonObject } }.getOrNull()?.get("feed")?.jsonObject ?: break
            val entries = when (val e = feed["entry"]) {
                is JsonArray -> e.map { it.jsonObject }
                is JsonObject -> listOf(e)
                else -> emptyList()
            }
            if (entries.isEmpty()) break
            entries.forEach { e ->
                val rating = e.label("im:rating")?.toIntOrNull() ?: return@forEach
                add(
                    Review(
                        rating = rating,
                        title = e.label("title").orEmpty(),
                        content = e.label("content").orEmpty(),
                        version = e.label("im:version"),
                        updated = e.label("updated")?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() },
                    ),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// France Travail : offres d'emploi (API Offres d'emploi v2, OAuth2 client credentials)
// ---------------------------------------------------------------------------------------------

class FranceTravailSource(
    private val http: HttpClient,
    private val clientId: String,
    private val clientSecret: String,
) {
    private var token: String? = null
    private var tokenExpiry: Instant = Instant.EPOCH

    private suspend fun accessToken(): String {
        token?.takeIf { Instant.now().isBefore(tokenExpiry) }?.let { return it }
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
        tokenExpiry = Instant.now().plusSeconds(ttl - 60L)
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
    fun factsFromOffers(accountId: AccountId, offers: List<JsonObject>, now: Instant = Instant.now()): SourceResult {
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
            // The date, not the age: the id derives from the statement, so an age would mint a new fact every night
            val f = fact(
                accountId, FactKind.JOB_POSTING,
                "Offre d'emploi « $title »" + (created?.let { " publiée le ${publishedOn(it)}" } ?: ""),
                url,
                excerpt = description.take(400),
                now = now,
            )
            facts += f
            signals += Signal(accountId, SignalType.HIRING_MOBILE, 0.7, now.toString(), listOf(f.id))
            if (HiringRules.mentionsKmp(title + description)) signals += Signal(accountId, SignalType.HIRING_KMP, 0.9, now.toString(), listOf(f.id))
            HiringRules.stuckStrength(age)?.let { signals += Signal(accountId, SignalType.HIRING_STUCK, it, now.toString(), listOf(f.id)) }
            if (HiringRules.mentionsCrossPlatform(title + description)) {
                facts += fact(accountId, FactKind.JOB_POSTING, "L'offre « $title » mentionne une stack cross-platform (Flutter ou React Native)", url, now = now)
            }
        }
        return SourceResult(facts, signals)
    }

    /** Offres mobiles d'une entreprise donnée (pour rafraîchir un compte connu). */
    suspend fun mobileJobs(accountId: AccountId, companyName: String, now: Instant = Instant.now()): SourceResult {
        val offers = search("mobile,$companyName", maxPages = 1)
            .filter { o -> companyName(o)?.let { lab.doppio.bloom.core.discovery.CompanyNames.sameCompany(it, companyName) } == true }
        return factsFromOffers(accountId, offers, now)
    }
}

// ---------------------------------------------------------------------------------------------
// Annuaire des entreprises (API Recherche d'entreprises, data.gouv, sans clé)
// ---------------------------------------------------------------------------------------------

class CompanyRegistrySource(private val http: HttpClient) {
    private val limiter = RateLimiter(Duration.ofMillis(200))

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

    suspend fun facts(accountId: AccountId, siren: String): List<Fact> {
        val company = bySiren(siren) ?: return emptyList()
        val url = "https://annuaire-entreprises.data.gouv.fr/entreprise/$siren"
        return listOfNotNull(
            company.headcountRange?.let { fact(accountId, FactKind.COMPANY_REGISTRY, "${company.name} : tranche d'effectif INSEE $it (${headcountLabel(it)})", url) },
            company.naf?.let { fact(accountId, FactKind.COMPANY_REGISTRY, "${company.name} : code NAF $it", url) },
            company.createdOn?.let { fact(accountId, FactKind.COMPANY_REGISTRY, "${company.name} : créée le $it", url) },
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

// ---------------------------------------------------------------------------------------------
// Pages web publiques de l'entreprise (site, page carrières, blog tech)
// ---------------------------------------------------------------------------------------------

class WebPageSource(private val http: HttpClient) {
    private val limiter = RateLimiter(Duration.ofSeconds(1))

    /** Renvoie le texte visible d'une page, tronqué. Le fait créé contient l'URL exacte lue. */
    suspend fun read(accountId: AccountId, url: String, maxChars: Int = 6_000): Pair<Fact, String>? {
        val html = runCatching { limiter.run { http.get(url).bodyAsText() } }.getOrNull() ?: return null
        val doc = Jsoup.parse(html, url)
        doc.select("script, style, nav, footer, noscript, svg").remove()
        val title = doc.title().ifBlank { url }
        val text = doc.body()?.text().orEmpty().take(maxChars)
        val f = fact(accountId, FactKind.WEB_PAGE, "Page lue : $title", url, excerpt = text.take(500))
        return f to text
    }
}

/** Utilitaire pour arrondir un score affiché. */
fun Double.pct(): Int = (this * 100).roundToInt()
