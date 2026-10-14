package lab.doppio.bloom.data.sources

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import lab.doppio.bloom.core.discovery.Boamp
import lab.doppio.bloom.core.discovery.Bodacc
import lab.doppio.bloom.core.discovery.Codeur
import lab.doppio.bloom.core.discovery.CodeurProject
import lab.doppio.bloom.core.discovery.CompanyNames
import lab.doppio.bloom.core.discovery.GitHub
import lab.doppio.bloom.core.discovery.Press
import lab.doppio.bloom.core.discovery.Ted
import lab.doppio.bloom.core.discovery.Tender
import lab.doppio.bloom.core.model.FactKind
import lab.doppio.bloom.core.model.Signal
import lab.doppio.bloom.core.model.SignalType
import lab.doppio.bloom.data.discovery.DiscoverySource
import lab.doppio.bloom.data.discovery.Lead
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Public sources added to discovery. Each one is free, needs no scraping and returns leads whose
 * facts are built by code with the URL of the record they come from.
 */

private val frDate: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
private val paris: ZoneId = ZoneId.of("Europe/Paris")
private fun day(iso: String): String = runCatching { LocalDate.parse(iso.take(10)).format(frDate) }.getOrDefault(iso)
private fun startOfDay(iso: String, fallback: Instant): Instant =
    runCatching { LocalDate.parse(iso.take(10)).atStartOfDay(ZoneOffset.UTC).toInstant() }.getOrDefault(fallback)
private fun euros(amount: Long): String = "%,d".format(amount).replace(',', ' ') + " €"

private suspend fun HttpClient.records(url: String, where: String, limit: Int, offset: Int = 0): List<JsonObject> {
    val res = get(url) {
        parameter("where", where)
        parameter("order_by", "dateparution desc")
        parameter("limit", limit)
        parameter("offset", offset)
    }
    if (!res.status.isSuccess()) error("HTTP ${res.status.value} sur $url")
    val body = sourcesJson.parseToJsonElement(res.bodyAsText()).jsonObject
    return (body["results"] as? JsonArray).orEmpty().map { it.jsonObject }
}

// ---------------------------------------------------------------------------------------------
// BODACC : annonces légales, augmentations de capital (open data, sans clé)
// ---------------------------------------------------------------------------------------------

class BodaccSource(
    private val http: HttpClient,
    private val minCapitalEur: Long = 100_000,
    private val lookbackDays: Long = 7,
    private val maxPages: Int = 5,
) : DiscoverySource {
    override val key = "bodacc"
    override val label = "BODACC"
    private val limiter = RateLimiter(Duration.ofMillis(500))

    override suspend fun leads(now: Instant): List<Lead> {
        val since = now.atZone(paris).toLocalDate().minusDays(lookbackDays)
        val where = "familleavis=\"modification\" and search(modificationsgenerales,\"augmentation\") and dateparution>=date'$since'"
        val notices = buildList {
            for (page in 0 until maxPages) {
                val batch = limiter.run { http.records(URL, where, limit = 100, offset = page * 100) }
                addAll(batch.mapNotNull { Bodacc.parse(it) })
                if (batch.size < 100) break
            }
        }
        return notices.filter { Bodacc.isCandidate(it, minCapitalEur) }.map { n ->
            Lead(
                name = n.name,
                seenKey = "bodacc:${n.id}",
                siren = n.siren,
                minHeadcount = 10,
                requireSectorOrApp = true,
                build = { accountId, at ->
                    // The notice gives the capital after the change, never the amount raised: the fact says exactly that
                    val f = fact(
                        accountId, FactKind.LEGAL_NOTICE,
                        "${n.name} : augmentation de capital publiée au BODACC le ${day(n.publishedOn)}, capital porté à ${euros(n.capitalEur ?: 0)}",
                        n.url,
                        excerpt = n.description,
                    )
                    val strength = ((n.capitalEur ?: 0) / 1_000_000.0).coerceIn(0.4, 1.0)
                    SourceResult(listOf(f), listOf(Signal(accountId, SignalType.FUNDING, strength, startOfDay(n.publishedOn, at).toString(), listOf(f.id))))
                },
            )
        }
    }

    private companion object {
        const val URL = "https://bodacc-datadila.opendatasoft.com/api/explore/v2.1/catalog/datasets/annonces-commerciales/records"
    }
}

// ---------------------------------------------------------------------------------------------
// BOAMP : appels d'offres publics qui demandent une application mobile (open data, sans clé)
// ---------------------------------------------------------------------------------------------

class BoampSource(private val http: HttpClient, private val lookbackDays: Long = 45) : DiscoverySource {
    override val key = "boamp"
    override val label = "marché public"

