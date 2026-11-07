package lab.doppio.bloom.data.sources.discovery

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import lab.doppio.bloom.core.discovery.Codeur
import lab.doppio.bloom.core.discovery.CodeurProject

/**
 * Codeur.com : projets publiés par des entreprises (flux RSS public). Pas d'entreprise nommée :
 * ce ne sont pas des comptes à prospecter mais des demandes auxquelles on répond sur la plateforme.
 */
class CodeurSource(private val http: HttpClient, private val minBudgetEur: Int = 1_000) {
    /** The plain feed only: the site's robots.txt disallows query strings, so the filter runs here. */
    suspend fun mobileProjects(): List<CodeurProject> {
        val res = http.get("https://www.codeur.com/projects.rss")
        if (!res.status.isSuccess()) error("Codeur HTTP ${res.status.value}")
        return Codeur.parseFeed(res.bodyAsText()).filter { Codeur.isMobileProject(it, minBudgetEur) }
    }
}
