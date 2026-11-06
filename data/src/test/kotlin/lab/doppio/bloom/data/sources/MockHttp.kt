package lab.doppio.bloom.data.sources

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import lab.doppio.bloom.data.sources.http.sourcesJson
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** A recorded response of a live source, shared with the core parser tests. */
fun fixture(name: String): String =
    object {}.javaClass.getResource("/fixtures/$name")!!.readText()

/** Production configuration of the JSON handling, on a [MockEngine]: no network is ever reached. */
class MockHttp(handler: suspend (HttpRequestData) -> Reply) {
    val requests = mutableListOf<HttpRequestData>()
    val client = HttpClient(
        MockEngine { request ->
            requests += request
            val reply = handler(request)
            respond(reply.body, reply.status, headersOf(HttpHeaders.ContentType, reply.contentType.toString()))
        },
    ) {
        install(ContentNegotiation) { json(sourcesJson) }
    }
    val urls: List<String> get() = requests.map { it.url.toString() }
}

class Reply(val body: String, val status: HttpStatusCode = HttpStatusCode.OK, val contentType: ContentType = ContentType.Application.Json)

fun json(body: String, status: HttpStatusCode = HttpStatusCode.OK) = Reply(body, status)
fun xml(body: String, status: HttpStatusCode = HttpStatusCode.OK) = Reply(body, status, ContentType.Application.Xml)
fun html(body: String, status: HttpStatusCode = HttpStatusCode.OK) = Reply(body, status, ContentType.Text.Html)
fun failure(status: HttpStatusCode = HttpStatusCode.InternalServerError) = Reply("boom", status, ContentType.Text.Plain)

/** A clock a test moves by hand. */
class MutableClock(private var now: Instant) : Clock() {
    override fun getZone() = ZoneOffset.UTC
    override fun withZone(zone: java.time.ZoneId?): Clock = this
    override fun instant(): Instant = now
    fun advanceSeconds(seconds: Long) { now = now.plusSeconds(seconds) }
}

fun HttpRequestData.param(name: String): String? = url.parameters[name]

fun parseJson(text: String) = sourcesJson.parseToJsonElement(text)
