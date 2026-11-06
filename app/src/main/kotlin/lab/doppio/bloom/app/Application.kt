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
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.httpMethod
import io.ktor.server.request.uri
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.head
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import lab.doppio.bloom.agents.Llm
import lab.doppio.bloom.agents.ReplyClassifierAgent
import lab.doppio.bloom.agents.ResearchAgent
import lab.doppio.bloom.agents.RunTracker
import lab.doppio.bloom.agents.WeeklyCoachAgent
import lab.doppio.bloom.agents.WriterAgent
import lab.doppio.bloom.app.config.BloomConfig
import lab.doppio.bloom.app.outreach.EmailGateway
import lab.doppio.bloom.app.outreach.ImapPoller
import lab.doppio.bloom.app.outreach.Scheduler
import lab.doppio.bloom.app.outreach.UnsubscribeTokens
import lab.doppio.bloom.app.web.CrossSiteGuard
import lab.doppio.bloom.app.web.apiRoutes
import lab.doppio.bloom.app.web.discoveryRoutes
import lab.doppio.bloom.app.web.unsubscribeRoutes
import lab.doppio.bloom.core.sequence.DefaultSequences
import lab.doppio.bloom.core.port.Drafter
import lab.doppio.bloom.core.port.Researcher
import lab.doppio.bloom.core.usecase.AddContact
import lab.doppio.bloom.core.usecase.CompleteTask
import lab.doppio.bloom.core.usecase.ContactBook
import lab.doppio.bloom.core.usecase.DecideDraft
import lab.doppio.bloom.core.usecase.EnrollContact
import lab.doppio.bloom.core.usecase.FindContactEmail
import lab.doppio.bloom.core.usecase.GetOverview
import lab.doppio.bloom.core.usecase.ImportAccounts
import lab.doppio.bloom.core.usecase.ListToComplete
import lab.doppio.bloom.core.usecase.PlanDrafts
import lab.doppio.bloom.core.usecase.ProcessReplies
import lab.doppio.bloom.core.usecase.ResearchBatch
import lab.doppio.bloom.core.usecase.ReviewQueue
import lab.doppio.bloom.core.usecase.RunDiscovery
import lab.doppio.bloom.core.usecase.RunPeriodicJobs
import lab.doppio.bloom.core.usecase.SendNextApproved
import lab.doppio.bloom.core.usecase.StopContactSequences
import lab.doppio.bloom.core.usecase.UnsubscribeContact
import lab.doppio.bloom.data.persistence.Db
import lab.doppio.bloom.data.persistence.PostgresInstanceLock
import lab.doppio.bloom.data.persistence.account.ExposedAccountRepository
import lab.doppio.bloom.data.persistence.agentrun.ExposedAgentRunRepository
import lab.doppio.bloom.data.persistence.contact.ExposedContactRepository
import lab.doppio.bloom.data.persistence.deal.ExposedDealRepository
import lab.doppio.bloom.data.persistence.discovery.ExposedDiscoveryStateRepository
import lab.doppio.bloom.data.persistence.draft.ExposedDraftRepository
import lab.doppio.bloom.data.persistence.event.ExposedEventRepository
import lab.doppio.bloom.data.persistence.fact.ExposedFactRepository
import lab.doppio.bloom.data.persistence.job.ExposedJobRunRepository
import lab.doppio.bloom.data.persistence.officer.ExposedOfficerRepository
import lab.doppio.bloom.data.persistence.reply.ExposedReplyRepository
import lab.doppio.bloom.data.persistence.sequence.ExposedSequenceRepository
import lab.doppio.bloom.data.sources.AppStoreSource
import lab.doppio.bloom.data.sources.BoampSource
import lab.doppio.bloom.data.sources.BodaccSource
import lab.doppio.bloom.data.sources.CodeurSource
import lab.doppio.bloom.data.sources.DecpSource
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
import java.time.Clock
import kotlin.time.Duration.Companion.minutes

