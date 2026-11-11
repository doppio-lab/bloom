package lab.doppio.bloom.core.golden

import lab.doppio.bloom.core.model.Account
import lab.doppio.bloom.core.model.Dossier
import lab.doppio.bloom.core.model.Market
import lab.doppio.bloom.core.model.Offer
import lab.doppio.bloom.core.model.Sector
import lab.doppio.bloom.core.scoring.Scoring.Tier

/**
 * The golden evaluation set: fictional cases, each labelled "must surface" or "must not surface".
 * To add a case, add one line to a table below and run `./gradlew :core:test --tests '*GoldenSetTest*'`.
 * Every case runs through the real rules (HiringRules, ReviewRules, Scoring, ResearchBatch, ContactBook).
 * No network, no LLM. "Surface" means: score of 45 or more, so the account is researched and listed for contact.
 */

// --- 1. Job titles: does the posting count as a mobile hiring signal (HiringRules.isMobile) -----------------------------

data class TitleCase(val title: String, val surface: Boolean, val why: String)

val titleCases = listOf(
    TitleCase("Développeur iOS Swift senior", true, "native iOS"),
    TitleCase("Ingénieur Android Kotlin", true, "native Android"),
    TitleCase("Lead Developer Mobile (Kotlin Multiplatform)", true, "mobile developer, KMP"),
    TitleCase("Développeur Flutter", false, "cross-platform: Doppio does not do Flutter"),
    TitleCase("React Native Engineer", false, "cross-platform: Doppio does not do React Native"),
    TitleCase("Conseiller service client mobile", false, "customer service, 'mobile' is the telecom line"),
    TitleCase("Analyste paiements SWIFT", false, "SWIFT is the bank messaging network"),
    TitleCase("Business developer mobile", false, "sells, does not build"),
)

// --- 2. App Store reviews: is the review a technical complaint (ReviewRules.isTechnicalComplaint) ----------------------

data class ReviewCase(val rating: Int, val title: String, val content: String, val surface: Boolean, val why: String)

val reviewCases = listOf(
    ReviewCase(1, "Plante au lancement", "Écran blanc depuis la mise à jour", true, "crash after an update"),
    ReviewCase(2, "Déçu", "L'app est lente et se ferme toute seule", true, "slowness and crashes"),
    ReviewCase(2, "Prix excellent mais pas pour moi", "", false, "'excellent' contains 'lent'"),
    ReviewCase(1, "Mode debug inutile", "", false, "'debug' contains 'bug'"),
    ReviewCase(5, "Plus aucun bug", "crash réglé", false, "5 stars: praise, not a complaint"),
)

// --- 3. Accounts: facts, signals and dossier through scoring, research selection and contact book ----------------------

/** A job posting the way a source reads it: title, text, age in days (null: no publication date, read today). */
data class Posting(val title: String, val text: String = "", val ageDays: Int? = 3)

/** One App Store review a few days old, and the store's overall average. */
data class StoreReview(val rating: Int, val title: String = "", val content: String = "", val ageDays: Int = 10)

data class AccountCase(
    val name: String,
    val surface: Boolean,
    /** Expected tier, or null when the case only cares about surfacing. */
    val tier: Tier?,
    val account: Account,
    val postings: List<Posting> = emptyList(),
    val reviews: List<StoreReview> = emptyList(),
    val overallRating: Double? = null,
    val publicTenderDaysAgo: Int? = null,
    val dossier: Dossier? = null,
    val why: String,
)

private fun acct(
    id: Long, name: String, sector: Sector = Sector.OTHER, headcount: Int? = 120, app: Boolean = true, warmPath: String? = null,
) = Account(
    id = id, name = name, domain = "${name.lowercase().filter { it.isLetter() }}.fr", sector = sector, market = Market.FR,
    headcount = headcount, iosAppId = if (app) 1000 + id else null, androidPackage = if (app) "fr.acct$id" else null, warmPath = warmPath,
)

private fun dossier(id: Long, noFit: Boolean) = Dossier(id, "Résumé", listOf("Hypothèse"), "Angle", Offer.DISCOVERY, noFit)

private val poorReviews = List(10) { StoreReview(1, "Plante", "L'app plante à chaque paiement", 5 + it) } +
    List(4) { StoreReview(4, "Correct") }

