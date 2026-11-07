package lab.doppio.bloom.app.di

import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import lab.doppio.bloom.agents.Llm
import lab.doppio.bloom.agents.ReplyClassifierAgent
import lab.doppio.bloom.agents.ResearchAgent
import lab.doppio.bloom.agents.RunTracker
import lab.doppio.bloom.agents.WeeklyCoachAgent
import lab.doppio.bloom.agents.WriterAgent
import lab.doppio.bloom.app.config.BloomConfig
import lab.doppio.bloom.app.outreach.EmailGateway
import lab.doppio.bloom.app.outreach.ImapPoller
import lab.doppio.bloom.app.outreach.MailConfig
import lab.doppio.bloom.app.outreach.Scheduler
import lab.doppio.bloom.app.outreach.UnsubscribeTokens
import lab.doppio.bloom.app.web.ApiRoutes
import lab.doppio.bloom.app.web.DiscoveryRoutes
import lab.doppio.bloom.app.web.UnsubscribeRoutes
import lab.doppio.bloom.core.model.SendingPolicy
import lab.doppio.bloom.core.port.AccountRepository
import lab.doppio.bloom.core.port.AgentRunRepository
import lab.doppio.bloom.core.port.AppStoreFacts
import lab.doppio.bloom.core.port.Coach
import lab.doppio.bloom.core.port.CompanyRegistryFacts
import lab.doppio.bloom.core.port.ContactRepository
import lab.doppio.bloom.core.port.DealRepository
import lab.doppio.bloom.core.port.Discovery
import lab.doppio.bloom.core.port.DiscoveryStateRepository
import lab.doppio.bloom.core.port.DraftRepository
import lab.doppio.bloom.core.port.Drafter
import lab.doppio.bloom.core.port.EmailFinder
import lab.doppio.bloom.core.port.EventRepository
import lab.doppio.bloom.core.port.FactRepository
import lab.doppio.bloom.core.port.Inbox
import lab.doppio.bloom.core.port.InstanceLock
import lab.doppio.bloom.core.port.JobOfferFacts
import lab.doppio.bloom.core.port.JobRunRepository
import lab.doppio.bloom.core.port.MailSender
import lab.doppio.bloom.core.port.OfficerRepository
import lab.doppio.bloom.core.port.ReplyClassifier
import lab.doppio.bloom.core.port.ReplyRepository
import lab.doppio.bloom.core.port.Researcher
import lab.doppio.bloom.core.port.SequenceRepository
import lab.doppio.bloom.core.port.WebPages
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
import lab.doppio.bloom.data.discovery.DiscoveryConfig
import lab.doppio.bloom.data.discovery.DiscoveryService
import lab.doppio.bloom.data.discovery.DiscoverySource
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
import lab.doppio.bloom.data.sources.facts.AppStoreSource
import lab.doppio.bloom.data.sources.discovery.BoampSource
import lab.doppio.bloom.data.sources.discovery.BodaccSource
import lab.doppio.bloom.data.sources.discovery.CodeurSource
import lab.doppio.bloom.data.sources.facts.CompanyRegistrySource
import lab.doppio.bloom.data.sources.discovery.DecpSource
import lab.doppio.bloom.data.sources.facts.DropcontactSource
import lab.doppio.bloom.data.sources.facts.FranceTravailSource
import lab.doppio.bloom.data.sources.discovery.GitHubSource
import lab.doppio.bloom.data.sources.facts.JobBoardSource
import lab.doppio.bloom.data.sources.discovery.PressSource
import lab.doppio.bloom.data.sources.discovery.TedSource
import lab.doppio.bloom.data.sources.facts.WebPageSource
import lab.doppio.bloom.data.sources.http.defaultHttpClient
import org.koin.core.module.Module
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.singleOf
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module
import org.koin.dsl.onClose
import java.time.Clock

/*
 * One Koin module per layer. They live in `app` only: core, data and agents are plain Kotlin and never see
 * the container. Implementations are bound to the port types, so a test replaces a port by declaring a
 * module after these ones (Koin lets the last definition win with allowOverride).
 *
 * Optional components (France Travail, Dropcontact, the sources chosen by DISCOVERY_SOURCES) are not
 * injected as nulls: the module functions take the immutable BloomConfig, so a component that is switched
 * off is simply not declared, and its single consumer asks for it with getOrNull. Koin has no nullable
 * definitions, and this keeps the graph honest: what is declared is what runs.
 */

