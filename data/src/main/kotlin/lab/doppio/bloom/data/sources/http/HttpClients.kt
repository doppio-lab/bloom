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

fun defaultHttpClient(): HttpClient = HttpClient(CIO) {
    install(ContentNegotiation) { json(sourcesJson) }
    install(HttpTimeout) { requestTimeoutMillis = 20_000 }
    defaultRequest { header("User-Agent", "DoppioBloom/0.1 (+https://doppio-lab.com)") }
}
