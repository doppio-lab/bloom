package lab.doppio.bloom.app.web

import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentLength
import io.ktor.server.request.receive

/**
 * The body when the request carries one, [default] when it carries none (no Content-Length or zero).
 * A body that is present but cannot be read is an error (400), never silently the default: a broken
 * edit must not be approved as if the reviewer had sent the original draft.
 */
suspend inline fun <reified T : Any> ApplicationCall.receiveOrDefault(default: T): T {
    val length = request.contentLength()
    return if (length == null || length == 0L) default else receive<T>()
}
