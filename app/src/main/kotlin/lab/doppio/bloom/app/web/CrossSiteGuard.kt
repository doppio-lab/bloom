package lab.doppio.bloom.app.web

import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.response.respondText

/**
 * Browsers attach Basic credentials to cross-site form posts, so a hostile page could approve a draft or
 * blacklist an account. State-changing requests are only accepted from the dashboard itself
 * (Sec-Fetch-Site: same-origin) or from tools that send no such header (curl).
 */
val CrossSiteGuard = createRouteScopedPlugin("CrossSiteGuard") {
    onCall { call ->
        if (call.request.local.method in setOf(HttpMethod.Get, HttpMethod.Head, HttpMethod.Options)) return@onCall
        when (call.request.headers["Sec-Fetch-Site"]) {
            null, "same-origin", "none" -> Unit
            else -> call.respondText(
                """{"error":"requête refusée : elle ne vient pas du dashboard"}""",
                ContentType.Application.Json,
                HttpStatusCode.Forbidden,
            )
        }
    }
}
