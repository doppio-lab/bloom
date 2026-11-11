package lab.doppio.bloom.core.model

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