val accountCases = listOf(
    AccountCase(
        "Native iOS hiring, poor rating", surface = true, tier = Tier.ACT_NOW,
        account = acct(1, "VeloNative", Sector.MOBILITY_TRANSPORT),
        postings = listOf(Posting("Développeur iOS Swift", "SwiftUI, équipe de 6")),
        reviews = poorReviews, overallRating = 4.4,
        why = "the clearest positive: native hiring plus complaints and a rating drop",
    ),
    AccountCase(
        "Native iOS hiring, migration away from Flutter", surface = true, tier = Tier.ACT_NOW,
        account = acct(2, "SortieDeFlutter", Sector.MOBILITY_TRANSPORT),
        postings = listOf(Posting("Développeur iOS", "Nous migrons depuis Flutter vers SwiftUI", ageDays = 2)),
        reviews = poorReviews.take(6), overallRating = 3.9,
        why = "a posting that leaves Flutter is not evidence of a cross-platform stack",
    ),
    AccountCase(
        "Stale mobile offer (40 days)", surface = false, tier = Tier.WATCH,
        account = acct(3, "OffreFanee", Sector.MOBILITY_TRANSPORT),
        postings = listOf(Posting("Développeur Android Kotlin", ageDays = 40)),
        why = "the posting date dates the signal: after 40 days it has lost most of its weight",
    ),
    AccountCase(
        "Offer open for 120 days, hire stuck", surface = true, tier = Tier.NURTURE,
        account = acct(4, "RecrutementBloque", Sector.MOBILITY_TRANSPORT),
        postings = listOf(Posting("Développeur Android Kotlin", ageDays = 120)),
        why = "a posting open for more than 45 days says the hire is stuck: an offer for staff augmentation",
    ),
    AccountCase(
        "Flutter stack stated in a native posting", surface = false, tier = Tier.WATCH,
        account = acct(5, "BoutiqueFlutter", Sector.MOBILITY_TRANSPORT),
        postings = listOf(Posting("Développeur Android", "Notre application est écrite en Flutter, vous gérez les modules natifs")),
        why = "sourced evidence of a cross-platform stack takes 20 points of fit",
    ),
    AccountCase(
        "Flutter developer posting alone", surface = false, tier = Tier.WATCH,
        account = acct(6, "SeulementFlutter", Sector.MOBILITY_TRANSPORT),
        postings = listOf(Posting("Développeur Flutter", "Flutter, Dart")),
        why = "no mobile signal at all, fit alone stays under the threshold",
    ),
    AccountCase(
        "Customer service 'mobile' posting", surface = false, tier = Tier.WATCH,
        account = acct(7, "TelcoService", Sector.OTHER, headcount = 300),
        postings = listOf(Posting("Conseiller service client mobile")),
        why = "not a software role: no hiring signal",
    ),
    AccountCase(
        "SWIFT payments analyst at a bank", surface = false, tier = Tier.WATCH,
        account = acct(8, "BanqueDuNord", Sector.BANK_INSURANCE, headcount = 3000),
        postings = listOf(Posting("Analyste paiements SWIFT")),
        why = "SWIFT is the interbank network: without the rule this bank would reach 45",
    ),
    AccountCase(
        "ESN without product", surface = false, tier = Tier.WATCH,
        account = acct(9, "ServicesNumeriquesESN", Sector.PRODUCTIVITY_SAAS, headcount = 200, app = false),
        postings = listOf(Posting("Développeur iOS", "Mission chez nos clients")),
        why = "NAF 62 maps to PRODUCTIVITY_SAAS: no 'direct reference' bonus without a product of its own",
    ),
    AccountCase(
        "Software publisher with an app", surface = true, tier = Tier.NURTURE,
        account = acct(10, "EditeurAgenda", Sector.PRODUCTIVITY_SAAS, headcount = 200),
        postings = listOf(Posting("Développeur iOS", "Notre app")),
        why = "same sector as the ESN, but it ships its own app: the bonus holds",
    ),
    AccountCase(
        "High score but dossier says no fit", surface = false, tier = Tier.ACT_NOW,
        account = acct(11, "ClientSansBesoin", Sector.MOBILITY_TRANSPORT),
        postings = listOf(Posting("Développeur iOS Swift")),
        reviews = poorReviews, overallRating = 4.4,
        dossier = dossier(11, noFit = true),
        why = "the researcher found no offer to sell: the score is untouched, but not researched again and not in the contact book",
    ),
    AccountCase(
        "Same account, later dossier lifts the no fit", surface = true, tier = Tier.ACT_NOW,
        account = acct(12, "ClientRevu", Sector.MOBILITY_TRANSPORT),
        postings = listOf(Posting("Développeur iOS Swift")),
        reviews = poorReviews, overallRating = 4.4,
        dossier = dossier(12, noFit = false),
        why = "a dossier that no longer says no fit leaves the score untouched",
    ),
    AccountCase(
        "Open public tender", surface = true, tier = Tier.NURTURE,
        account = acct(13, "VilleDeDemain", Sector.OTHER, headcount = 800, app = false),
        publicTenderDaysAgo = 5,
        why = "a tender states the need outright",
    ),
    AccountCase(
        "Warm path and KMP hiring", surface = true, tier = Tier.NURTURE,
        account = acct(14, "AncienClientKMP", Sector.OTHER, headcount = 80, warmPath = "ancien client"),
        postings = listOf(Posting("Développeur Android", "Kotlin Multiplatform et Compose Multiplatform", ageDays = 10)),
        why = "a warm link and a KMP posting: the offer is the training",
    ),
    AccountCase(
        "Micro startup, no signal", surface = false, tier = Tier.WATCH,
        account = acct(15, "MicroStartup", Sector.OTHER, headcount = 5, app = false),
        why = "nothing to say, nothing to sell",
    ),
)

/** Relative order that must hold in the ranking: the first account scores strictly above the second. */
val orderCases = listOf(
    "Native iOS hiring, poor rating" to "Flutter stack stated in a native posting",
    "Native iOS hiring, poor rating" to "Stale mobile offer (40 days)",
    "Software publisher with an app" to "ESN without product",
    "Native iOS hiring, migration away from Flutter" to "Flutter stack stated in a native posting",
    "Offer open for 120 days, hire stuck" to "Stale mobile offer (40 days)",
)
