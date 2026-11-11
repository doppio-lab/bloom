package lab.doppio.bloom.data.sources.facts

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import lab.doppio.bloom.core.discovery.ReleaseRules
import lab.doppio.bloom.core.model.AccountId
import lab.doppio.bloom.core.model.Fact
import lab.doppio.bloom.core.model.FactKind
import lab.doppio.bloom.core.model.Signal
import lab.doppio.bloom.core.model.SignalType
import lab.doppio.bloom.core.model.SourceResult
import lab.doppio.bloom.core.model.fact
import lab.doppio.bloom.core.port.PlayStoreFacts
import lab.doppio.bloom.data.sources.http.RateLimiter
import lab.doppio.bloom.data.sources.http.sourcesJson
import org.jsoup.Jsoup
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** What the parser recognised on a listing. Each field is null when its part of the page was not recognised. */
data class PlayListing(val name: String?, val rating: Double?, val ratingCount: Long?, val updatedOn: LocalDate?)

/**
 * Google Play: rating, number of ratings and last update date of one app, from its public listing page
 * (hl=fr, gl=FR). Two anchors only, both meant for readers and crawlers rather than the page's obfuscated
 * script data: the schema.org JSON-LD block (rating) and the labelled "Date de mise à jour" line.
 * No reviews are read (the page shows a handful), so there is no RATING_DROP or QUALITY_COMPLAINTS from here.
 */
class PlayStoreSource(
    private val http: HttpClient,
    clock: Clock = Clock.systemDefaultZone(),
) : PlayStoreFacts {
    private val log = LoggerFactory.getLogger(javaClass)

    // One listing page is about 1.3 MB: slower than the App Store APIs
    private val limiter = RateLimiter(Duration.ofSeconds(5), clock)

    override suspend fun analyze(accountId: AccountId, packageName: String, now: Instant): SourceResult {
        if (!PlayListingParser.isPackageName(packageName)) {
            log.warn("Google Play: \"{}\" is not an Android package name, nothing read", packageName)
            return SourceResult(emptyList(), emptyList())
        }
        val html = runCatching {
            limiter.run {
                val response = http.get("https://play.google.com/store/apps/details") {
                    parameter("id", packageName); parameter("hl", "fr"); parameter("gl", "FR")
                }
                // An unknown package (404) or a block (403, 429) is not a listing: no fact
                if (response.status.isSuccess()) response.bodyAsText() else null.also { log.warn("Google Play: HTTP {} for {}", response.status.value, packageName) }
            }
        }.onFailure { log.warn("Google Play unreachable for {}: {}", packageName, it.message) }.getOrNull()
            ?: return SourceResult(emptyList(), emptyList())

        val listing = PlayListingParser.parse(html, packageName)
        if (listing == null) {
            log.warn("Google Play: the page of {} is not in the expected layout, no fact recorded", packageName)
            return SourceResult(emptyList(), emptyList())
        }
        val url = "https://play.google.com/store/apps/details?id=$packageName&hl=fr&gl=FR"
        val name = listing.name ?: "l'app"
        val facts = mutableListOf<Fact>()
        val signals = mutableListOf<Signal>()

        if (listing.rating != null && listing.ratingCount != null) {
            facts += fact(accountId, FactKind.APP_METADATA, "$name : note moyenne Google Play ${"%.1f".format(listing.rating).replace('.', ',')} sur ${listing.ratingCount} notes (fr)", url, at = now)
        }
        listing.updatedOn?.takeIf { !it.isAfter(now.atOffset(ZoneOffset.UTC).toLocalDate()) }?.let { updated ->
            val days = Duration.between(updated.atStartOfDay().toInstant(ZoneOffset.UTC), now).toDays()
            val f = fact(accountId, FactKind.APP_METADATA, "$name : dernière mise à jour Google Play il y a $days jours", url, at = now)
            facts += f
            ReleaseRules.stallStrength(days)?.let { signals += Signal(accountId, SignalType.RELEASE_STALL, it, now.toString(), listOf(f.id)) }
        }
        if (facts.isEmpty()) log.warn("Google Play: the page of {} has neither a rating nor an update date in the expected layout", packageName)
        return SourceResult(facts, signals)
    }
}

/** Pure reading of a listing page, tested on saved pages. Fails closed: anything unexpected gives null or a null field. */
internal object PlayListingParser {
    private val packageName = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
    fun isPackageName(value: String) = packageName.matches(value)

    private val months = mapOf(
        "janv" to 1, "janvier" to 1, "févr" to 2, "fevr" to 2, "février" to 2, "mars" to 3, "avr" to 4, "avril" to 4,
        "mai" to 5, "juin" to 6, "juil" to 7, "juillet" to 7, "août" to 8, "aout" to 8, "sept" to 9, "septembre" to 9,
        "oct" to 10, "octobre" to 10, "nov" to 11, "novembre" to 11, "déc" to 12, "dec" to 12, "décembre" to 12,
    )
    private val frenchDate = Regex("^(\\d{1,2})\\s+([\\p{L}]+)\\.?\\s+(\\d{4})$")

    /** Null when the page is not the listing of [expectedPackage] (no JSON-LD application block, other package, other OS). */
    fun parse(html: String, expectedPackage: String): PlayListing? {
        val doc = Jsoup.parse(html)
        val app = doc.select("script[type=application/ld+json]").firstNotNullOfOrNull { script ->
            runCatching { sourcesJson.parseToJsonElement(script.data()) }.getOrNull()
                ?.let { root -> (root as? JsonObject)?.let(::listOf) ?: runCatching { root.jsonArray.map { it.jsonObject } }.getOrNull() }
                ?.firstOrNull { it.text("@type") == "SoftwareApplication" && it.text("operatingSystem") == "ANDROID" }
        } ?: return null
        // A redirect or a look-alike page must never be read as this package
        if (!app.text("url").orEmpty().contains("id=$expectedPackage")) return null

        val aggregate = app["aggregateRating"] as? JsonObject
        val rating = aggregate?.text("ratingValue")?.toDoubleOrNull()?.takeIf { it in 1.0..5.0 }
        val count = aggregate?.text("ratingCount")?.toLongOrNull()?.takeIf { it > 0 }
        val updated = doc.getElementsMatchingOwnText("^Date de mise à jour$").firstNotNullOfOrNull { label ->
            label.nextElementSibling()?.text()?.trim()?.let(::parseFrenchDate)
        }
        return PlayListing(app.text("name"), rating.takeIf { count != null }, count.takeIf { rating != null }, updated)
    }

    /** "5 nov. 2026" or "5 novembre 2026". Null for anything else, including impossible dates. */
    fun parseFrenchDate(text: String): LocalDate? {
        val m = frenchDate.matchEntire(text) ?: return null
        val month = months[m.groupValues[2].lowercase()] ?: return null
        return runCatching { LocalDate.of(m.groupValues[3].toInt(), month, m.groupValues[1].toInt()) }.getOrNull()
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}