/** Câblage manuel : pas de framework d'injection pour une douzaine de composants. */
class AppContext(val config: BloomConfig, val clock: Clock = Clock.systemDefaultZone()) {
    val accountRepo = ExposedAccountRepository(clock)
    val officerRepo = ExposedOfficerRepository(clock)
    val contactRepo = ExposedContactRepository(clock)
    val factRepo = ExposedFactRepository(clock)
    val sequenceRepo = ExposedSequenceRepository(clock)
    val draftRepo = ExposedDraftRepository(clock)
    val replyRepo = ExposedReplyRepository()
    val dealRepo = ExposedDealRepository(clock)
    val jobRunRepo = ExposedJobRunRepository(clock)
    val eventRepo = ExposedEventRepository()
    val agentRunRepo = ExposedAgentRunRepository()
    val discoveryState = ExposedDiscoveryStateRepository(clock)
    private val http = defaultHttpClient()
    val llm = Llm.from(config.llm.client)
    private val tracker = RunTracker(agentRunRepo, dailyTokenBudget = config.llm.dailyTokenBudget, clock = clock)
    /** Portée des tâches de fond déclenchées depuis le dashboard (découverte, recherche). */
    val background = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val policy = config.sending.policy

    val mail = config.mail
    val tokens = UnsubscribeTokens(mail.unsubscribeSecret)
    private val gateway = EmailGateway(mail, tokens)

    private val appStore = AppStoreSource(http)
    private val registry = CompanyRegistrySource(http)
    private val jobs = config.discovery.franceTravail?.let { FranceTravailSource(http, it.clientId, it.clientSecret) }

    /** Optionnel : sans clé Dropcontact, les contacts sont saisis à la main (nom, email ou profil LinkedIn). */
    val dropcontact = config.discovery.dropcontactApiKey?.let { DropcontactSource(http, it) }

    val research: Researcher = ResearchAgent(llm, accountRepo, eventRepo, factRepo, tracker, appStore = appStore, registry = registry, jobs = jobs, web = WebPageSource(http), clock = clock)

    /**
     * Sources added to discovery, chosen by DISCOVERY_SOURCES. The press feeds are left out of the default:
     * their reuse terms have to be checked by the owner before the source is turned on.
     */
    private val enabledSources = config.discovery.sources
    private val discoverySources = listOfNotNull(
        BodaccSource(http, minCapitalEur = config.discovery.bodaccMinCapitalEur).takeIf { "bodacc" in enabledSources },
        BoampSource(http).takeIf { "boamp" in enabledSources },
        TedSource(http).takeIf { "ted" in enabledSources },
        DecpSource(
            http,
            buyerName = { siren -> registry.bySiren(siren)?.name },
            wasSeen = { discoveryState.wasSeen(it, 365) },
            markSeen = { key, kept, reason -> discoveryState.markSeen(key, kept, reason) },
            maxMonths = config.discovery.decpRenewalMonths,
        ).takeIf { "decp" in enabledSources },
        PressSource(http).takeIf { "press" in enabledSources },
        GitHubSource(http, config.discovery.githubToken, wasSeen = { discoveryState.wasSeen(it, 90) }, markSeen = { key, kept, reason -> discoveryState.markSeen(key, kept, reason) })
            .takeIf { "github" in enabledSources },
    )

    val discovery = DiscoveryService(
        accountRepo, discoveryState, eventRepo, factRepo, officerRepo, appStore, registry, jobs,
        DiscoveryConfig(
            appsPerRun = config.discovery.appsPerRun,
            // Clients actuels et partenaires à ne jamais prospecter
            extraExclusions = config.discovery.extraExclusions,
        ),
        sources = discoverySources,
        jobBoards = JobBoardSource(http).takeIf { "jobboards" in enabledSources },
        codeur = CodeurSource(http, minBudgetEur = config.discovery.codeurMinBudgetEur).takeIf { "codeur" in enabledSources },
        clock = clock,
    )
    val writer: Drafter = WriterAgent(llm, accountRepo, contactRepo, draftRepo, eventRepo, factRepo, tracker, senderName = config.sending.senderName)
    private val coach = WeeklyCoachAgent(llm, eventRepo, accountRepo, dealRepo, agentRunRepo, tracker, clock)
    private val classifier = ReplyClassifierAgent(llm, tracker, clock)

