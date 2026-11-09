package lab.doppio.bloom.data.sources.facts

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import lab.doppio.bloom.core.discovery.ReleaseRules
import lab.doppio.bloom.core.discovery.ReviewRules
import lab.doppio.bloom.core.model.AccountId
import lab.doppio.bloom.core.model.AppMeta
import lab.doppio.bloom.core.model.AppReview
import lab.doppio.bloom.core.model.Fact
import lab.doppio.bloom.core.model.FactKind
import lab.doppio.bloom.core.model.Signal
import lab.doppio.bloom.core.model.SignalType
import lab.doppio.bloom.core.model.SourceResult
import lab.doppio.bloom.core.model.fact
import lab.doppio.bloom.core.port.AppStoreFacts
import lab.doppio.bloom.data.sources.FactTemplates
import lab.doppio.bloom.data.sources.http.RateLimiter
import lab.doppio.bloom.data.sources.http.label
import lab.doppio.bloom.data.sources.http.sourcesJson
import lab.doppio.bloom.data.sources.http.str
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** App Store : métadonnées (Lookup API) et avis récents (flux RSS public). */
class AppStoreSource(
    private val http: HttpClient,
    private val country: String = "fr",
    clock: Clock = Clock.systemDefaultZone(),
) : AppStoreFacts {
    private val limiter = RateLimiter(MIN_INTERVAL, clock)

    companion object {
        private const val ITUNES = "https://itunes.apple.com"
        private const val SEARCH_URL = "$ITUNES/search"

        private fun lookupUrl(appId: Long, country: String) = "$ITUNES/lookup?id=$appId&country=$country"

        private fun chartUrl(country: String, chart: String, limit: Int, genreId: Int) =
            "$ITUNES/$country/rss/$chart/limit=$limit/genre=$genreId/json"

        /** The customer reviews RSS feed of an app on a storefront, most recent first. */
        internal fun reviewsUrl(country: String, appId: Long, page: Int) =
            "$ITUNES/$country/rss/customerreviews/page=$page/id=$appId/sortBy=mostRecent/json"

        /** Apple tolerates about 20 calls per minute on its Search and Lookup APIs: one every 3 seconds. */
        private val MIN_INTERVAL: Duration = Duration.ofSeconds(3)

        /** Reviews older than this say nothing about the app as it is today. */
        const val REVIEW_WINDOW_DAYS = 90L

        /** The RSS feed holds at most 50 reviews per page: 2 pages cover [REVIEW_WINDOW_DAYS] for most apps. */
        const val REVIEW_PAGES = 2

        /** No release for this long starts to look like an abandoned app (the rule is shared with Google Play). */
        const val RELEASE_STALL_AFTER_DAYS = ReleaseRules.STALL_AFTER_DAYS

        /** Fewer recent reviews than this say nothing about the average rating. */
        const val MIN_REVIEWS_FOR_RATING = 10

        /** The recent average must sit this far below the overall one to count as a drop. */
        const val RATING_DROP_MIN = 0.4

        /** A drop of this many stars gives the full strength. */
        private const val RATING_DROP_FULL = 1.5

        /** Fewer recent reviews than this cannot show a pattern of complaints. */
        const val MIN_REVIEWS_FOR_COMPLAINTS = 5

        /** Only low ratings (this or below) count as complaints. */
        const val COMPLAINT_MAX_RATING = ReviewRules.COMPLAINT_MAX_RATING

        /** The share of complaining reviews must reach this to count as a quality problem. */
        const val COMPLAINT_SHARE_MIN = 0.15

        /** A share of 50 percent gives the full strength. */
        private const val COMPLAINT_SHARE_FACTOR = 2

        /** Complaints quoted in the fact excerpt, and characters kept from each. */
        const val COMPLAINT_EXAMPLES = 3
        const val COMPLAINT_EXCERPT_CHARS = 140

        /** A signal never weighs less than this once it exists. */
        const val MIN_STRENGTH = 0.2

        /** Apps read per chart: the most the RSS feed returns. */
        const val CHART_SIZE = 200

        /** Review themes kept as facts per read, the most cited first. */
        const val MAX_ISSUES = 4

        private val PARIS: ZoneId = ZoneId.of("Europe/Paris")
        private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")

        /** Candidate apps returned by a search by company name. */
        const val SEARCH_RESULTS = 5
    }

    override suspend fun analyze(accountId: AccountId, appId: Long, now: Instant): SourceResult {
        val facts = mutableListOf<Fact>()
        val signals = mutableListOf<Signal>()

        val lookupUrl = lookupUrl(appId, country)
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
            facts += fact(accountId, FactKind.APP_METADATA, FactTemplates.appRating(name, avg, count, country), storeUrl, at = now)
        }
        if (releaseDate != null) {
            val days = Duration.between(releaseDate, now).toDays()
            val f = fact(accountId, FactKind.APP_METADATA, FactTemplates.appLastRelease(name, version, days), storeUrl, at = now)
            facts += f
            ReleaseRules.stallStrength(days)?.let {
                signals += Signal(accountId, SignalType.RELEASE_STALL, it, now.toString(), listOf(f.id))
            }
        }

        val reviews = recentReviews(appId)
        val recent = reviews.filter { r -> r.updated?.let { Duration.between(it, now).toDays() <= REVIEW_WINDOW_DAYS } ?: false }
        val rssUrl = reviewsUrl(country, appId, 1)

        if (recent.size >= MIN_REVIEWS_FOR_RATING && avg != null) {
            val recentAvg = recent.map { it.rating }.average()
            val drop = avg - recentAvg
            val f = fact(
                accountId, FactKind.APP_REVIEW,
                FactTemplates.appRecentRating(name, recent.size, REVIEW_WINDOW_DAYS, recentAvg, avg),
                rssUrl,
                at = now,
            )
            facts += f
            if (drop >= RATING_DROP_MIN) signals += Signal(accountId, SignalType.RATING_DROP, (drop / RATING_DROP_FULL).coerceIn(MIN_STRENGTH, 1.0), now.toString(), listOf(f.id))
        }

        val complaints = recent.filter { r -> ReviewRules.isTechnicalComplaint(r.rating, r.title, r.content) }
        if (recent.size >= 5 && complaints.isNotEmpty()) {
            val share = complaints.size.toDouble() / recent.size
            val versions = complaints.mapNotNull { it.version }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
            val f = fact(
                accountId, FactKind.APP_REVIEW,
                FactTemplates.appComplaints(name, complaints.size, recent.size, REVIEW_WINDOW_DAYS, versions),
                rssUrl,
                at = now,
                excerpt = complaints.take(COMPLAINT_EXAMPLES).joinToString(" | ") { "${it.rating}/5 « ${it.title} » ${it.content.take(COMPLAINT_EXCERPT_CHARS)}" },
            )
            facts += f
            facts += issueFacts(accountId, name, complaints, recent.size, rssUrl, now)
            if (share >= 0.15) signals += Signal(accountId, SignalType.QUALITY_COMPLAINTS, (share * 2).coerceIn(0.2, 1.0), now.toString(), listOf(f.id))
        }
        facts += quoteFacts(accountId, appId, recent, now)
        return SourceResult(facts, signals, meta)
    }

    /** One fact per theme the complaints mention (crashes, login...), most cited first. */
    private fun issueFacts(accountId: AccountId, name: String, complaints: List<AppReview>, recentCount: Int, sourceUrl: String, now: Instant): List<Fact> =
        ReviewRules.Theme.entries
            .map { theme -> theme to complaints.count { theme in ReviewRules.themes(it.title, it.content) } }
            .filter { (_, count) -> count > 0 }
            .sortedByDescending { (_, count) -> count }
            .take(MAX_ISSUES)
            .map { (theme, count) ->
                fact(accountId, FactKind.APP_REVIEW_ISSUE, "$name : $count avis sur $recentCount des 90 derniers jours parlent de ${theme.label}", sourceUrl, at = now)
            }

    /**
     * The low-rated reviews worth quoting, one fact each, so the person contacted hears their own users' words.
     * The source is the reviews page of the store, where anyone can read the review.
     */
    private fun quoteFacts(accountId: AccountId, appId: Long, recent: List<AppReview>, now: Instant): List<Fact> =
        ReviewRules.mostTelling(recent).map { r ->
            val on = r.updated?.let { " du " + DAY.format(it.atZone(PARIS)) }.orEmpty()
            val version = r.version?.let { ", version $it" }.orEmpty()
            fact(
                accountId, FactKind.APP_REVIEW_QUOTE,
                "Avis App Store ${r.rating}/5$on$version : « ${r.title.trim()} »",
                "https://apps.apple.com/$country/app/id$appId?see-all=reviews",
                at = now,
                excerpt = r.content.trim(),
            )
        }.distinctBy { it.id }

    data class ChartApp(val appId: Long, val name: String, val artist: String, val genreId: Int?)

    /** Classement d'un genre sur le storefront (gratuites par défaut, ou « topgrossingapplications »). Flux public sans clé. */
    suspend fun topChart(genreId: Int, limit: Int = CHART_SIZE, chart: String = "topfreeapplications"): List<ChartApp> {
        val url = chartUrl(country, chart, limit, genreId)
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
    suspend fun searchByCompany(company: String, limit: Int = SEARCH_RESULTS): List<AppMeta> {
        val res = runCatching {
            limiter.run {
                sourcesJson.parseToJsonElement(
                    http.get(SEARCH_URL) {
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
    suspend fun recentReviews(appId: Long, pages: Int = 2): List<AppReview> = buildList {
        for (page in 1..pages) {
            val url = reviewsUrl(country, appId, page)
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
                    AppReview(
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
