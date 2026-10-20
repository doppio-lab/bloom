package lab.doppio.bloom.data.sources

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import lab.doppio.bloom.core.discovery.BoardRef
import lab.doppio.bloom.core.discovery.HiringRules
import lab.doppio.bloom.core.discovery.JobBoards
import lab.doppio.bloom.core.discovery.Posting
import lab.doppio.bloom.core.model.AccountId
import lab.doppio.bloom.core.model.Fact
import lab.doppio.bloom.core.model.FactKind
import lab.doppio.bloom.core.model.Signal
import lab.doppio.bloom.core.model.SignalType
import java.time.Duration
import java.time.Instant

/**
 * Hosted job boards (Greenhouse, Lever, Ashby). Their posting APIs are public and need no key, but
 * they are addressed by company, so this enriches accounts we already know instead of finding new ones.
 * What it adds over France Travail: the exact publication date, hence how long a mobile role has been open.
 */
class JobBoardSource(private val http: HttpClient) {
    private val limiter = RateLimiter(Duration.ofSeconds(1))

    private suspend fun fetch(url: String): String? = runCatching {
        limiter.run { http.get(url).takeIf { it.status.isSuccess() }?.bodyAsText() }
    }.getOrNull()

    suspend fun postings(ref: BoardRef): List<Posting> {
        val body = fetch(JobBoards.apiUrl(ref)) ?: return emptyList()
        return runCatching { JobBoards.parse(ref.provider, sourcesJson.parseToJsonElement(body)) }.getOrDefault(emptyList())
    }

    /**
     * Looks for the board of a company through a link on its own site. Guessing a board name from the
     * domain would be faster but could attach another company's postings to the account, so it is not done.
     */
    suspend fun find(domain: String): BoardRef? {
        listOf("https://$domain", "https://$domain/careers", "https://$domain/jobs", "https://$domain/recrutement").forEach { page ->
            fetch(page)?.let { JobBoards.detect(it) }?.let { return it }
        }
        return null
    }

    /** Facts and signals from the mobile postings of a board. Same rules as the France Travail offers. */
    fun result(accountId: AccountId, postings: List<Posting>, now: Instant = Instant.now(), max: Int = 5): SourceResult {
        val facts = mutableListOf<Fact>()
        val signals = mutableListOf<Signal>()
        postings.filter { HiringRules.isMobile(it.title) }.take(max).forEach { p ->
            // A date is stated only when the board gives the first publication date. The date, not the age:
            // the id derives from the statement, so an age would mint a new fact every night
            val published = p.publishedAt?.takeIf { p.dateIsFirstPublication }
            val age = published?.let { Duration.between(it, now).toDays() }
            val f = fact(
                accountId, FactKind.JOB_POSTING,
                "Offre d'emploi « ${p.title} »" + (published?.let { " publiée le ${publishedOn(it)}" } ?: ""),
                p.url,
                excerpt = p.location,
                now = now,
            )
            facts += f
            signals += Signal(accountId, SignalType.HIRING_MOBILE, 0.7, now.toString(), listOf(f.id))
            if (HiringRules.mentionsKmp(p.title)) signals += Signal(accountId, SignalType.HIRING_KMP, 0.9, now.toString(), listOf(f.id))
            HiringRules.stuckStrength(age)?.let { signals += Signal(accountId, SignalType.HIRING_STUCK, it, now.toString(), listOf(f.id)) }
        }
        return SourceResult(facts, signals)
    }
}