    // Use cases: what the application does, free of Ktor and of the database
    val stopContactSequences = StopContactSequences(sequenceRepo)
    val unsubscribeContact = UnsubscribeContact(contactRepo, replyRepo, eventRepo, stopContactSequences)
    val planDrafts = PlanDrafts(sequenceRepo, draftRepo, contactRepo, replyRepo, eventRepo, writer, stopContactSequences, policy, clock)
    val sendNextApproved = SendNextApproved(draftRepo, sequenceRepo, contactRepo, replyRepo, eventRepo, gateway, mail.mailboxes, policy, clock)
    val processReplies = ProcessReplies(ImapPoller(mail, clock), classifier, replyRepo, draftRepo, sequenceRepo, contactRepo, eventRepo, stopContactSequences)
    val researchBatch = ResearchBatch(accountRepo, factRepo, research, clock)
    val runDiscovery = RunDiscovery(discovery)
    val runPeriodicJobs = RunPeriodicJobs(
        jobRunRepo, discovery, runDiscovery, researchBatch, coach, eventRepo, contactRepo, officerRepo, discoveryState, policy, clock,
    )
    val scheduler = Scheduler(PostgresInstanceLock(), draftRepo, planDrafts, sendNextApproved, processReplies, runPeriodicJobs)

    val enrollContact = EnrollContact(sequenceRepo, replyRepo, factRepo, accountRepo, eventRepo, afterEnrolled = scheduler::planSoon)
    val addContact = AddContact(contactRepo, replyRepo, enrollContact)
    val findContactEmail = dropcontact?.let { FindContactEmail(it, contactRepo, eventRepo, enrollContact) }
    val decideDraft = DecideDraft(draftRepo, sequenceRepo, contactRepo, accountRepo, factRepo, eventRepo)
    val completeTask = CompleteTask(draftRepo, sequenceRepo, contactRepo, eventRepo)
    val reviewQueue = ReviewQueue(draftRepo, sequenceRepo, contactRepo, accountRepo, factRepo)
    val overview = GetOverview(eventRepo, dealRepo, draftRepo, replyRepo, accountRepo, agentRunRepo, factRepo, policy, clock, mail.dryRun, mail.mailboxes.size)
    val contactBook = ContactBook(accountRepo, contactRepo, officerRepo, sequenceRepo, factRepo)
    val toComplete = ListToComplete(accountRepo, factRepo)
    val importAccounts = ImportAccounts(accountRepo, factRepo, contactRepo, replyRepo, eventRepo, clock)
}

fun main() {
    // Fail before touching the database when a secret, DRY_RUN or any other setting is unusable
    val config = BloomConfig.from(System.getenv())
    Db.connect(config.database)
    val ctx = AppContext(config)
    org.slf4j.LoggerFactory.getLogger("bloom").info("LLM : {}", ctx.llm.describe())
    runBlocking { ctx.sequenceRepo.ensureSequences(DefaultSequences.all()) }
    val loops = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    ctx.scheduler.start(loops)
    val server = embeddedServer(Netty, port = config.server.port) { module(ctx) }
    // SIGTERM (docker stop) stops the server first, then the loops and the database, lock included
    server.monitor.subscribe(ApplicationStopped) {
        loops.cancel()
        ctx.background.cancel()
        Db.close()
        org.slf4j.LoggerFactory.getLogger("bloom").info("Arrêt propre : boucles stoppées, base fermée")
    }
    server.start(wait = true)
}

private val unsubscribeToken = Regex("^/u/[^/?]+")

/** Unsubscribe tokens identify a contact: they never reach the logs. */
private fun maskedUri(uri: String) = uri.replace(unsubscribeToken, "/u/***")

fun Application.module(ctx: AppContext) {
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
    val adminPassword = ctx.config.server.adminPassword.toByteArray()
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
    routing {
        // Public : la désinscription doit marcher sans compte, en un clic
        unsubscribeRoutes(ctx)
        rateLimit(RateLimitName("admin")) {
            authenticate("admin") {
                install(CrossSiteGuard)
                staticResources("/", "static")
                apiRoutes(ctx)
                discoveryRoutes(ctx)
            }
        }
        get("/health") { call.respondText("ok") }
        // Uptime probes often check with HEAD
        head("/health") { call.respond(HttpStatusCode.OK) }
    }
}
