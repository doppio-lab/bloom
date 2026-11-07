package lab.doppio.bloom.data.sources.facts

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import lab.doppio.bloom.core.model.SignalType
import lab.doppio.bloom.data.sources.Reply
import lab.doppio.bloom.data.sources.MockHttp
import lab.doppio.bloom.data.sources.MutableClock
import lab.doppio.bloom.data.sources.failure
import lab.doppio.bloom.data.sources.json
import lab.doppio.bloom.data.sources.param
import lab.doppio.bloom.data.sources.parseJson
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FranceTravailSourceTest {
    private val start = Instant.parse("2026-11-06T10:00:00Z")
    private val clock = MutableClock(start)

    private fun offer(id: String, title: String = "Développeur iOS", company: String? = "Acme", description: String = "Swift", created: String = "2026-11-01T09:00:00+01:00") =
        """{"id":"$id","intitule":"$title","description":"$description","dateCreation":"$created","entreprise":${if (company == null) "{}" else """{"nom":"$company","url":"https://acme.fr"}"""}}"""

    private fun results(vararg offers: String) = """{"resultats":[${offers.joinToString(",")}]}"""

    private var tokenCalls = 0

    private fun http(searchReply: (Int) -> Reply = { json(results(offer("1"))) }): MockHttp {
        var searches = 0
        return MockHttp { request ->
            if (request.url.encodedPath.endsWith("access_token")) {
                tokenCalls++
                json("""{"access_token":"tok$tokenCalls","expires_in":1500}""")
            } else searchReply(searches++)
        }
    }

    private fun source(http: MockHttp) = FranceTravailSource(http.client, "my-id", "my-secret", clock)

    @Test
    fun `a search fetches a token with the client credentials, then calls the API with it`() = runTest {
        val http = http()
        val offers = source(http).search("développeur iOS", publieeDepuis = 7)

        val (token, search) = http.requests
        assertEquals(HttpMethod.Post, token.method)
        assertEquals("https://entreprise.francetravail.fr/connexion/oauth2/access_token?realm=%2Fpartenaire", token.url.toString())
        val form = String((token.body as io.ktor.http.content.OutgoingContent.ByteArrayContent).bytes())
        assertTrue("grant_type=client_credentials" in form && "client_id=my-id" in form && "client_secret=my-secret" in form)
        assertTrue("scope=api_offresdemploiv2+o2dsoffre" in form || "scope=api_offresdemploiv2%20o2dsoffre" in form)

        assertEquals("https://api.francetravail.io/partenaire/offresdemploi/v2/offres/search", search.url.toString().substringBefore('?'))
        assertEquals("Bearer tok1", search.headers[HttpHeaders.Authorization])
        assertEquals(listOf("développeur iOS", "7", "0-149"), listOf("motsCles", "publieeDepuis", "range").map { search.param(it) })
        assertEquals(listOf("1"), offers.map { it["id"]!!.toString().trim('"') })
    }

    @Test
    fun `the token is reused until shortly before it expires`() = runTest {
        val http = http()
        val source = source(http)
        source.search("a")
        source.search("b")
        assertEquals(1, tokenCalls)

        // expires_in is 1500 s and one minute of margin is kept: 1440 s
        clock.advanceSeconds(1_439)
        source.search("c")
        assertEquals(1, tokenCalls)
        clock.advanceSeconds(1)
        source.search("d")
        assertEquals(2, tokenCalls)
        assertEquals("Bearer tok2", http.requests.last().headers[HttpHeaders.Authorization])
    }

    @Test
    fun `a token response without a token is an error`() = runTest {
        val http = MockHttp { json("""{"error":"invalid_client"}""") }
        val error = assertFailsWith<IllegalStateException> { source(http).search("a") }
        assertTrue(error.message!!.startsWith("France Travail : pas de jeton"))
    }

    @Test
    fun `rejected credentials are an error, not an empty result`() = runTest {
        for (status in listOf(HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden)) {
            val error = assertFailsWith<IllegalStateException> { source(http { failure(status) }).search("a") }
            assertEquals("France Travail a refusé les identifiants (HTTP ${status.value})", error.message)
        }
    }

    @Test
    fun `no content and server errors end the search quietly`() = runTest {
        assertEquals(emptyList(), source(http { Reply("", HttpStatusCode.NoContent) }).search("a"))
        assertEquals(emptyList(), source(http { failure() }).search("a"))
    }

    @Test
    fun `a full page of 150 offers asks for the next range`() = runTest {
        val full = results(*Array(150) { offer("o$it") })
        val http = http { page -> if (page == 0) json(full) else json(results(offer("last"))) }
        val offers = source(http).search("a", maxPages = 3)
        assertEquals(151, offers.size)
        assertEquals(listOf("0-149", "150-299"), http.requests.filter { "offres/search" in it.url.toString() }.map { it.param("range") })
    }

    @Test
    fun `a malformed body is propagated`() = runTest {
        assertFailsWith<Exception> { source(http { json("<html>") }).search("a") }
    }

    @Test
    fun `factsFromOffers keeps the mobile offers and derives the hiring signals`() {
        val source = source(http())
        val offers = listOf(
            offer("1", "Développeur iOS", description = "Kotlin Multiplatform et Swift", created = "2026-09-01T10:00:00+02:00"),
            offer("2", "Comptable"),
            offer("3", "Développeur Android", description = "Flutter ou React Native", created = "2026-11-05T10:00:00+01:00"),
        ).map { parseJson(it).jsonObject }
        val result = source.factsFromOffers(7, offers, start)

        val statements = result.facts.map { it.statement }
        assertTrue("Offre d'emploi « Développeur iOS » publiée il y a 66 jours" in statements)
        assertTrue("Offre d'emploi « Développeur Android » publiée il y a 1 jours" in statements)
        assertTrue("L'offre « Développeur Android » mentionne une stack cross-platform (Flutter ou React Native)" in statements)
        assertTrue(statements.none { "Comptable" in it })
        assertTrue(result.facts.all { it.retrievedAt == start.toString() })
        assertEquals(
            listOf(SignalType.HIRING_MOBILE, SignalType.HIRING_KMP, SignalType.HIRING_STUCK, SignalType.HIRING_MOBILE),
            result.signals.map { it.type },
        )
        assertEquals("https://candidat.francetravail.fr/offres/recherche/detail/1", result.facts.first().sourceUrl)
    }

    @Test
    fun `the offer accessors read the company and the id`() {
        val source = source(http())
        val o = parseJson(offer("9", company = "  Acme  ")).jsonObject
        assertEquals("Acme", source.companyName(o))
        assertEquals("https://acme.fr", source.companyUrl(o))
        assertEquals("9", source.offerId(o))
        assertEquals(null, source.companyName(parseJson(offer("9", company = null)).jsonObject))
    }

    @Test
    fun `mobileJobs searches the company name and keeps only its offers`() = runTest {
        val http = http { json(results(offer("1", company = "Acme SAS"), offer("2", company = "Autre"))) }
        val result = source(http).mobileJobs(7, "Acme", start)
        assertEquals("mobile,Acme", http.requests.last().param("motsCles"))
        assertEquals(1, result.facts.size)
        assertEquals(1, http.requests.count { "offres/search" in it.url.toString() })
    }
}
