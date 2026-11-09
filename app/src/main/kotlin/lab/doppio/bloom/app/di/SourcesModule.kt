package lab.doppio.bloom.app.di

import io.ktor.client.HttpClient
import lab.doppio.bloom.app.config.BloomConfig
import lab.doppio.bloom.core.port.AppStoreFacts
import lab.doppio.bloom.core.port.CompanyRegistryFacts
import lab.doppio.bloom.core.port.Discovery
import lab.doppio.bloom.core.port.DiscoveryStateRepository
import lab.doppio.bloom.core.port.EmailFinder
import lab.doppio.bloom.core.port.JobOfferFacts
import lab.doppio.bloom.core.port.WebPages
import lab.doppio.bloom.core.usecase.FindContactEmail
import lab.doppio.bloom.data.discovery.DiscoveryConfig
import lab.doppio.bloom.data.discovery.DiscoveryService
import lab.doppio.bloom.data.discovery.DiscoverySource
import lab.doppio.bloom.data.sources.discovery.BoampSource
import lab.doppio.bloom.data.sources.discovery.BodaccSource
import lab.doppio.bloom.data.sources.discovery.CodeurSource
import lab.doppio.bloom.data.sources.discovery.DecpSource
import lab.doppio.bloom.data.sources.discovery.GitHubSource
import lab.doppio.bloom.data.sources.discovery.PressSource
import lab.doppio.bloom.data.sources.discovery.TedSource
import lab.doppio.bloom.data.sources.facts.AppStoreSource
import lab.doppio.bloom.data.sources.facts.CompanyRegistrySource
import lab.doppio.bloom.data.sources.facts.DropcontactSource
import lab.doppio.bloom.data.sources.facts.FranceTravailSource
import lab.doppio.bloom.data.sources.facts.JobBoardSource
import lab.doppio.bloom.data.sources.facts.WebPageSource
import lab.doppio.bloom.data.sources.http.defaultHttpClient
import org.koin.core.module.Module
import org.koin.core.module.dsl.bind
import org.koin.dsl.bind
import org.koin.dsl.module
import org.koin.dsl.onClose
import java.time.Clock

/**
 * HTTP clients of the fact sources, and the discovery service built from the sources DISCOVERY_SOURCES turns on.
 * The press feeds are left out of the documented default: their reuse terms have to be checked by the owner.
 */
fun sourcesModule(config: BloomConfig): Module = module {
    val discovery = config.discovery
    single<HttpClient> { defaultHttpClient(config.httpTimeoutSeconds * 1_000) } onClose { it?.close() }
    single { AppStoreSource(get(), clock = get()) } bind AppStoreFacts::class
    // PlayStoreSource (data/sources/facts) is deliberately not bound: Google Play's terms on automated reading
    // have not been checked. ResearchAgent runs without it.
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
            BodaccSource(http, minCapitalEur = discovery.bodaccMinCapitalEur, clock = clock).takeIf { BodaccSource.KEY in enabled },
            BoampSource(http).takeIf { BoampSource.KEY in enabled },
            TedSource(http).takeIf { TedSource.KEY in enabled },
            DecpSource(
                http,
                buyerName = { siren -> get<CompanyRegistrySource>().bySiren(siren)?.name },
                wasSeen = { discoveryState.wasSeen(it, DiscoverySource.DEFAULT_RECHECK_DAYS) },
                markSeen = { key, kept, reason -> discoveryState.markSeen(key, kept, reason) },
                maxMonths = discovery.decpRenewalMonths,
                clock = clock,
            ).takeIf { DecpSource.KEY in enabled },
            PressSource(http).takeIf { PressSource.KEY in enabled },
            GitHubSource(
                http, discovery.githubToken,
                wasSeen = { discoveryState.wasSeen(it, GitHubSource.RECHECK_AFTER_DAYS) },
                markSeen = { key, kept, reason -> discoveryState.markSeen(key, kept, reason) },
                clock = clock,
            ).takeIf { GitHubSource.KEY in enabled },
        )
    }
    single {
        DiscoveryService(
            accountRepo = get(), discoveryState = get(), eventRepo = get(), factRepo = get(), officerRepo = get(),
            appStore = get(), registry = get(),
            jobs = getOrNull<FranceTravailSource>(),
            config = DiscoveryConfig(
                jobsPublishedWithinDays = discovery.jobsWithinDays,
                appsPerRun = discovery.appsPerRun,
                recheckAfterDays = discovery.recheckAfterDays,
                leadsPerSource = discovery.leadsPerSource,
                jobBoardLookupsPerRun = discovery.jobBoardLookupsPerRun,
                officerLookupsPerRun = discovery.officerLookupsPerRun,
                // Current clients and partners never to prospect
                extraExclusions = discovery.extraExclusions,
            ),
            sources = get(),
            jobBoards = JobBoardSource(get(), get()).takeIf { JobBoardSource.KEY in discovery.sources },
            codeur = CodeurSource(get(), minBudgetEur = discovery.codeurMinBudgetEur).takeIf { CodeurSource.KEY in discovery.sources },
            clock = get(),
        )
    } bind Discovery::class
}
