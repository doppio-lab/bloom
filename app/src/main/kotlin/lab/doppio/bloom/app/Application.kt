package lab.doppio.bloom.app

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.basic
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticResources
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import lab.doppio.bloom.agents.Llm
import lab.doppio.bloom.agents.LlmConfig
import lab.doppio.bloom.agents.LlmProvider
import lab.doppio.bloom.agents.ReplyClassifierAgent
import lab.doppio.bloom.agents.ResearchAgent
import lab.doppio.bloom.agents.RunTracker
import lab.doppio.bloom.agents.WeeklyCoachAgent
import lab.doppio.bloom.agents.WriterAgent
import lab.doppio.bloom.app.outreach.EmailGateway
import lab.doppio.bloom.app.outreach.ImapPoller
import lab.doppio.bloom.app.outreach.MailConfig
import lab.doppio.bloom.app.outreach.Mailbox
import lab.doppio.bloom.app.outreach.Orchestrator
import lab.doppio.bloom.app.outreach.UnsubscribeTokens
import lab.doppio.bloom.app.web.apiRoutes
import lab.doppio.bloom.app.web.discoveryRoutes
import lab.doppio.bloom.app.web.unsubscribeRoutes
import lab.doppio.bloom.core.model.SendingPolicy
import lab.doppio.bloom.core.sequence.DefaultSequences
import lab.doppio.bloom.data.db.Db
import lab.doppio.bloom.data.db.DbConfig
import lab.doppio.bloom.data.db.Repository
import lab.doppio.bloom.data.sources.AppStoreSource
import lab.doppio.bloom.data.sources.BoampSource
import lab.doppio.bloom.data.sources.BodaccSource
import lab.doppio.bloom.data.sources.CodeurSource
import lab.doppio.bloom.data.sources.TedSource
import lab.doppio.bloom.data.sources.GitHubSource
import lab.doppio.bloom.data.sources.JobBoardSource
import lab.doppio.bloom.data.sources.PressSource
import lab.doppio.bloom.data.sources.CompanyRegistrySource
import lab.doppio.bloom.data.sources.FranceTravailSource
import lab.doppio.bloom.data.sources.WebPageSource
import lab.doppio.bloom.data.sources.DropcontactSource
import lab.doppio.bloom.data.discovery.DiscoveryConfig
import lab.doppio.bloom.data.discovery.DiscoveryService
import lab.doppio.bloom.data.sources.defaultHttpClient
import kotlinx.coroutines.runBlocking

/** A blank value in .env (KEY=) counts as an absent key: the default applies, or the start stops. */
internal fun envValue(lookup: (String) -> String?, name: String, default: String? = null): String =
    lookup(name)?.takeIf { it.isNotBlank() } ?: default ?: error("Variable d'environnement manquante : $name")

private fun env(name: String, default: String? = null): String = envValue(System::getenv, name, default)

