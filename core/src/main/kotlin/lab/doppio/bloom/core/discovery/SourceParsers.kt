package lab.doppio.bloom.core.discovery

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import lab.doppio.bloom.core.model.AppReview
import lab.doppio.bloom.core.model.Fact
import lab.doppio.bloom.core.model.FactKind
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * Pure parsers for the public sources read by discovery. No I/O here: each function takes the raw
 * response of a source and returns typed values, so it can be tested on recorded responses.
 */

private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }

private fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
private fun JsonElement?.obj(): JsonObject? = this as? JsonObject

/** Some open data fields hold a JSON document serialized as a string. */
private fun JsonElement?.embedded(): JsonObject? = when (this) {
    is JsonObject -> this
    is JsonPrimitive -> contentOrNull?.let { runCatching { lenient.parseToJsonElement(it).obj() }.getOrNull() }
    else -> null
}

private fun instant(value: String?): Instant? = value?.let {
    runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() ?: runCatching { Instant.parse(it) }.getOrNull()
}

// --- BODACC: legal notices, capital increases ---------------------------------------------------

data class CapitalNotice(
    val id: String,
    val siren: String,
    val name: String,
    val legalForm: String?,
    /** The capital after the change. The notice does not give the amount raised. */
    val capitalEur: Long?,
    val publishedOn: String,
    val description: String,
    val url: String,
)

object Bodacc {
    private val nonTargetForms = Regex("(?i)soci[ée]t[ée] civile|agricole|groupement|\\bsci\\b|\\bscea\\b|\\bgaec\\b|\\bearl\\b")

    fun parse(record: JsonObject): CapitalNotice? {
        val id = record["id"].text() ?: return null
        val siren = (record["registre"] as? JsonArray)?.firstNotNullOfOrNull { it.text()?.filter(Char::isDigit)?.takeIf { d -> d.length == 9 } } ?: return null
        val people = record["listepersonnes"].embedded()?.get("personne")
        val person = people.obj() ?: (people as? JsonArray)?.firstOrNull().obj()
        return CapitalNotice(
            id = id,
            siren = siren,
            name = record["commercant"].text() ?: person?.get("denomination").text() ?: return null,
            legalForm = person?.get("formeJuridique").text(),
            capitalEur = person?.get("capital").obj()?.get("montantCapital").text()?.substringBefore('.')?.filter(Char::isDigit)?.toLongOrNull(),
            publishedOn = record["dateparution"].text() ?: return null,
            description = record["modificationsgenerales"].embedded()?.get("descriptif").text().orEmpty(),
            url = record["url_complete"].text() ?: "https://www.bodacc.fr/pages/annonces-commerciales-detail/?q.id=id:$id",
        )
    }

    /** Cheap filter applied before any lookup: most capital increases come from holdings, farms and property companies. */
    fun isCandidate(notice: CapitalNotice, minCapitalEur: Long): Boolean =
        notice.description.contains("augmentation", ignoreCase = true) &&
            (notice.capitalEur ?: 0) >= minCapitalEur &&
            notice.legalForm?.let { nonTargetForms.containsMatchIn(it) } != true
}

// --- BOAMP: public tenders ------------------------------------------------------------------------

data class Tender(
    val id: String,
    val subject: String,
    val buyer: String,
    val publishedOn: String,
    val deadline: Instant?,
    val nature: String?,
    val url: String,
)

object Boamp {
    private val mobileApp = Regex("(?i)\\bappli(cations?)?\\b.{0,25}\\b(mobiles?|smartphones?|ios|android)\\b|\\bd[ée]veloppement d'une appli")

    fun parse(record: JsonObject): Tender? {
        val id = record["idweb"].text() ?: return null
        return Tender(
            id = id,
            subject = record["objet"].text() ?: return null,
            buyer = record["nomacheteur"].text() ?: return null,
            publishedOn = record["dateparution"].text() ?: return null,
            deadline = instant(record["datelimitereponse"].text()),
            nature = record["nature"].text(),
            url = record["url_avis"].text() ?: "https://www.boamp.fr/pages/avis/?q=idweb:$id",
        )
    }

