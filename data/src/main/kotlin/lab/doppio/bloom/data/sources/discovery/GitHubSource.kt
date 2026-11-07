package lab.doppio.bloom.data.sources.discovery

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import lab.doppio.bloom.core.discovery.CompanyNames
import lab.doppio.bloom.core.discovery.GitHub
import lab.doppio.bloom.core.model.FactKind
import lab.doppio.bloom.core.model.Signal
import lab.doppio.bloom.core.model.SignalType
import lab.doppio.bloom.core.model.SourceResult
import lab.doppio.bloom.core.model.fact
import lab.doppio.bloom.data.discovery.DiscoverySource
import lab.doppio.bloom.data.discovery.Lead
import lab.doppio.bloom.data.discovery.SeenKeys
import lab.doppio.bloom.data.sources.http.RateLimiter
import lab.doppio.bloom.data.sources.http.sourcesJson
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/** GitHub : organisations françaises qui publient du code Kotlin Multiplatform (API REST de recherche). */
/**
 * Only organisations are read, never individual developers: no contact is ever created from GitHub.
 * Without a token the API allows 10 searches per minute and 60 other calls per hour, hence [maxLookups].
 */
class GitHubSource(
    private val http: HttpClient,
    private val token: String?,
    private val wasSeen: suspend (String) -> Boolean,
    private val markSeen: suspend (String, Boolean, String) -> Unit,
    private val maxLookups: Int = MAX_LOOKUPS,
    clock: Clock = Clock.systemDefaultZone(),
) : DiscoverySource {
    override val key = KEY
    override val label = "GitHub"
    override val recheckAfterDays: Long = RECHECK_AFTER_DAYS
    private val limiter = RateLimiter(MIN_INTERVAL, clock)
    private val france = Regex("(?i)france|paris|lyon|nantes|bordeaux|toulouse|lille|marseille|rennes|montpellier|grenoble|strasbourg")

    private suspend fun api(path: String, query: String? = null): JsonObject = limiter.run {
        val res = http.get("https://api.github.com$path") {
            header("Accept", "application/vnd.github+json")
            token?.let { header("Authorization", "Bearer $it") }
            query?.let { parameter("q", it); parameter("sort", "updated"); parameter("per_page", PAGE_SIZE) }
        }
        if (!res.status.isSuccess()) error("GitHub HTTP ${res.status.value} sur $path")
        sourcesJson.parseToJsonElement(res.bodyAsText()).jsonObject
    }

    override suspend fun leads(now: Instant): List<Lead> {
        val since = now.atZone(ZoneOffset.UTC).toLocalDate().minusDays(PUSHED_WITHIN_DAYS)
        val repos = listOf("kotlin-multiplatform", "compose-multiplatform")
            .flatMap { topic -> GitHub.organisationRepos(api("/search/repositories", "topic:$topic pushed:>$since")) }
            .distinctBy { it.first }
        val leads = mutableListOf<Lead>()
        var lookups = 0
        for ((login, repoUrl) in repos) {
            val seenKey = SeenKeys.of(KEY, login.lowercase())
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
                        "${org.name ?: org.login} : dépôt public Kotlin Multiplatform mis à jour ces $PUSHED_WITHIN_DAYS derniers jours",
                        repoUrl,
                        excerpt = org.url,
                        at = at,
                    )
                    SourceResult(listOf(f), listOf(Signal(accountId, SignalType.KMP_ADOPTION, STRENGTH, at.toString(), listOf(f.id))))
                },
            )
        }
        return leads
    }

    companion object {
        const val KEY = "github"

        /** An organisation not worth a second look for a quarter. */
        const val RECHECK_AFTER_DAYS = 90L

        /** Lookups per run: without a token the API allows 60 calls per hour. */
        const val MAX_LOOKUPS = 25

        /** Repositories pushed within this many days count as active Kotlin Multiplatform work. */
        const val PUSHED_WITHIN_DAYS = 60L

        /** Search results per call, the maximum GitHub allows. */
        private const val PAGE_SIZE = 100

        /** Public code is a softer signal than a hire or a tender. */
        const val STRENGTH = 0.8

        /** 10 searches per minute without a token, so one call every 6 seconds, with a margin. */
        private val MIN_INTERVAL: Duration = Duration.ofSeconds(7)
    }
}
