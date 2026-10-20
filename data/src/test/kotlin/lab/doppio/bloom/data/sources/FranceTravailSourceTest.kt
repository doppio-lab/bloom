package lab.doppio.bloom.data.sources

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class FranceTravailSourceTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val token = """{"access_token":"tok","expires_in":1499}"""
    private val page = """{"resultats":[{"id":"1","intitule":"Développeur iOS","entreprise":{"nom":"Velomoov"}}]}"""

    /** Same plugins as defaultHttpClient: the token call reads its JSON through content negotiation. */
    private fun client(engine: MockEngine) = HttpClient(engine) { install(ContentNegotiation) { json(sourcesJson) } }

    @Test
    fun `une réponse 429 est attendue puis la page est relue`() = runTest {
        var searches = 0
        val engine = MockEngine { req ->
            when {
                "access_token" in req.url.encodedPath -> respond(token, HttpStatusCode.OK, json)
                searches++ == 0 -> respond("", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "1"))
                else -> respond(page, HttpStatusCode.OK, json)
            }
        }
        val offers = FranceTravailSource(client(engine), "id", "secret").search("développeur iOS", publieeDepuis = 7)
        assertEquals(listOf("1"), offers.map { it["id"]!!.toString().trim('"') })
        assertEquals(2, searches, "one retry after the 429")
    }

    @Test
    fun `le jeton n'est demandé qu'une fois pour des recherches concurrentes`() = runTest {
        var tokens = 0
        val engine = MockEngine { req ->
            if ("access_token" in req.url.encodedPath) { tokens++; respond(token, HttpStatusCode.OK, json) }
            else respond(page, HttpStatusCode.OK, json)
        }
        val source = FranceTravailSource(client(engine), "id", "secret")
        (1..5).map { async { source.search("mot $it", maxPages = 1) } }.awaitAll()
        assertEquals(1, tokens)
    }
}
