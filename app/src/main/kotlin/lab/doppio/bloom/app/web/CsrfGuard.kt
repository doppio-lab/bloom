package lab.doppio.bloom.app.web

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.request.contentLength
import io.ktor.server.request.header
import io.ktor.server.request.path
import io.ktor.server.request.receive
import io.ktor.server.response.respondText

/**
 * Browsers send Sec-Fetch-Site on every request: a form or script on another site that targets the API
 * with the admin's session says "cross-site" and is refused. Clients without the header (curl, the
 * dashboard itself: "same-origin") are let through. Only /api is guarded: the unsubscribe link is opened
 * from a mail client and must stay reachable.
 */
val CsrfGuard = createApplicationPlugin("CsrfGuard") {
    onCall { call ->
        if (call.request.path().startsWith("/api") && call.request.header("Sec-Fetch-Site") == "cross-site") {
            call.respondText("{\"error\":\"requête d'un autre site refusée\"}", ContentType.Application.Json, HttpStatusCode.Forbidden)
        }
    }
}

/**
 * The body when the request carries one, [default] when it carries none (no Content-Length or zero).
 * A body that is present but cannot be read is an error (400), never silently the default.
 */
suspend inline fun <reified T : Any> ApplicationCall.receiveOrDefault(default: T): T {
    val length = request.contentLength()
    return if (length == null || length == 0L) default else receive<T>()
}
