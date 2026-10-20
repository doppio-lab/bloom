package lab.doppio.bloom.data.sources

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class DropcontactTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    @Test
    fun `un refus de l'API n'est pas un email introuvable`() = runTest {
        val unauthorized = HttpClient(MockEngine { respond("""{"error":true,"reason":"Invalid token"}""", HttpStatusCode.Unauthorized, json) })
        val e = assertFailsWith<DropcontactError> { DropcontactSource(unauthorized, "bad-key").findEmail("Alex Martin", "Velomoov", "velomoov.fr") }
        assertEquals(401, e.status)

        val submitted = HttpClient(MockEngine { req ->
            if (req.method == HttpMethod.Post) respond("""{"request_id":"abc","success":true}""", HttpStatusCode.OK, json)
            else respond("""{"error":true,"reason":"No credits left"}""", HttpStatusCode.PaymentRequired, json)
        })
        assertEquals(402, assertFailsWith<DropcontactError> { DropcontactSource(submitted, "key").findEmail("Alex Martin", "Velomoov", null) }.status)
    }

    @Test
    fun `un email nominatif professionnel est retenu, sinon rien`() = runTest {
        fun client(emails: String) = HttpClient(MockEngine { req ->
            if (req.method == HttpMethod.Post) respond("""{"request_id":"abc","success":true}""", HttpStatusCode.OK, json)
            else respond("""{"success":true,"data":[{"email":$emails}]}""", HttpStatusCode.OK, json)
        })
        val found = DropcontactSource(client("""[{"email":"contact@velomoov.fr","qualification":"generic"},{"email":"alex@velomoov.fr","qualification":"nominative@pro"}]"""), "key")
            .findEmail("Alex Martin", "Velomoov", null)
        assertEquals(DropcontactSource.Result("alex@velomoov.fr", "nominative@pro"), found)
        assertNull(DropcontactSource(client("""[{"email":"contact@velomoov.fr","qualification":"generic"}]"""), "key").findEmail("Alex Martin", "Velomoov", null))
    }
}