    /** A call for tenders (not an award notice) still open, whose subject asks for a mobile app. */
    fun isOpenMobileTender(tender: Tender, now: Instant): Boolean =
        tender.nature == "APPEL_OFFRE" && tender.deadline?.isAfter(now) == true && mobileApp.containsMatchIn(tender.subject)
}

// --- DECP: public contracts already awarded ---------------------------------------------------------

data class AwardedContract(
    val id: String,
    val subject: String,
    /** SIRET of the buyer. The records carry no buyer name. */
    val buyerSiret: String,
    val holderSiret: String?,
    val notifiedOn: LocalDate,
    val durationMonths: Int,
    val amountEur: Long?,
) {
    val buyerSiren: String get() = buyerSiret.take(9)
    val endsOn: LocalDate get() = notifiedOn.plusMonths(durationMonths.toLong())
}

object Decp {
    private val mobileApp = Regex("(?i)\\b(appli(cations?)?|apps?)\\b.{0,25}\\b(mobiles?|smartphones?|ios|android)\\b")

    fun parse(record: JsonObject): AwardedContract? {
        val buyer = record["acheteur_id"].text()?.filter(Char::isDigit)?.takeIf { it.length == 14 } ?: return null
        return AwardedContract(
            id = record["id"].text() ?: return null,
            subject = record["objet"].text() ?: return null,
            buyerSiret = buyer,
            holderSiret = record["titulaire_id_1"].text()?.filter(Char::isDigit)?.takeIf { it.length == 14 },
            notifiedOn = record["datenotification"].text()?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() } ?: return null,
            durationMonths = record["dureemois"].text()?.toDoubleOrNull()?.toInt()?.takeIf { it > 0 } ?: return null,
            amountEur = record["montant"].text()?.toDoubleOrNull()?.toLong(),
        )
    }

    /**
     * A mobile app contract that ends between [minMonths] and [maxMonths] from [today]: the buyer has to
     * renew it, and usually prepares the new tender a few months before the end.
     */
    fun isRenewalCandidate(contract: AwardedContract, today: LocalDate, minMonths: Long = 1, maxMonths: Long = 12): Boolean =
        mobileApp.containsMatchIn(contract.subject) &&
            !contract.endsOn.isBefore(today.plusMonths(minMonths)) &&
            !contract.endsOn.isAfter(today.plusMonths(maxMonths))
}

// --- Press: funding headlines from RSS feeds --------------------------------------------------------

data class PressItem(val title: String, val link: String, val publishedAt: Instant?)

data class FundingHeadline(val company: String, val amount: String)

object Press {
    private val amount = "(\\d+(?:[.,]\\d+)?)\\s*(millions?|milliards?)\\s+d['’]euros"
    private val raises = Regex("^(.{2,40}?)\\s+(?:lève|boucle|sécurise|réalise)\\b.{0,60}?$amount", RegexOption.IGNORE_CASE)
    private val colonThenRaises = Regex("^(.{2,40}?)\\s*:\\s.*?\\blève\\b.{0,40}?$amount", RegexOption.IGNORE_CASE)

    fun parseFeed(xml: String): List<PressItem> = Rss.items(xml).map { PressItem(it.title, it.link, it.publishedAt) }

    /** Company and amount when the headline announces a funding round, null otherwise. Deterministic: headline only. */
    fun funding(title: String): FundingHeadline? {
        val m = raises.find(title) ?: colonThenRaises.find(title) ?: return null
        val company = m.groupValues[1].trim().trim('«', '»', '"', ' ')
        // "Les startups françaises ont levé..." style headlines are round-ups, not a company
        if (company.isEmpty() || company.first().isLowerCase() || company.split(' ').size > 4) return null
        return FundingHeadline(company, "${m.groupValues[2]} ${m.groupValues[3].lowercase()} d'euros")
    }
}

// --- GitHub: organisations --------------------------------------------------------------------------

data class GitHubOrg(val login: String, val name: String?, val website: String?, val location: String?, val url: String)

object GitHub {
    /** Logins from a `search/users` response. */
    fun logins(search: JsonObject): List<String> =
        (search["items"] as? JsonArray).orEmpty().mapNotNull { it.obj()?.get("login").text() }

