package lab.doppio.bloom.core.model

import lab.doppio.bloom.core.discovery.ReviewRules
import java.security.MessageDigest
import java.time.Instant

/** Stable short ids: the same statement from the same URL always gets the same fact id. */
object FactIds {
    fun of(sourceUrl: String, statement: String): String =
        MessageDigest.getInstance("SHA-1").digest("$sourceUrl|$statement".toByteArray())
            .joinToString("") { "%02x".format(it) }.take(8)
}

fun fact(accountId: AccountId, kind: FactKind, statement: String, sourceUrl: String, excerpt: String? = null, at: Instant = Instant.now()) =
    Fact(FactIds.of(sourceUrl, statement), accountId, kind, statement, sourceUrl, at.toString(), excerpt?.take(500))

data class AppMeta(val appId: Long, val name: String, val sellerName: String?, val sellerUrl: String?, val genreId: Int?)

/** What a fact source returns for one account: sourced facts and the signals computed from them. */
data class SourceResult(val facts: List<Fact>, val signals: List<Signal>, val app: AppMeta? = null)

/**
 * A fact restated at each read ("published 12 days ago", "rated 4.3 over 1 234 notes") gets a new id every time,
 * because its numbers changed. Keeps the latest reading of each one so a dossier or a draft never holds the same
 * posting twice with two different ages. The topic is the kind, the source and the statement without its numbers.
 * Display and writing use this. Citation checks must keep the full list: a draft may cite an older reading.
 */
fun List<Fact>.latestPerTopic(): List<Fact> =
    groupBy { Triple(it.kind, it.sourceUrl, it.statement.replace(Regex("\\d+"), "#")) }
        .values
        .map { readings -> readings.maxBy { Instant.parse(it.retrievedAt) } }
        .sortedByDescending { Instant.parse(it.retrievedAt) }

/**
 * Signals that describe a state of the account, recomputed at each read (rating, complaints, stalled releases,
 * open postings): the latest reading replaces the previous ones. Event signals (tender, funding, referral...) accumulate.
 */
val SignalType.isState: Boolean
    get() = this in setOf(
        SignalType.RATING_DROP, SignalType.QUALITY_COMPLAINTS, SignalType.RELEASE_STALL,
        SignalType.HIRING_MOBILE, SignalType.HIRING_KMP, SignalType.HIRING_STUCK,
    )

/** One App Store review as the public feed gives it. */
data class AppReview(val rating: Int, val title: String, val content: String, val version: String?, val updated: Instant?)

/**
 * What users report in the latest App Store read: the themes of the complaints, most cited first, then the reviews
 * quoted in the order [lab.doppio.bloom.core.discovery.ReviewRules.mostTelling] picked them (a technical problem
 * first, then the most recent). What an older read found and the latest one did not is left out.
 */
fun List<Fact>.currentReviewIssues(): List<Fact> {
    val all = filter { it.kind == FactKind.APP_REVIEW_ISSUE || it.kind == FactKind.APP_REVIEW_QUOTE }
    val latest = all.maxOfOrNull { Instant.parse(it.retrievedAt) } ?: return emptyList()
    val (themes, quotes) = all.filter { Instant.parse(it.retrievedAt) == latest }.partition { it.kind == FactKind.APP_REVIEW_ISSUE }
    val count = Regex("(\\d+) avis sur")
    val date = Regex("du (\\d{2})/(\\d{2})/(\\d{4})")
    return themes.sortedByDescending { count.find(it.statement)?.groupValues?.get(1)?.toIntOrNull() ?: 0 } +
        quotes.sortedWith(
            compareByDescending<Fact> { ReviewRules.themes(it.statement, it.excerpt.orEmpty()).isNotEmpty() }
                .thenByDescending { f -> date.find(f.statement)?.destructured?.let { (d, m, y) -> "$y$m$d" }.orEmpty() },
        )
}
