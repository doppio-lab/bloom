package lab.doppio.bloom.data.sources.http

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import lab.doppio.bloom.core.Brand

val sourcesJson = Json { ignoreUnknownKeys = true; isLenient = true }

/** A public source that has not answered after this long is skipped for the run. */
const val REQUEST_TIMEOUT_MILLIS = 20_000L

fun defaultHttpClient(requestTimeoutMillis: Long = REQUEST_TIMEOUT_MILLIS): HttpClient = HttpClient(CIO) {
    install(ContentNegotiation) { json(sourcesJson) }
    install(HttpTimeout) { this.requestTimeoutMillis = requestTimeoutMillis }
    defaultRequest { header("User-Agent", Brand.USER_AGENT) }
}