    /** Owners of type Organization from a `search/repositories` response, with the repository that matched. */
    fun organisationRepos(search: JsonObject): List<Pair<String, String>> =
        (search["items"] as? JsonArray).orEmpty().mapNotNull { item ->
            val repo = item.obj() ?: return@mapNotNull null
            val owner = repo["owner"].obj()?.takeIf { it["type"].text() == "Organization" } ?: return@mapNotNull null
            (owner["login"].text() ?: return@mapNotNull null) to (repo["html_url"].text() ?: return@mapNotNull null)
        }

    fun parseOrg(org: JsonObject): GitHubOrg? {
        val login = org["login"].text() ?: return null
        return GitHubOrg(
            login = login,
            name = org["name"].text(),
            website = org["blog"].text()?.let { if (it.startsWith("http")) it else "https://$it" },
            location = org["location"].text(),
            url = org["html_url"].text() ?: "https://github.com/$login",
        )
    }
}

// --- Job boards: Greenhouse, Lever, Ashby -----------------------------------------------------------

enum class BoardProvider { GREENHOUSE, LEVER, ASHBY, SMARTRECRUITERS }

data class BoardRef(val provider: BoardProvider, val token: String)

data class Posting(
    val title: String,
    val url: String,
    val publishedAt: Instant?,
    val location: String?,
    /** False when the date can move after the first publication (Ashby republish, Greenhouse update). */
    val dateIsFirstPublication: Boolean,
)

object JobBoards {
    private val links = listOf(
        BoardProvider.GREENHOUSE to Regex("(?:boards|job-boards)(?:\\.eu)?\\.greenhouse\\.io/(?:embed/job_board\\?for=)?([A-Za-z0-9_-]+)"),
        BoardProvider.LEVER to Regex("jobs(?:\\.eu)?\\.lever\\.co/([A-Za-z0-9_-]+)"),
        BoardProvider.ASHBY to Regex("jobs\\.ashbyhq\\.com/([A-Za-z0-9_.%-]+)"),
        BoardProvider.SMARTRECRUITERS to Regex("(?:jobs|careers)\\.smartrecruiters\\.com/([A-Za-z0-9_-]+)"),
    )

    /** Finds a link to a hosted job board in the HTML of a company page. */
    fun detect(html: String): BoardRef? = links.firstNotNullOfOrNull { (provider, regex) ->
        regex.find(html)?.groupValues?.get(1)?.takeIf { it.length > 1 }?.let { BoardRef(provider, it) }
    }

    fun apiUrl(ref: BoardRef): String = when (ref.provider) {
        BoardProvider.GREENHOUSE -> "https://boards-api.greenhouse.io/v1/boards/${ref.token}/jobs"
        BoardProvider.LEVER -> "https://api.lever.co/v0/postings/${ref.token}?mode=json"
        BoardProvider.ASHBY -> "https://api.ashbyhq.com/posting-api/job-board/${ref.token}"
        BoardProvider.SMARTRECRUITERS -> "https://api.smartrecruiters.com/v1/companies/${ref.token}/postings?limit=100"
    }

    fun parse(provider: BoardProvider, body: JsonElement): List<Posting> = when (provider) {
        BoardProvider.GREENHOUSE -> (body.obj()?.get("jobs") as? JsonArray).orEmpty().mapNotNull { it.obj() }.mapNotNull { j ->
            val first = instant(j["first_published"].text())
            Posting(
                title = j["title"].text() ?: return@mapNotNull null,
                url = j["absolute_url"].text() ?: return@mapNotNull null,
                publishedAt = first ?: instant(j["updated_at"].text()),
                location = j["location"].obj()?.get("name").text(),
                dateIsFirstPublication = first != null,
            )
        }
        BoardProvider.LEVER -> (body as? JsonArray).orEmpty().mapNotNull { it.obj() }.mapNotNull { j ->
            Posting(
                title = j["text"].text() ?: return@mapNotNull null,
                url = j["hostedUrl"].text() ?: return@mapNotNull null,
                publishedAt = j["createdAt"].text()?.toLongOrNull()?.let(Instant::ofEpochMilli),
                location = j["categories"].obj()?.get("location").text(),
                dateIsFirstPublication = true,
            )
        }
        BoardProvider.ASHBY -> (body.obj()?.get("jobs") as? JsonArray).orEmpty().mapNotNull { it.obj() }.mapNotNull { j ->
            Posting(
                title = j["title"].text() ?: return@mapNotNull null,
                url = j["jobUrl"].text() ?: return@mapNotNull null,
                publishedAt = instant(j["publishedAt"].text()),
                location = j["location"].text(),
                dateIsFirstPublication = false,
            )
        }
        BoardProvider.SMARTRECRUITERS -> (body.obj()?.get("content") as? JsonArray).orEmpty().mapNotNull { it.obj() }.mapNotNull { j ->
            val id = j["id"].text() ?: return@mapNotNull null
            val company = j["company"].obj()?.get("identifier").text() ?: return@mapNotNull null
            Posting(
                title = j["name"].text() ?: return@mapNotNull null,
                url = "https://jobs.smartrecruiters.com/$company/$id",
                publishedAt = instant(j["releasedDate"].text()),
                location = j["location"].obj()?.get("fullLocation").text(),
                // The release date moves when a posting is published again
                dateIsFirstPublication = false,
            )
        }
    }
}