/** Scope for work started from the dashboard (discovery, research). */
val BACKGROUND = named("background")

/** Scope of the scheduler loops. */
val LOOPS = named("loops")

/** The configuration, cut into the pieces components ask for, and the clock. */
fun configModule(config: BloomConfig, clock: Clock = Clock.systemDefaultZone()): Module = module {
    single { config }
    single { clock }
    single<MailConfig> { config.mail }
    single<SendingPolicy> { config.sending.policy }
}

/** Exposed repositories and the instance lock, each bound to its core port. */
val persistenceModule: Module = module {
    singleOf(::ExposedAccountRepository) { bind<AccountRepository>() }
    singleOf(::ExposedOfficerRepository) { bind<OfficerRepository>() }
    singleOf(::ExposedContactRepository) { bind<ContactRepository>() }
    singleOf(::ExposedFactRepository) { bind<FactRepository>() }
    singleOf(::ExposedSequenceRepository) { bind<SequenceRepository>() }
    singleOf(::ExposedDraftRepository) { bind<DraftRepository>() }
    singleOf(::ExposedReplyRepository) { bind<ReplyRepository>() }
    singleOf(::ExposedDealRepository) { bind<DealRepository>() }
    singleOf(::ExposedJobRunRepository) { bind<JobRunRepository>() }
    singleOf(::ExposedEventRepository) { bind<EventRepository>() }
    singleOf(::ExposedAgentRunRepository) { bind<AgentRunRepository>() }
    singleOf(::ExposedDiscoveryStateRepository) { bind<DiscoveryStateRepository>() }
    singleOf(::PostgresInstanceLock) { bind<InstanceLock>() }
}

/**
 * HTTP clients of the fact sources, and the discovery service built from the sources DISCOVERY_SOURCES turns on.
 * The press feeds are left out of the documented default: their reuse terms have to be checked by the owner.
 */
fun sourcesModule(config: BloomConfig): Module = module {
    val discovery = config.discovery
    single<HttpClient> { defaultHttpClient() } onClose { it?.close() }
    single { AppStoreSource(get(), clock = get()) } bind AppStoreFacts::class
    single { CompanyRegistrySource(get(), get()) } bind CompanyRegistryFacts::class
    single { WebPageSource(get(), get()) } bind WebPages::class

    // Optional: without France Travail credentials the job offer facts and signals are simply absent
    discovery.franceTravail?.let { ft ->
        single { FranceTravailSource(get(), ft.clientId, ft.clientSecret, get()) } bind JobOfferFacts::class
    }
    // Optional: without a Dropcontact key, contacts are entered by hand (name, email or LinkedIn profile)
    discovery.dropcontactApiKey?.let { key ->
        single { DropcontactSource(get(), key) } bind EmailFinder::class
        single { FindContactEmail(get(), get(), get(), get()) }
    }

    single<List<DiscoverySource>> {
        val enabled = discovery.sources
        val discoveryState = get<DiscoveryStateRepository>()
        val http = get<HttpClient>()
        val clock = get<Clock>()
        listOfNotNull(
            BodaccSource(http, minCapitalEur = discovery.bodaccMinCapitalEur, clock = clock).takeIf { "bodacc" in enabled },
            BoampSource(http).takeIf { "boamp" in enabled },
            TedSource(http).takeIf { "ted" in enabled },
            DecpSource(
                http,
                buyerName = { siren -> get<CompanyRegistrySource>().bySiren(siren)?.name },
                wasSeen = { discoveryState.wasSeen(it, 365) },
                markSeen = { key, kept, reason -> discoveryState.markSeen(key, kept, reason) },
                maxMonths = discovery.decpRenewalMonths,
                clock = clock,
            ).takeIf { "decp" in enabled },
            PressSource(http).takeIf { "press" in enabled },
            GitHubSource(
                http, discovery.githubToken,
                wasSeen = { discoveryState.wasSeen(it, 90) },
                markSeen = { key, kept, reason -> discoveryState.markSeen(key, kept, reason) },
                clock = clock,
            ).takeIf { "github" in enabled },
        )
    }
    single {
        DiscoveryService(
            accountRepo = get(), discoveryState = get(), eventRepo = get(), factRepo = get(), officerRepo = get(),
            appStore = get(), registry = get(),
            jobs = getOrNull<FranceTravailSource>(),
            config = DiscoveryConfig(
                appsPerRun = discovery.appsPerRun,
                // Current clients and partners never to prospect
                extraExclusions = discovery.extraExclusions,
            ),
            sources = get(),
            jobBoards = JobBoardSource(get(), get()).takeIf { "jobboards" in discovery.sources },
            codeur = CodeurSource(get(), minBudgetEur = discovery.codeurMinBudgetEur).takeIf { "codeur" in discovery.sources },
            clock = get(),
        )
    } bind Discovery::class
}

