package lab.doppio.bloom.data.sources.http

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

val sourcesJson = Json { ignoreUnknownKeys = true; isLenient = true }

/** A public source that has not answered after this long is skipped for the run. */
const val REQUEST_TIMEOUT_MILLIS = 20_000L

fun defaultHttpClient(): HttpClient = HttpClient(CIO) {
    install(ContentNegotiation) { json(sourcesJson) }
    install(HttpTimeout) { requestTimeoutMillis = REQUEST_TIMEOUT_MILLIS }
    defaultRequest { header("User-Agent", "DoppioBloom/0.1 (+https://doppio-lab.com)") }
}
