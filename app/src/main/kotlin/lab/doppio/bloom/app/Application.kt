package lab.doppio.bloom.app

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.basic
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticResources
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.calllogging.processingTimeMillis
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.uri
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.head
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import lab.doppio.bloom.agents.Llm
import lab.doppio.bloom.app.config.BloomConfig
import lab.doppio.bloom.app.di.LOOPS
import lab.doppio.bloom.app.di.bloomModules
import lab.doppio.bloom.app.outreach.Scheduler
import lab.doppio.bloom.app.web.ApiRoutes
import lab.doppio.bloom.app.web.CrossSiteGuard
import lab.doppio.bloom.app.web.DiscoveryRoutes
import lab.doppio.bloom.app.web.UnsubscribeRoutes
import lab.doppio.bloom.core.port.SequenceRepository
import lab.doppio.bloom.core.sequence.DefaultSequences
import lab.doppio.bloom.data.persistence.Db
import org.koin.ktor.ext.get
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger
import kotlin.time.Duration.Companion.minutes

private val log = org.slf4j.LoggerFactory.getLogger("bloom")

fun main() {
    // Fail before touching the database when a secret, DRY_RUN or any other setting is unusable
    val config = BloomConfig.from(System.getenv())
    Db.connect(config.database)
    // The Koin graph is built when the server loads the module, right before it starts listening. It is also
    // where the sequences are ensured and the loops started, so nothing runs before the graph resolved.
    val server = embeddedServer(Netty, port = config.server.port) { bloom(config) }
    // SIGTERM (docker stop) stops the server first. Koin is closed on ApplicationStopping and cancels the
    // loops and the background scope; the database, advisory lock included, is closed last.
    server.monitor.subscribe(ApplicationStopped) {
        Db.close()
        log.info("Arrêt propre : boucles stoppées, base fermée")
    }
    server.start(wait = true)
}

/** The running application: the Koin graph of every layer, the default sequences, the loops and the web module. */
fun Application.bloom(config: BloomConfig) {
    install(Koin) {
        slf4jLogger()
        modules(bloomModules(config))
    }
    log.info("LLM : {}", get<Llm>().describe())
    runBlocking { get<SequenceRepository>().ensureSequences(DefaultSequences.all()) }
    get<Scheduler>().start(get<CoroutineScope>(LOOPS))
    module()
}

private val unsubscribeToken = Regex("^/u/[^/?]+")

/** Unsubscribe tokens identify a contact: they never reach the logs. */
internal fun maskedUri(uri: String) = uri.replace(unsubscribeToken, "/u/***")

/**
 * Plugins and routes. Expects Koin to be installed with the modules of [lab.doppio.bloom.app.di], so the same
 * code runs in production and in tests that replace the ports with fakes.
 */
fun Application.module() {
    install(CallLogging) {
        format { call -> "${call.response.status()}: ${call.request.httpMethod.value} - ${maskedUri(call.request.uri)} in ${call.processingTimeMillis()}ms" }
    }
    install(ContentNegotiation) { json(Json { encodeDefaults = true; ignoreUnknownKeys = true }) }
    install(StatusPages) {
        exception<IllegalArgumentException> { call, e -> call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message)) }
        exception<Throwable> { call, e ->
            this@module.environment.log.error("Erreur", e)
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "erreur interne"))
        }
    }
    val adminPassword = get<BloomConfig>().server.adminPassword.toByteArray()
    install(Authentication) {
        basic("admin") {
            realm = "Doppio Bloom"
            validate {
                // Constant-time compare: the response time says nothing about how much of the password matched
                val ok = java.security.MessageDigest.isEqual(it.password.toByteArray(), adminPassword)
                if (it.name == "doppio" && ok) UserIdPrincipal(it.name) else null
            }
        }
    }
    // One shared budget for the dashboard: behind a proxy every request comes from the same address
    install(RateLimit) { register(RateLimitName("admin")) { rateLimiter(limit = 600, refillPeriod = 1.minutes) } }
    val unsubscribe = get<UnsubscribeRoutes>()
    val api = get<ApiRoutes>()
    val discovery = get<DiscoveryRoutes>()
    routing {
        // Public : la désinscription doit marcher sans compte, en un clic
        unsubscribe.mount(this)
        rateLimit(RateLimitName("admin")) {
            authenticate("admin") {
                install(CrossSiteGuard)
                staticResources("/", "static")
                api.mount(this)
                discovery.mount(this)
            }
        }
        get("/health") { call.respondText("ok") }
        // Uptime probes often check with HEAD
        head("/health") { call.respond(HttpStatusCode.OK) }
    }
}