// --- Company registry: legal officers -----------------------------------------------------------------

data class Officer(val fullName: String, val role: String)

object Registry {
    /** People who run the company. Board members, supervisory boards and auditors are listed too, and are left out. */
    private val executive = Regex("(?i)pr[ée]sident|directeur g[ée]n[ée]ral|g[ée]rant|dirigeant")
    private val notExecutive = Regex("(?i)commissaire|conseil de surveillance")

    /**
     * Natural persons who run the company, from a record of the company registry. The date of birth the
     * registry publishes is not kept: it is not needed to write to someone.
     */
    fun officers(company: JsonObject): List<Officer> = (company["dirigeants"] as? JsonArray).orEmpty().mapNotNull { it.obj() }.mapNotNull { d ->
        if (d["type_dirigeant"].text() != "personne physique") return@mapNotNull null
        val role = d["qualite"].text()?.takeIf { executive.containsMatchIn(it) && !notExecutive.containsMatchIn(it) } ?: return@mapNotNull null
        val first = d["prenoms"].text()?.split(' ', ',')?.firstOrNull { it.isNotBlank() } ?: return@mapNotNull null
        // "SOUAL (DELEVALLEE)": the name in brackets is a birth name, the first one is the name in use
        val last = d["nom"].text()?.substringBefore('(')?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        Officer("${capitalise(first)} ${capitalise(last)}", role)
    }.distinct()

    /** The registry writes names in capitals: "JEAN-PIERRE" becomes "Jean-Pierre". */
    private fun capitalise(name: String): String =
        name.lowercase().split(' ').joinToString(" ") { word -> word.split('-').joinToString("-") { it.replaceFirstChar(Char::uppercase) } }
}

/** Rules shared by every source of job postings (France Travail, hosted job boards). */
object HiringRules {
    // Native mobile stacks are enough on their own. Cross-platform stacks are not: Doppio builds native
    // (and Kotlin Multiplatform) apps, so a Flutter or React Native opening is not a need it can serve.
    private val nativeTitle = Regex("(?i)\\b(ios|android|swiftui|kotlin)\\b")
    // "Swift" is also the bank messaging network and "mobile" also names telecom sales or heavy machinery:
    // both count only next to a word that says the role builds software.
    private val ambiguousTitle = Regex("(?i)\\b(swift|mobiles?)\\b")
    private val developerWord = Regex("(?i)d[ée]velopp|developer|\\bdev\\b|engineer|ing[ée]nieur|architect|\\blead\\b|\\bsoftware\\b|\\bapps?\\b|\\bapplications?\\b")
    // "Business developer" and "développeur commercial" sell, they do not build
    private val salesRole = Regex("(?i)business develop|d[ée]veloppeu(r|se) (commercial|d'affaires)|sales|commercial")
    private val kmp = Regex("(?i)(kotlin multiplatform|compose multiplatform|\\bkmp\\b|\\bcmp\\b)")
    private val crossPlatform = Regex("(?i)(flutter|react native)")

