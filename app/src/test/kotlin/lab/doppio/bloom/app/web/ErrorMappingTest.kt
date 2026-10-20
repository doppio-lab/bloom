package lab.doppio.bloom.app.web

import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ErrorMappingTest {
    @Test
    fun `client mistakes and duplicates are not internal errors`() {
        assertEquals(HttpStatusCode.BadRequest, errorStatus(IllegalArgumentException("Nom obligatoire")).first)
        assertEquals("Nom obligatoire", errorStatus(IllegalArgumentException("Nom obligatoire")).second)
        assertEquals(HttpStatusCode.BadRequest, errorStatus(BadRequestException("bad json")).first)
        assertEquals(HttpStatusCode.Conflict to "déjà présent", errorStatus(SQLException("duplicate key", "23505")))
        assertEquals(HttpStatusCode.Conflict to "déjà présent", errorStatus(RuntimeException("wrapped", SQLException("duplicate key", "23505"))))
        assertEquals(HttpStatusCode.InternalServerError to "erreur interne", errorStatus(SQLException("deadlock", "40P01")))
        assertEquals(HttpStatusCode.InternalServerError to "erreur interne", errorStatus(IllegalStateException("boom")))
    }

    @Test
    fun `malformed JSON answers 400`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            install(StatusPages) { bloomErrors(this@application.environment.log) }
            routing { post("/echo") { call.respond(call.receive<DecisionRequest>()) } }
        }
        val r = client.post("/echo") { contentType(ContentType.Application.Json); setBody("{\"subject\": ") }
        assertEquals(HttpStatusCode.BadRequest, r.status, r.bodyAsText())
        assertTrue("erreur interne" !in r.bodyAsText())
    }
}