    override suspend fun leads(now: Instant): List<Lead> {
        val since = now.atZone(paris).toLocalDate().minusDays(lookbackDays)
        val where = "(objet like \"%application mobile%\" or objet like \"%appli mobile%\" or objet like \"%application smartphone%\") and dateparution>=date'$since'"
        return http.records(URL, where, limit = 100).mapNotNull { Boamp.parse(it) }
            .filter { Boamp.isOpenMobileTender(it, now) }
            .map { tenderLead(it, "boamp") }
    }

    private companion object {
        const val URL = "https://boamp-datadila.opendatasoft.com/api/explore/v2.1/catalog/datasets/boamp/records"
    }
}

/** A tender becomes a lead on its buyer. Shared by BOAMP and TED, which publish the same kind of notice. */
private fun tenderLead(t: Tender, prefix: String) = Lead(
    name = t.buyer,
    seenKey = "$prefix:${t.id}",
    // Town halls and public bodies are not always matched by name in the registry
    requireRegistry = false,
    build = { accountId, at ->
        val deadline = t.deadline?.minusSeconds(1)?.atZone(paris)?.toLocalDate()?.format(frDate)
        val f = fact(
            accountId, FactKind.PUBLIC_TENDER,
            "${t.buyer} : appel d'offres publié le ${day(t.publishedOn)}" + (deadline?.let { ", réponse avant le $it" } ?: ""),
            t.url,
            excerpt = t.subject,
        )
        // Observed now, not at publication: the tender was checked as still open, so its weight should not have decayed already
        SourceResult(listOf(f), listOf(Signal(accountId, SignalType.PUBLIC_TENDER, 1.0, at.toString(), listOf(f.id))))
    },
)

// ---------------------------------------------------------------------------------------------
// TED : marchés publics européens passés en France (API de recherche ouverte, sans clé)
// ---------------------------------------------------------------------------------------------

class TedSource(private val http: HttpClient, private val lookbackDays: Long = 60) : DiscoverySource {
    override val key = "ted"
    override val label = "marché européen"

    override suspend fun leads(now: Instant): List<Lead> {
        val since = now.atZone(paris).toLocalDate().minusDays(lookbackDays)
        val res = http.post("https://api.ted.europa.eu/v3/notices/search") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("query", Ted.query(since))
                put("fields", JsonArray(Ted.fields.map { JsonPrimitive(it) }))
                put("limit", 100)
                put("scope", "ACTIVE")
                put("paginationMode", "PAGE_NUMBER")
                put("page", 1)
            })
        }
        if (!res.status.isSuccess()) error("TED HTTP ${res.status.value}")
        val notices = (sourcesJson.parseToJsonElement(res.bodyAsText()).jsonObject["notices"] as? JsonArray).orEmpty()
        // The search is full text: only the notices whose subject asks for a mobile app are kept
        return notices.mapNotNull { Ted.parse(it.jsonObject) }.filter { Boamp.isOpenMobileTender(it, now) }.map { tenderLead(it, "ted") }
    }
}

// ---------------------------------------------------------------------------------------------
// Codeur.com : projets publiés par des entreprises (flux RSS public). Pas d'entreprise nommée :
// ce ne sont pas des comptes à prospecter mais des demandes auxquelles on répond sur la plateforme.
// ---------------------------------------------------------------------------------------------

class CodeurSource(private val http: HttpClient, private val minBudgetEur: Int = 1_000) {
    /** The plain feed only: the site's robots.txt disallows query strings, so the filter runs here. */
    suspend fun mobileProjects(): List<CodeurProject> {
        val res = http.get("https://www.codeur.com/projects.rss")
        if (!res.status.isSuccess()) error("Codeur HTTP ${res.status.value}")
        return Codeur.parseFeed(res.bodyAsText()).filter { Codeur.isMobileProject(it, minBudgetEur) }
    }
}

// ---------------------------------------------------------------------------------------------
// Presse : titres qui annoncent une levée de fonds (flux RSS publics). Seuls le titre et le lien sont repris.
// ---------------------------------------------------------------------------------------------

