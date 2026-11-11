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
import lab.doppio.bloom.core.model.Fact
import lab.doppio.bloom.core.model.FactKind
import lab.doppio.bloom.core.model.Signal
import lab.doppio.bloom.core.model.SignalType
import lab.doppio.bloom.core.model.SourceResult
import lab.doppio.bloom.core.model.fact
import lab.doppio.bloom.core.port.AppStoreFacts
import lab.doppio.bloom.data.sources.http.RateLimiter
import lab.doppio.bloom.data.sources.http.label
import lab.doppio.bloom.data.sources.http.sourcesJson
import lab.doppio.bloom.data.sources.http.str
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

/** App Store : métadonnées (Lookup API) et avis récents (flux RSS public). */
class AppStoreSource(
    private val http: HttpClient,
    private val country: String = "fr",
    clock: Clock = Clock.systemDefaultZone(),
) : AppStoreFacts {
    private val limiter = RateLimiter(Duration.ofSeconds(3), clock)

    data class Review(val rating: Int, val title: String, val content: String, val version: String?, val updated: Instant?)

    override suspend fun analyze(accountId: AccountId, appId: Long, now: Instant): SourceResult {
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
            facts += fact(accountId, FactKind.APP_METADATA, "$name : note moyenne App Store ${"%.1f".format(avg).replace('.', ',')} sur $count notes ($country)", storeUrl, at = now)
        }
        if (releaseDate != null) {
            val days = Duration.between(releaseDate, now).toDays()
            val f = fact(accountId, FactKind.APP_METADATA, "$name : dernière version $version publiée il y a $days jours", storeUrl, at = now)
            facts += f
            ReleaseRules.stallStrength(days)?.let {
                signals += Signal(accountId, SignalType.RELEASE_STALL, it, now.toString(), listOf(f.id))
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
                at = now,
            )
            facts += f
            if (drop >= 0.4) signals += Signal(accountId, SignalType.RATING_DROP, (drop / 1.5).coerceIn(0.2, 1.0), now.toString(), listOf(f.id))
        }

        val complaints = recent.filter { r -> ReviewRules.isTechnicalComplaint(r.rating, r.title, r.content) }
        if (recent.size >= 5 && complaints.isNotEmpty()) {
            val share = complaints.size.toDouble() / recent.size
            val versions = complaints.mapNotNull { it.version }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
            val f = fact(
                accountId, FactKind.APP_REVIEW,
                "$name : ${complaints.size} avis sur ${recent.size} des 90 derniers jours signalent un problème technique" +
                    (versions?.let { " (version la plus citée : $it)" } ?: ""),
                rssUrl,
                at = now,
                excerpt = complaints.take(3).joinToString(" | ") { "${it.rating}/5 « ${it.title} » ${it.content.take(140)}" },
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