    fun isMobile(title: String): Boolean =
        nativeTitle.containsMatchIn(title) || (ambiguousTitle.containsMatchIn(title) && developerWord.containsMatchIn(title) && !salesRole.containsMatchIn(title))
    fun mentionsKmp(text: String): Boolean = kmp.containsMatchIn(text)
    fun mentionsCrossPlatform(text: String): Boolean = crossPlatform.containsMatchIn(text)

    // "Migration depuis Flutter", "replace React Native": the posting is about leaving the stack, which is good news
    private val leavingCrossPlatform = Regex("(?i)(migr|replac|remplac|abandon|depuis|from|quitt|sortir de|away from)\\p{L}*[^.]{0,40}(flutter|react native)")

    /** True when the text says the company builds on a cross-platform stack, not when it only quotes one to leave it. */
    fun indicatesCrossPlatformStack(text: String): Boolean = mentionsCrossPlatform(text) && !leavingCrossPlatform.containsMatchIn(text)

    /** Marker shared by the statement a source writes and the check [isCrossPlatformEvidence] makes on it. */
    private const val CROSS_PLATFORM_MARK = "stack cross-platform"

    fun crossPlatformStatement(title: String): String = "L'offre « $title » mentionne une $CROSS_PLATFORM_MARK (Flutter ou React Native)"

    /** A sourced fact that states a cross-platform stack: the only evidence scoring accepts for it. */
    fun isCrossPlatformEvidence(fact: Fact): Boolean = fact.kind == FactKind.JOB_POSTING && CROSS_PLATFORM_MARK in fact.statement

    /** When a hiring signal is dated: the publication day when the source gives it (never in the future), else the read time. */
    fun observedAt(publishedAt: Instant?, now: Instant): Instant = publishedAt?.takeIf { !it.isAfter(now) } ?: now

    /** A posting open for more than 45 days: the hire is stuck. Null below that. */
    fun stuckStrength(ageDays: Long?): Double? = ageDays?.takeIf { it > 45 }?.let { ((it - 45) / 60.0).coerceIn(0.3, 1.0) }
}

/** Reads app reviews: which low ratings describe a technical problem Doppio could audit. */
object ReviewRules {
    /**
     * What a low-rated review complains about, by keyword. [label] completes "N avis parlent de ...".
     * Whole words only: a plain substring test matched "lent" in "excellent" and "bug" in "debug".
     */
    enum class Theme(val label: String, words: String) {
        CRASH("plantages", "crash\\p{L}*|plante\\p{L}*|freeze|fige|figé|se ferme|écran blanc"),
        BUG("bugs", "bugs?|bugu\\p{L}*|beug\\p{L}*|erreurs?"),
        SLOW("lenteurs", "lent|lente|lents|lentes|lenteur|slow|chargement|charge non stop"),
        LOGIN("problèmes de connexion", "connexion|connecter|connect|reconnect\\p{L}*|déconnect\\p{L}*|login"),
        BROKEN(
            "fonctions inutilisables",
            "ne fonctionne (?:plus|pas)|ne marche (?:plus|pas)|fonctionne pas|marche pas|dysfonction\\p{L}*|inutilisable\\p{L}*|impossible|doesn't work",
        ),
        SYNC("la synchronisation", "synchronis\\p{L}*|sync"),
        UPDATE("la mise à jour", "mise à jour"),
        BATTERY("la batterie", "batterie"),
        ;

        internal val regex = Regex("(?<![\\p{L}\\p{N}])($words)(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
    }

    /** The themes a review mentions, whatever its rating. */
    fun themes(title: String, content: String): Set<Theme> =
        "$title $content".let { text -> Theme.entries.filterTo(mutableSetOf()) { it.regex.containsMatchIn(text) } }

    fun isTechnicalComplaint(rating: Int, title: String, content: String): Boolean =
        rating <= 2 && themes(title, content).isNotEmpty()

    /**
     * The low-rated reviews worth quoting to the company: the ones that name a technical problem first, then the
     * most recent. A rating with no text says nothing and is left out.
     */
    fun mostTelling(reviews: List<AppReview>, limit: Int = 5): List<AppReview> =
        reviews.filter { it.rating <= 2 && it.content.isNotBlank() }
            .sortedWith(
                compareByDescending<AppReview> { themes(it.title, it.content).isNotEmpty() }
                    .thenByDescending { it.updated ?: Instant.MIN },
            )
            .take(limit)
}