class PressSource(
    private val http: HttpClient,
    private val feeds: List<String> = listOf(
        "https://www.frenchweb.fr/feed", "https://www.maddyness.com/feed/", "https://www.journaldunet.com/rss/",
        "https://www.usine-digitale.fr/rss", "https://siecledigital.fr/feed/",
    ),
    private val maxAgeDays: Long = 14,
) : DiscoverySource {
    override val key = "press"
    override val label = "presse"

    override suspend fun leads(now: Instant): List<Lead> {
        // One feed down does not hide the others: the source fails only when none of them answers
        val read = feeds.map { feed ->
            runCatching {
                val res = http.get(feed)
                if (!res.status.isSuccess()) error("HTTP ${res.status.value} sur $feed")
                Press.parseFeed(res.bodyAsText())
            }
        }
        if (read.all { it.isFailure }) error(read.first().exceptionOrNull()?.message ?: "aucun flux lisible")
        val items = read.flatMap { it.getOrDefault(emptyList()) }
        return items
            .filter { it.publishedAt == null || Duration.between(it.publishedAt, now).toDays() <= maxAgeDays }
            .mapNotNull { item -> Press.funding(item.title)?.let { item to it } }
            .map { (item, round) ->
                Lead(
                    name = round.company,
                    seenKey = "press:${FactIds.of(item.link, round.company)}",
                    // A headline gives a trade name only: two companies with that name means we cannot tell which one
                    requireUniqueMatch = true,
                    build = { accountId, at ->
                        val published = item.publishedAt ?: at
                        val f = fact(
                            accountId, FactKind.PRESS,
                            "${round.company} : levée de ${round.amount} annoncée le ${published.atZone(paris).toLocalDate().format(frDate)}",
                            item.link,
                            excerpt = item.title,
                        )
                        SourceResult(listOf(f), listOf(Signal(accountId, SignalType.FUNDING, 0.8, published.toString(), listOf(f.id))))
                    },
                )
            }
    }
}

// ---------------------------------------------------------------------------------------------
// GitHub : organisations françaises qui publient du code Kotlin Multiplatform (API REST de recherche)
// ---------------------------------------------------------------------------------------------

/**
 * Only organisations are read, never individual developers: no contact is ever created from GitHub.
 * Without a token the API allows 10 searches per minute and 60 other calls per hour, hence [maxLookups].
 */
class GitHubSource(
    private val http: HttpClient,
    private val token: String?,
    private val wasSeen: suspend (String) -> Boolean,
    private val markSeen: suspend (String, Boolean, String) -> Unit,
    private val maxLookups: Int = 25,
) : DiscoverySource {
    override val key = "github"
    override val label = "GitHub"
    override val recheckAfterDays: Long = 90
    private val limiter = RateLimiter(Duration.ofSeconds(7))
    private val france = Regex("(?i)france|paris|lyon|nantes|bordeaux|toulouse|lille|marseille|rennes|montpellier|grenoble|strasbourg")

    private suspend fun api(path: String, query: String? = null): JsonObject = limiter.run {
        val res = http.get("https://api.github.com$path") {
            header("Accept", "application/vnd.github+json")
            token?.let { header("Authorization", "Bearer $it") }
            query?.let { parameter("q", it); parameter("sort", "updated"); parameter("per_page", 100) }
        }
        if (!res.status.isSuccess()) error("GitHub HTTP ${res.status.value} sur $path")
        sourcesJson.parseToJsonElement(res.bodyAsText()).jsonObject
    }

    override suspend fun leads(now: Instant): List<Lead> {
        val since = now.atZone(ZoneOffset.UTC).toLocalDate().minusDays(60)
        val repos = listOf("kotlin-multiplatform", "compose-multiplatform")
            .flatMap { topic -> GitHub.organisationRepos(api("/search/repositories", "topic:$topic pushed:>$since")) }
            .distinctBy { it.first }
        val leads = mutableListOf<Lead>()
        var lookups = 0
        for ((login, repoUrl) in repos) {
            val seenKey = "github:${login.lowercase()}"
            if (wasSeen(seenKey)) continue
            if (lookups++ >= maxLookups) break
            val org = GitHub.parseOrg(api("/orgs/$login"))
            // Organisations outside France, or without a website to tie them to a company, are remembered and skipped
            if (org == null || org.website == null || org.location?.let { france.containsMatchIn(it) } != true) {
                markSeen(seenKey, false, "organisation hors France ou sans site")
                continue
            }
            leads += Lead(
                name = org.name ?: org.login,
                seenKey = seenKey,
                domain = CompanyNames.domainOf(org.website),
                requireUniqueMatch = true,
                build = { accountId, at ->
                    val f = fact(
                        accountId, FactKind.CODE_REPOSITORY,
                        "${org.name ?: org.login} : dépôt public Kotlin Multiplatform mis à jour ces 60 derniers jours",
                        repoUrl,
                        excerpt = org.url,
                    )
                    SourceResult(listOf(f), listOf(Signal(accountId, SignalType.KMP_ADOPTION, 0.8, at.toString(), listOf(f.id))))
                },
            )
        }
        return leads
    }
}
