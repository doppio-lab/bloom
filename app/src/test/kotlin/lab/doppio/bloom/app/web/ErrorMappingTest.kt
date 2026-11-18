package lab.doppio.bloom.app.web

import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import lab.doppio.bloom.app.admin
import lab.doppio.bloom.app.routeTest
import java.sql.SQLException
import io.ktor.server.plugins.BadRequestException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ErrorMappingTest {
    @Test
    fun `client mistakes and duplicates are not internal errors`() {
        assertEquals(HttpStatusCode.BadRequest to "Nom obligatoire", errorStatus(IllegalArgumentException("Nom obligatoire")))
        assertEquals(HttpStatusCode.BadRequest to "requête invalide", errorStatus(BadRequestException("bad json")))
        assertEquals(HttpStatusCode.Conflict to "déjà présent", errorStatus(SQLException("duplicate key", "23505")))
        assertEquals(HttpStatusCode.Conflict to "déjà présent", errorStatus(RuntimeException("wrapped", SQLException("duplicate key", "23505"))))
        assertEquals(HttpStatusCode.InternalServerError to "erreur interne", errorStatus(SQLException("deadlock", "40P01")))
        assertEquals(HttpStatusCode.InternalServerError to "erreur interne", errorStatus(IllegalStateException("boom")))
    }

    @Test
    fun `malformed JSON answers 400, not an internal error`() = routeTest {
        val r = client.post("/api/contacts") { admin(); contentType(ContentType.Application.Json); setBody("{\"fullName\": ") }
        assertEquals(HttpStatusCode.BadRequest, r.status, r.bodyAsText())
        assertTrue("erreur interne" !in r.bodyAsText())
    }
}