/** The LLM, the run tracker and the Koog agents, bound to their role ports. */
val agentsModule: Module = module {
    single { Llm.from(get<BloomConfig>().llm.client) }
    single { RunTracker(get(), dailyTokenBudget = get<BloomConfig>().llm.dailyTokenBudget, clock = get()) }
    single {
        ResearchAgent(
            get(), get(), get(), get(), get(),
            appStore = get(), registry = get(), jobs = getOrNull<JobOfferFacts>(), web = get(), clock = get(),
        )
    } bind Researcher::class
    single { WriterAgent(get(), get(), get(), get(), get(), get(), get(), senderName = get<BloomConfig>().sending.senderName) } bind Drafter::class
    singleOf(::WeeklyCoachAgent) { bind<Coach>() }
    singleOf(::ReplyClassifierAgent) { bind<ReplyClassifier>() }
}

/** Sending and receiving mail: unsubscribe tokens, SMTP gateway, IMAP poller. */
val mailModule: Module = module {
    single { UnsubscribeTokens(get<MailConfig>().unsubscribeSecret) }
    singleOf(::EmailGateway) { bind<MailSender>() }
    singleOf(::ImapPoller) { bind<Inbox>() }
}

/** What the application does. Free of Ktor and of the database, they only see ports. */
val useCasesModule: Module = module {
    singleOf(::StopContactSequences)
    singleOf(::UnsubscribeContact)
    singleOf(::PlanDrafts)
    single { SendNextApproved(get(), get(), get(), get(), get(), get(), get<MailConfig>().mailboxes, get(), get()) }
    singleOf(::ProcessReplies)
    single { ResearchBatch(get(), get(), get(), get()) }
    singleOf(::RunDiscovery)
    singleOf(::RunPeriodicJobs)
    // A new enrollment wakes the planner. Resolved on use: the scheduler is declared in its own module.
    single { EnrollContact(get(), get(), get(), get(), get(), afterEnrolled = { get<Scheduler>().planSoon() }) }
    singleOf(::AddContact)
    singleOf(::DecideDraft)
    singleOf(::CompleteTask)
    singleOf(::ReviewQueue)
    single {
        val mail = get<MailConfig>()
        GetOverview(get(), get(), get(), get(), get(), get(), get(), get(), get(), mail.dryRun, mail.mailboxes.size)
    }
    singleOf(::ContactBook)
    singleOf(::ListToComplete)
    singleOf(::ImportAccounts)
}

/** The background loops and the two scopes they and the dashboard run in. Closing Koin cancels both. */
val schedulerModule: Module = module {
    single(LOOPS) { CoroutineScope(SupervisorJob() + Dispatchers.Default) } onClose { it?.cancel() }
    single(BACKGROUND) { CoroutineScope(SupervisorJob() + Dispatchers.Default) } onClose { it?.cancel() }
    singleOf(::Scheduler)
}

/** HTTP routes. Each group declares what it needs in its constructor, so a missing binding fails at startup. */
val webModule: Module = module {
    single {
        ApiRoutes(
            overview = get(), accountRepo = get(), contactRepo = get(), factRepo = get(), enrollContact = get(),
            reviewQueue = get(), decideDraft = get(), completeTask = get(), replyRepo = get(), dealRepo = get(),
            eventRepo = get(), research = get(), background = get(BACKGROUND),
        )
    }
    single {
        DiscoveryRoutes(
            runDiscovery = get(), researchBatch = get(), eventRepo = get(), toComplete = get(), addContact = get(),
            accountRepo = get(), discoveryState = get(), contactBook = get(), importAccounts = get(),
            // Declared only when a Dropcontact key is configured
            findContactEmail = getOrNull<FindContactEmail>(),
            background = get(BACKGROUND),
        )
    }
    singleOf(::UnsubscribeRoutes)
}

/** Every module of the running application, in the order the layers depend on each other. */
fun bloomModules(config: BloomConfig, clock: Clock = Clock.systemDefaultZone()): List<Module> = listOf(
    configModule(config, clock), persistenceModule, sourcesModule(config), agentsModule, mailModule,
    useCasesModule, schedulerModule, webModule,
)
