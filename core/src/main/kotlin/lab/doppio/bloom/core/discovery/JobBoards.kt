package lab.doppio.bloom.core.discovery

import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

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
    private const val GREENHOUSE_API = "https://boards-api.greenhouse.io/v1/boards"
    private const val LEVER_API = "https://api.lever.co/v0/postings"
    private const val ASHBY_API = "https://api.ashbyhq.com/posting-api/job-board"
    private const val SMARTRECRUITERS_API = "https://api.smartrecruiters.com/v1/companies"
    private const val SMARTRECRUITERS_JOBS_PAGE = "https://jobs.smartrecruiters.com"

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
        BoardProvider.GREENHOUSE -> "$GREENHOUSE_API/${ref.token}/jobs"
        BoardProvider.LEVER -> "$LEVER_API/${ref.token}?mode=json"
        BoardProvider.ASHBY -> "$ASHBY_API/${ref.token}"
        BoardProvider.SMARTRECRUITERS -> "$SMARTRECRUITERS_API/${ref.token}/postings?limit=100"
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
                url = "$SMARTRECRUITERS_JOBS_PAGE/$company/$id",
                publishedAt = instant(j["releasedDate"].text()),
                location = j["location"].obj()?.get("fullLocation").text(),
                // The release date moves when a posting is published again
                dateIsFirstPublication = false,
            )
        }
    }
}
