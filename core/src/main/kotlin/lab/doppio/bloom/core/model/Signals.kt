package lab.doppio.bloom.core.model

import lab.doppio.bloom.core.discovery.ReviewRules
import java.security.MessageDigest
import java.time.Instant
import kotlinx.serialization.Serializable

/** Catégories de signaux d'intention. Chacune a sa demi-vie (voir scoring). */
@Serializable
enum class SignalType {
    /** Note App Store en baisse sur 90 jours. */
    RATING_DROP,
    /** Avis récents qui parlent de crash, lenteur, bug, connexion. */
    QUALITY_COMPLAINTS,
    /** Pas de mise à jour depuis longtemps : app à l'abandon ou équipe partie. */
    RELEASE_STALL,
    /** Offre d'emploi iOS / Android / mobile ouverte. */
    HIRING_MOBILE,
    /** Offre d'emploi qui mentionne Kotlin Multiplatform ou Compose Multiplatform. */
    HIRING_KMP,
    /** Offre d'emploi ouverte depuis plus de 45 jours : le recrutement coince. */
    HIRING_STUCK,
    /** Levée de fonds ou augmentation de capital. */
    FUNDING,
    /** Nouveau dirigeant tech ou produit. */
    NEW_TECH_LEADER,
    /** Annonce d'un nouveau produit mobile ou d'une refonte. */
    PRODUCT_LAUNCH,
    /** L'équipe construit avec des LLM (offres d'emploi, blog). */
    AI_ADOPTION,
    /** Interaction avec le contenu LinkedIn de Doppio (saisie manuelle). */
    CONTENT_ENGAGEMENT,
    /** Recommandation ou lien chaud (saisie manuelle). */
    REFERRAL,
    /** Open public tender that asks for a mobile app (BOAMP). */
    PUBLIC_TENDER,
    /** The organisation maintains Kotlin Multiplatform code in public (GitHub). */
    KMP_ADOPTION,
    /** A public mobile app contract ends within a year: the buyer will tender again (DECP). */
    CONTRACT_RENEWAL,
}

@Serializable
data class Signal(
    val accountId: AccountId,
    val type: SignalType,
    /** 0.0 à 1.0, calculé par la source (ex. ampleur de la baisse de note). */
    val strength: Double,
    val observedAt: String,
    val factIds: List<FactId>,
)

@Serializable
enum class FactKind {
    APP_METADATA, APP_REVIEW,
    /** One theme of the recent low-rated reviews (crashes, login...), with review quotes as excerpt. */
    APP_REVIEW_ISSUE,
    /** One recent low-rated review, quoted: rating, date and title in the statement, the text as excerpt. */
    APP_REVIEW_QUOTE,
    JOB_POSTING, COMPANY_REGISTRY, WEB_PAGE, MANUAL, LEGAL_NOTICE, PUBLIC_TENDER, PUBLIC_CONTRACT, PRESS, CODE_REPOSITORY,
}

/**
 * Un fait vérifiable, toujours accompagné de sa source.
 * L'id est stable (hash de la source + énoncé) pour être cité par les agents sous la forme [F:id].
 */
@Serializable
data class Fact(
    val id: FactId,
    val accountId: AccountId,
    val kind: FactKind,
    val statement: String,
    val sourceUrl: String,
    val retrievedAt: String,
    /** Extrait brut (avis client, ligne d'offre d'emploi) pour relecture humaine. */
    val excerpt: String? = null,
)

/** Stable short ids: the same statement from the same URL always gets the same fact id. */
object FactIds {
    fun of(sourceUrl: String, statement: String): String =
        MessageDigest.getInstance("SHA-1").digest("$sourceUrl|$statement".toByteArray())
            .joinToString("") { "%02x".format(it) }.take(Limits.FACT_ID_CHARS)
}

fun fact(accountId: AccountId, kind: FactKind, statement: String, sourceUrl: String, excerpt: String? = null, at: Instant = Instant.now()) =
    Fact(FactIds.of(sourceUrl, statement), accountId, kind, statement, sourceUrl, at.toString(), excerpt?.take(Limits.FACT_EXCERPT_CHARS))

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
