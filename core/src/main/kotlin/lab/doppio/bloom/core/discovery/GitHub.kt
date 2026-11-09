package lab.doppio.bloom.core.discovery

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

data class GitHubOrg(val login: String, val name: String?, val website: String?, val location: String?, val url: String)

object GitHub {
    private const val SITE_URL = "https://github.com"

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
            url = org["html_url"].text() ?: "$SITE_URL/$login",
        )
    }
}
