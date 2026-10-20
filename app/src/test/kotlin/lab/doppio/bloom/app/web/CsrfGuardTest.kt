package lab.doppio.bloom.app.web

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class CsrfGuardTest {
    @Test
    fun `a cross-site request to the API is refused, same-site and non-API ones go through`() = testApplication {
        application {
            install(CsrfGuard)
            routing {
                post("/api/ping") { call.respond(HttpStatusCode.OK) }
                get("/u/1.abc") { call.respond(HttpStatusCode.OK) }
            }
        }
        assertEquals(HttpStatusCode.Forbidden, client.post("/api/ping") { header("Sec-Fetch-Site", "cross-site") }.status)
        assertEquals(HttpStatusCode.OK, client.post("/api/ping") { header("Sec-Fetch-Site", "same-origin") }.status)
        assertEquals(HttpStatusCode.OK, client.post("/api/ping").status, "no header: a non-browser client")
        assertEquals(HttpStatusCode.OK, client.get("/u/1.abc") { header("Sec-Fetch-Site", "cross-site") }.status, "the unsubscribe link is opened from the mail client")
    }

    @Test
    fun `an absent body is the empty decision, a broken one is refused`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            install(StatusPages) { bloomErrors(this@application.environment.log) }
            routing { post("/decide") { call.respond(call.receiveOrDefault(DecisionRequest())) } }
        }
        assertEquals(HttpStatusCode.OK, client.post("/decide").status)
        assertEquals("{\"subject\":null,\"body\":null}", client.post("/decide").bodyAsText())
        val edited = client.post("/decide") { contentType(ContentType.Application.Json); setBody("{\"subject\":\"Objet\"}") }
        assertEquals("{\"subject\":\"Objet\",\"body\":null}", edited.bodyAsText())
        val broken = client.post("/decide") { contentType(ContentType.Application.Json); setBody("{\"subject\":") }
        assertEquals(HttpStatusCode.BadRequest, broken.status, broken.bodyAsText())
    }
}
