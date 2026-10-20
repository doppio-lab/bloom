package lab.doppio.bloom.app.web

import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.statuspages.StatusPagesConfig
import io.ktor.server.response.respond
import org.slf4j.Logger
import java.sql.SQLException

/** Postgres: a unique constraint was violated (duplicate contact email, second active enrollment...). */
private const val UNIQUE_VIOLATION = "23505"

/**
 * HTTP status and message for an exception. A client mistake is 400, a duplicate is 409 (the unique
 * index speaks for the business rule), anything else is an internal error whose details stay in the log.
 */
internal fun errorStatus(e: Throwable): Pair<HttpStatusCode, String> {
    val chain = generateSequence(e) { it.cause }.take(10).toList()
    return when {
        e is IllegalArgumentException -> HttpStatusCode.BadRequest to (e.message ?: "requête invalide")
        e is BadRequestException -> HttpStatusCode.BadRequest to "requête invalide"
        chain.any { it is SQLException && it.sqlState == UNIQUE_VIOLATION } -> HttpStatusCode.Conflict to "déjà présent"
        else -> HttpStatusCode.InternalServerError to "erreur interne"
    }
}

fun StatusPagesConfig.bloomErrors(log: Logger) {
    exception<Throwable> { call, e ->
        val (status, message) = errorStatus(e)
        if (status == HttpStatusCode.InternalServerError) log.error("Erreur", e)
        call.respond(status, mapOf("error" to message))
    }
}