/** An empty value in .env (KEY=) counts as an absent key. */
private fun optionalEnv(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

/** The example values shipped in .env.example: never accepted as a secret. */
private val PLACEHOLDER_SECRETS = setOf("change-me", "une-longue-chaine-aleatoire")

/** A secret that must be set: not blank, not the example value, at least 16 characters. */
internal fun requireSecret(lookup: (String) -> String?, name: String): String {
    val value = lookup(name)?.trim().orEmpty()
    if (value.isEmpty()) error("Variable d'environnement manquante : $name")
    if (value in PLACEHOLDER_SECRETS) error("$name : remplacer la valeur d'exemple de .env.example par un secret")
    if (value.length < 16) error("$name : au moins 16 caractères")
    return value
}

/**
 * DRY_RUN decides whether emails leave: only "true" or "false" are accepted. Anything else ("yes",
 * "1", a blank) stops the start instead of being read as false. Absent: true, nothing leaves.
 */
internal fun parseDryRun(raw: String?): Boolean = when (raw?.trim()?.lowercase()) {
    null -> true
    "true" -> true
    "false" -> false
    else -> error("DRY_RUN : valeur attendue true ou false, reçu « $raw »")
}

/**
 * MAILBOXES="prenom@doppio-studio.fr:motdepasseapplication:Prénom Nom,..." : the password may hold
 * colons, the display name is everything after the second one. A malformed entry stops the start.
 */
internal fun parseMailboxes(raw: String?): List<Mailbox> = raw.orEmpty().split(',').filter { it.isNotBlank() }.map { entry ->
    val parts = entry.split(':', limit = 3).map { it.trim() }
    if (parts.size < 3 || parts.any { it.isEmpty() }) error("MAILBOXES : format attendu adresse:mot-de-passe:Nom, reçu « ${entry.trim()} »")
    Mailbox(parts[0], parts[1], parts[2])
}

/** Câblage manuel : pas de framework d'injection pour une douzaine de composants. */
class AppContext {
    val repo = Repository()
    private val http = defaultHttpClient()
    val llm = LlmProvider.parse(env("LLM_PROVIDER", "anthropic")).let { provider ->
        Llm(
            LlmConfig(
                provider = provider,
                apiKey = when (provider) {
                    LlmProvider.ANTHROPIC -> optionalEnv("ANTHROPIC_API_KEY") ?: error("ANTHROPIC_API_KEY est obligatoire avec LLM_PROVIDER=anthropic")
                    LlmProvider.OPENAI -> optionalEnv("OPENAI_API_KEY")
                    LlmProvider.OLLAMA -> null
                },
                baseUrl = when (provider) {
                    LlmProvider.ANTHROPIC -> null
                    LlmProvider.OPENAI -> optionalEnv("OPENAI_BASE_URL")
                    LlmProvider.OLLAMA -> optionalEnv("OLLAMA_BASE_URL")
                },
                model = optionalEnv("LLM_MODEL"),
                fastModel = optionalEnv("LLM_MODEL_FAST"),
                contextLength = env("LLM_CONTEXT_LENGTH", "16384").toLong(),
            ),
        )
    }
    private val tracker = RunTracker(repo, dailyTokenBudget = optionalEnv("LLM_DAILY_TOKEN_BUDGET")?.toLong() ?: 0)
    /** Portée des tâches de fond déclenchées depuis le dashboard (découverte, recherche). */
    val background = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val policy = SendingPolicy(maxPerMailboxPerDay = env("MAX_PER_MAILBOX_PER_DAY", "25").toInt())

    val mail = MailConfig(
        smtpHost = env("SMTP_HOST", "smtp.gmail.com"),
        smtpPort = env("SMTP_PORT", "587").toInt(),
        imapHost = env("IMAP_HOST", "imap.gmail.com"),
        mailboxes = parseMailboxes(System.getenv("MAILBOXES")),
        publicBaseUrl = env("PUBLIC_BASE_URL", "http://localhost:8080"),
        unsubscribeSecret = requireSecret(System::getenv, "UNSUBSCRIBE_SECRET"),
        legalFooterFr = env(
            "LEGAL_FOOTER_FR",
            "Doppio Lab (Advisely), Paris. Vos coordonnées professionnelles proviennent de sources publiques " +
                "(site de votre entreprise, annuaire des entreprises) et ne servent qu'à ce contact.",
        ),
        legalFooterEn = env(
            "LEGAL_FOOTER_EN",
            "Doppio Lab (Advisely), Paris, France. Your business contact details come from public sources and are used only for this contact.",
        ),
        dryRun = parseDryRun(System.getenv("DRY_RUN")),
    )
    val tokens = UnsubscribeTokens(mail.unsubscribeSecret)
    private val gateway = EmailGateway(mail, tokens)

    private val appStore = AppStoreSource(http)
    private val registry = CompanyRegistrySource(http)
    private val jobs = optionalEnv("FT_CLIENT_ID")?.let { FranceTravailSource(http, it, env("FT_CLIENT_SECRET")) }

    /** Optionnel : sans clé Dropcontact, les contacts sont saisis à la main (nom, email ou profil LinkedIn). */
    val dropcontact = optionalEnv("DROPCONTACT_API_KEY")?.let { DropcontactSource(http, it) }

    val research = ResearchAgent(llm, repo, tracker, appStore = appStore, registry = registry, jobs = jobs, web = WebPageSource(http))

    /**
     * Sources added to discovery, chosen by DISCOVERY_SOURCES. The press feeds are part of the default;
     * their reuse terms have to be checked by the owner, who removes "press" from the list in case of doubt
     * (see .env.example).
     */
    private val enabledSources = env("DISCOVERY_SOURCES", "bodacc,boamp,ted,codeur,press,github,jobboards").split(',').map { it.trim().lowercase() }.toSet()
    private val discoverySources = listOfNotNull(
        BodaccSource(http, minCapitalEur = env("BODACC_MIN_CAPITAL", "100000").toLong()).takeIf { "bodacc" in enabledSources },
        BoampSource(http).takeIf { "boamp" in enabledSources },
        TedSource(http).takeIf { "ted" in enabledSources },
        PressSource(http).takeIf { "press" in enabledSources },
        GitHubSource(http, optionalEnv("GITHUB_TOKEN"), wasSeen = { repo.wasSeen(it, 90) }, markSeen = { key, kept, reason -> repo.markSeen(key, kept, reason) })
            .takeIf { "github" in enabledSources },
    )

    val discovery = DiscoveryService(
        repo, appStore, registry, jobs,
        DiscoveryConfig(
            appsPerRun = env("DISCOVERY_APPS_PER_RUN", "400").toInt(),
            // Clients actuels et partenaires à ne jamais prospecter, séparés par des virgules
            extraExclusions = env("DISCOVERY_EXCLUDE", "").split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
        ),
        sources = discoverySources,
        jobBoards = JobBoardSource(http).takeIf { "jobboards" in enabledSources },
        codeur = CodeurSource(http, minBudgetEur = env("CODEUR_MIN_BUDGET", "1000").toInt()).takeIf { "codeur" in enabledSources },
    )
    val writer = WriterAgent(llm, repo, tracker, senderName = env("SENDER_NAME", "l'équipe Doppio"))
    val orchestrator = Orchestrator(
        repo, writer, research, ReplyClassifierAgent(llm, tracker), WeeklyCoachAgent(llm, repo, tracker),
        gateway, ImapPoller(mail), mail, policy, discovery,
    )
}

fun main() {
    Db.connect(DbConfig(env("DATABASE_URL", "jdbc:postgresql://localhost:5432/bloom"), env("DB_USER", "bloom"), env("DB_PASSWORD", "bloom")))
    val ctx = AppContext()
    org.slf4j.LoggerFactory.getLogger("bloom").info("LLM : {}", ctx.llm.describe())
    runBlocking { ctx.repo.ensureSequences(DefaultSequences.all()) }
    ctx.orchestrator.start(CoroutineScope(SupervisorJob() + Dispatchers.Default))
    embeddedServer(Netty, port = env("PORT", "8080").toInt()) { module(ctx) }.start(wait = true)
}

fun Application.module(ctx: AppContext) {
    install(CallLogging)
    install(ContentNegotiation) { json(Json { encodeDefaults = true; ignoreUnknownKeys = true }) }
    install(StatusPages) {
        exception<IllegalArgumentException> { call, e -> call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message)) }
        exception<Throwable> { call, e ->
            this@module.environment.log.error("Erreur", e)
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "erreur interne"))
        }
    }
    val adminPassword = requireSecret(System::getenv, "ADMIN_PASSWORD")
    install(Authentication) {
        basic("admin") {
            realm = "Doppio Bloom"
            validate { if (it.name == "doppio" && it.password == adminPassword) UserIdPrincipal(it.name) else null }
        }
    }
    routing {
        // Public : la désinscription doit marcher sans compte, en un clic
        unsubscribeRoutes(ctx)
        authenticate("admin") {
            staticResources("/", "static")
            apiRoutes(ctx)
            discoveryRoutes(ctx)
        }
        get("/health") { call.respondText("ok") }
    }
}
