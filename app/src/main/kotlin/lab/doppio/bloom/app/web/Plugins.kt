package lab.doppio.bloom.app.web

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.basic
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.calllogging.processingTimeMillis
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.uri
import io.ktor.server.response.respond
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.minutes

/** Name of the Basic auth provider and of its rate limit, and the only user it accepts. */
internal const val ADMIN_AUTH = "admin"
private const val ADMIN_USER = "doppio"

/** Admin API requests allowed per minute: a dashboard session never gets close, a script gone wrong does. */
private const val ADMIN_REQUESTS_PER_MINUTE = 600

private val unsubscribeToken = Regex("^/u/[^/?]+")

/** Unsubscribe tokens identify a contact: they never reach the logs. */
internal fun maskedUri(uri: String) = uri.replace(unsubscribeToken, "/u/***")

internal fun Application.installCallLogging() {
    install(CallLogging) {
        format { call -> "${call.response.status()}: ${call.request.httpMethod.value} - ${maskedUri(call.request.uri)} in ${call.processingTimeMillis()}ms" }
    }
}

internal fun Application.installJson() {
    install(ContentNegotiation) { json(Json { encodeDefaults = true; ignoreUnknownKeys = true }) }
}

internal fun Application.installStatusPages() {
    install(StatusPages) {
        exception<IllegalArgumentException> { call, e -> call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message)) }
        exception<Throwable> { call, e ->
            this@installStatusPages.environment.log.error("Erreur", e)
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to ApiMessages.INTERNAL_ERROR))
        }
    }
}

/** Basic auth for the dashboard and the shared rate limit that goes with it. */
internal fun Application.installAdminSecurity(adminPassword: ByteArray) {
    install(Authentication) {
        basic(ADMIN_AUTH) {
            realm = "Doppio Bloom"
            validate {
                // Constant-time compare: the response time says nothing about how much of the password matched
                val ok = java.security.MessageDigest.isEqual(it.password.toByteArray(), adminPassword)
                if (it.name == ADMIN_USER && ok) UserIdPrincipal(it.name) else null
            }
        }
    }
    // One shared budget for the dashboard: behind a proxy every request comes from the same address
    install(RateLimit) { register(RateLimitName(ADMIN_AUTH)) { rateLimiter(limit = ADMIN_REQUESTS_PER_MINUTE, refillPeriod = 1.minutes) } }
}
