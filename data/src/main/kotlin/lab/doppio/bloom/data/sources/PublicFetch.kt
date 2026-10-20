package lab.doppio.bloom.data.sources

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.charset
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray

/** Largest body read from a page or an API that is not ours: the rest of the response is discarded. */
internal const val MAX_BODY_BYTES: Long = 2L * 1024 * 1024

internal val TEXT_CONTENT_TYPES = listOf(ContentType.Text.Html, ContentType.Text.Plain)
internal val JSON_CONTENT_TYPES = listOf(ContentType.Application.Json)

/**
 * Reads a page of a site we do not control. Null unless the status is 2xx and the Content-Type is one
 * of [accepted]: a PDF or a binary would be parsed as text otherwise. Reads at most [maxBytes].
 */
internal suspend fun HttpClient.fetchPublic(url: String, accepted: List<ContentType> = TEXT_CONTENT_TYPES, maxBytes: Long = MAX_BODY_BYTES): String? {
    val res = get(url)
    if (!res.status.isSuccess()) return null
    return res.textIfAccepted(accepted, maxBytes)
}

private suspend fun HttpResponse.textIfAccepted(accepted: List<ContentType>, maxBytes: Long): String? {
    val type = contentType() ?: return null
    if (accepted.none { type.match(it) }) return null
    val charset = type.charset() ?: Charsets.UTF_8
    val channel = bodyAsChannel()
    val bytes = channel.readRemaining(maxBytes).readByteArray()
    channel.cancel(null)
    return String(bytes, charset)
}
