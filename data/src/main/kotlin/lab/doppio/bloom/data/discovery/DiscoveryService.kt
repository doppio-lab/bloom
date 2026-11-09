package lab.doppio.bloom.data.discovery

import java.time.Clock
import java.time.Instant
import kotlinx.serialization.json.JsonObject
import lab.doppio.bloom.core.discovery.CompanyNames
import lab.doppio.bloom.core.discovery.Exclusions
import lab.doppio.bloom.core.discovery.SectorMapping
import lab.doppio.bloom.core.model.Account
import lab.doppio.bloom.core.model.AccountDiscoveredPayload
import lab.doppio.bloom.core.model.AppMeta
import lab.doppio.bloom.core.model.DiscoveryReport
import lab.doppio.bloom.core.model.EventType
import lab.doppio.bloom.core.model.Market
import lab.doppio.bloom.core.model.Sector
import lab.doppio.bloom.core.port.AccountRepository
import lab.doppio.bloom.core.port.Discovery
import lab.doppio.bloom.core.port.DiscoveryStateRepository
import lab.doppio.bloom.core.port.EventRepository
import lab.doppio.bloom.core.port.FactRepository
import lab.doppio.bloom.core.port.OfficerRepository
import lab.doppio.bloom.data.sources.discovery.CodeurSource
import lab.doppio.bloom.data.sources.facts.AppStoreSource
import lab.doppio.bloom.data.sources.facts.CompanyRegistrySource
import lab.doppio.bloom.data.sources.facts.FranceTravailSource
import lab.doppio.bloom.data.sources.facts.JobBoardSource
import org.slf4j.LoggerFactory

/**
 * Étape 1 du pipeline : trouver des entreprises qui ont un besoin mobile maintenant, sans que personne
 * ne les saisisse. Deux sources historiques, écrites ici, et des sources ajoutées par [DiscoverySource]
 * (BODACC, BOAMP, presse, GitHub), plus la relecture des pages carrières des comptes connus :
 *
 *  - les offres d'emploi mobiles publiées en France (API France Travail) : une entreprise qui recrute
 *    un développeur iOS ou Android est un prospect pour la régie, la formation KMP ou un forfait ;
 *  - les classements App Store France par genre : une app française dont les avis récents se dégradent
 *    est un prospect pour l'audit.
 *
 * Tout est déterministe : pas de LLM ici. Chaque compte découvert reçoit ses faits, ses signaux et un
 * score immédiat. Seuls les comptes au-dessus du seuil passent ensuite par l'agent de recherche (coûteux).
 */
class DiscoveryService(
    private val accountRepo: AccountRepository,
    private val discoveryState: DiscoveryStateRepository,
    private val eventRepo: EventRepository,
    private val factRepo: FactRepository,
    private val officerRepo: OfficerRepository,
    private val appStore: AppStoreSource,
    private val registry: CompanyRegistrySource,
    private val jobs: FranceTravailSource?,
    private val config: DiscoveryConfig = DiscoveryConfig(),
    private val sources: List<DiscoverySource> = emptyList(),
    private val jobBoards: JobBoardSource? = null,
    private val codeur: CodeurSource? = null,
    private val clock: Clock = Clock.systemDefaultZone(),
) : Discovery {
    private val log = LoggerFactory.getLogger(javaClass)
    private val ingest = AccountIngest(accountRepo, eventRepo, factRepo, registry)
    private val officerRefresher = OfficerRefresher(officerRepo, registry, config)
    private val jobBoardRefresher = JobBoardRefresher(accountRepo, discoveryState, ingest, config)
    private val demandPoller = CodeurDemandPoller(codeur, discoveryState)

    /** Names never prospected: the configured ones plus the blacklist kept in the dashboard, read at each run. */
    @Volatile private var blocked: Set<String> = config.extraExclusions

    override suspend fun run(): DiscoveryReport = run(clock.instant())

    suspend fun run(now: Instant): DiscoveryReport {
        blocked = config.extraExclusions + runCatching { accountRepo.blockedNames() }.getOrDefault(emptySet())
        // Before the sources, for the accounts already known; again at the end, for the ones this run creates
        runCatching { officerRefresher.refresh() }.onFailure { log.warn("Lecture des dirigeants en échec", it) }
        val ft = jobs
        val a = if (ft != null) runCatching { fromJobs(ft, now) }.getOrElse {
            log.warn("Découverte par les offres d'emploi en échec", it); DiscoveryReport(notes = listOf(DiscoveryNotes.jobsFailed(it.message)))
        } else DiscoveryReport(notes = listOf(DiscoveryNotes.JOBS_DISABLED))
        val b = runCatching { fromCharts(now) }.getOrElse {
            log.warn("Découverte par l'App Store en échec", it); DiscoveryReport(notes = listOf(DiscoveryNotes.chartsFailed(it.message)))
        }
        var report = a.copy(bySource = if (ft != null) mapOf(DiscoveryNotes.JOBS_LABEL to a.newAccounts) else emptyMap()) +
            b.copy(bySource = mapOf(DiscoveryNotes.CHARTS_LABEL to b.newAccounts))
        // One failing source never stops the others: it leaves a note and the run goes on
        for (source in sources) {
            report += runCatching { fromSource(source, now) }.getOrElse {
                log.warn("Découverte ${source.key} en échec", it); DiscoveryReport(notes = listOf(DiscoveryNotes.sourceFailed(source.label, it.message)))
            }
        }
        if (codeur != null) {
            report += runCatching { DiscoveryReport(bySource = mapOf(DiscoveryNotes.DEMANDS_LABEL to demandPoller.poll())) }.getOrElse {
                log.warn("Lecture de Codeur en échec", it); DiscoveryReport(notes = listOf(DiscoveryNotes.demandsFailed(it.message)))
            }
        }
        jobBoards?.let { boards ->
            report += runCatching { jobBoardRefresher.refresh(boards, now) }.getOrElse {
                log.warn("Lecture des pages carrières en échec", it); DiscoveryReport(notes = listOf(DiscoveryNotes.jobBoardsFailed(it.message)))
            }
        }
        runCatching { officerRefresher.refresh() }.onFailure { log.warn("Lecture des dirigeants en échec", it) }
        eventRepo.logEvent(EventType.DISCOVERY_RUN, payload = kotlinx.serialization.json.Json.encodeToString(DiscoveryReport.serializer(), report))
        return report
    }

    // --- Offres d'emploi ------------------------------------------------------------------------

    private suspend fun fromJobs(ft: FranceTravailSource, now: Instant): DiscoveryReport {
        val errors = mutableListOf<String>()
        val offers = config.jobKeywords
            .flatMap { kw ->
                runCatching { ft.search(kw, publieeDepuis = config.jobsPublishedWithinDays) }
                    .getOrElse { errors += it.message ?: it.javaClass.simpleName; emptyList() }
            }
            .distinctBy { ft.offerId(it) }
            .filter { ft.isMobileOffer(it) }
        // Every search failed (bad credentials, API down): say so instead of reporting zero offers
        if (offers.isEmpty() && errors.size == config.jobKeywords.size) error(errors.first())

        // Offres sans nom d'entreprise (confidentielles) et offres déjà traitées : ignorées
        val fresh = offers.filter { o -> ft.companyName(o) != null && ft.offerId(o)?.let { !discoveryState.wasSeen(SeenKeys.franceTravail(it), FranceTravailSource.RECHECK_AFTER_DAYS) } == true }
        val byCompany: Map<String, List<JsonObject>> = fresh.groupBy { CompanyNames.normalize(ft.companyName(it)!!) }

        var created = 0; var refreshed = 0; var excluded = 0
        for ((_, companyOffers) in byCompany) {
            val name = ft.companyName(companyOffers.first())!!
            val outcome = runCatching { handleHiringCompany(ft, name, companyOffers, now) }.getOrElse {
                log.warn("Entreprise ignorée après erreur : $name", it); Outcome.Skipped
            }
            when (outcome) {
                Outcome.Created -> created++
                Outcome.Refreshed -> refreshed++
                Outcome.Excluded -> excluded++
                Outcome.Skipped -> {}
            }
            // After an error the offers stay unseen, so the company gets another chance next night
            if (outcome != Outcome.Skipped) companyOffers.forEach { o -> ft.offerId(o)?.let { discoveryState.markSeen(SeenKeys.franceTravail(it), outcome != Outcome.Excluded, outcome.name) } }
        }
        return DiscoveryReport(created, refreshed, excluded, offersRead = offers.size)
    }

    private suspend fun handleHiringCompany(ft: FranceTravailSource, name: String, offers: List<JsonObject>, now: Instant): Outcome {
        Exclusions.reason(name, extra = blocked)?.let { return Outcome.Excluded }

        val company = registry.matches(name).firstOrNull()
        if (company != null) Exclusions.reason(company.name, company.naf, blocked)?.let { return Outcome.Excluded }

        val app: AppMeta? = appStore.searchByCompany(name).firstOrNull { a -> a.sellerName?.let { CompanyNames.sameCompany(it, name) } == true }
        val domain = CompanyNames.domainOf(ft.companyUrl(offers.first())) ?: CompanyNames.domainOf(app?.sellerUrl)

        val isNew = ingest.mergeAndPersist(
            Account(
                name = name,
                domain = domain,
                siren = company?.siren,
                sector = SectorMapping.fromNaf(company?.naf).takeIf { it != Sector.OTHER } ?: SectorMapping.fromGenre(app?.genreId),
                market = Market.FR,
                headcount = registry.headcountLowerBound(company?.headcountRange),
                iosAppId = app?.appId,
                source = DiscoveryNotes.JOBS_LABEL,
            ),
            company?.siren, AccountDiscoveredPayload("jobs", offers = offers.size), now,
        ) { accountId -> ft.factsFromOffers(accountId, offers, now) }
        return if (isNew) Outcome.Created else Outcome.Refreshed
    }

    // --- Classements App Store ------------------------------------------------------------------

    private suspend fun fromCharts(now: Instant): DiscoveryReport {
        val candidates = config.chartGenres
            .flatMap { g -> config.chartKinds.flatMap { kind -> runCatching { appStore.topChart(g, chart = kind) }.getOrDefault(emptyList()) } }
            .distinctBy { it.appId }
            .filter { !discoveryState.wasSeen(SeenKeys.app(it.appId), config.recheckAfterDays) }
            .take(config.appsPerRun)

        var created = 0; var refreshed = 0; var excluded = 0; var checked = 0
        for (app in candidates) {
            checked++
            val key = SeenKeys.app(app.appId)
            val byName = Exclusions.reason(app.artist, extra = blocked)
            if (byName != null) { discoveryState.markSeen(key, false, byName); excluded++; continue }
            // On ne garde que les éditeurs immatriculés en France : le classement français contient aussi Netflix ou Uber.
            val company = registry.matches(app.artist).firstOrNull()
            if (company == null) { discoveryState.markSeen(key, false, DiscoveryNotes.PUBLISHER_NOT_IN_REGISTRY); continue }
            val byRegistry = Exclusions.reason(company.name, company.naf, blocked)
            if (byRegistry != null) { discoveryState.markSeen(key, false, byRegistry); excluded++; continue }

            val analysis = runCatching { appStore.analyze(0, app.appId, now) }.getOrNull()
            if (analysis == null || analysis.signals.isEmpty()) {
                discoveryState.markSeen(key, false, DiscoveryNotes.NO_DIFFICULTY_SIGNAL); continue
            }
            val isNew = ingest.mergeAndPersist(
                Account(
                    name = app.artist,
                    domain = CompanyNames.domainOf(analysis.app?.sellerUrl),
                    siren = company.siren,
                    sector = SectorMapping.fromGenre(app.genreId).takeIf { it != Sector.OTHER } ?: SectorMapping.fromNaf(company.naf),
                    market = Market.FR,
                    headcount = registry.headcountLowerBound(company.headcountRange),
                    iosAppId = app.appId,
                    source = DiscoveryNotes.CHARTS_LABEL,
                ),
                company.siren, AccountDiscoveredPayload("appstore", appId = app.appId), now,
            ) { accountId ->
                analysis.copy(
                    facts = analysis.facts.map { it.copy(accountId = accountId) },
                    signals = analysis.signals.map { it.copy(accountId = accountId) },
                )
            }
            if (isNew) created++ else refreshed++
            discoveryState.markSeen(key, true, DiscoveryNotes.signalsFound(analysis.signals.joinToString { it.type.name }))
        }
        return DiscoveryReport(created, refreshed, excluded, appsChecked = checked)
    }

    // --- Sources ajoutées -------------------------------------------------------------------------

    private suspend fun fromSource(source: DiscoverySource, now: Instant): DiscoveryReport {
        val leads = source.leads(now).filter { !discoveryState.wasSeen(it.seenKey, source.recheckAfterDays) }.take(config.leadsPerSource)
        var created = 0; var refreshed = 0; var excluded = 0
        for (lead in leads) {
            when (runCatching { ingest(source, lead, now) }.getOrElse { log.warn("Piste ignorée après erreur : ${lead.name}", it); Outcome.Skipped }) {
                Outcome.Created -> created++
                Outcome.Refreshed -> refreshed++
                Outcome.Excluded -> excluded++
                Outcome.Skipped -> {}
            }
        }
        return DiscoveryReport(created, refreshed, excluded, bySource = mapOf(source.label to created))
    }

    /** Shared path of every added source: exclusions, French registry, thresholds, then account, facts and score. */
    private suspend fun ingest(source: DiscoverySource, lead: Lead, now: Instant): Outcome {
        val key = lead.seenKey
        Exclusions.reason(lead.name, extra = blocked)?.let { discoveryState.markSeen(key, false, it); return Outcome.Excluded }

        val company = if (lead.siren != null) registry.bySiren(lead.siren) else {
            val matches = registry.matches(lead.name)
            if (lead.requireUniqueMatch && matches.size > 1) {
                discoveryState.markSeen(key, false, DiscoveryNotes.AMBIGUOUS_NAME); return Outcome.Skipped
            }
            matches.firstOrNull()
        }
        if (company == null && lead.requireRegistry) {
            discoveryState.markSeen(key, false, DiscoveryNotes.NOT_IN_REGISTRY); return Outcome.Skipped
        }
        if (company != null) Exclusions.reason(company.name, company.naf, blocked)?.let {
            discoveryState.markSeen(key, false, it); return Outcome.Excluded
        }
        val headcount = registry.headcountLowerBound(company?.headcountRange)
        if (lead.minHeadcount != null && (headcount ?: 0) < lead.minHeadcount) {
            discoveryState.markSeen(key, false, DiscoveryNotes.belowMinHeadcount(lead.minHeadcount)); return Outcome.Skipped
        }
        var sector = SectorMapping.fromNaf(company?.naf)
        var app: AppMeta? = null
        if (lead.requireSectorOrApp && sector == Sector.OTHER) {
            app = appStore.searchByCompany(lead.name).firstOrNull { a -> a.sellerName?.let { CompanyNames.sameCompany(it, lead.name) } == true }
            if (app == null) { discoveryState.markSeen(key, false, DiscoveryNotes.NO_SECTOR_NOR_APP); return Outcome.Skipped }
            sector = SectorMapping.fromGenre(app.genreId)
        }

        val isNew = ingest.mergeAndPersist(
            Account(
                name = lead.name,
                domain = lead.domain ?: CompanyNames.domainOf(app?.sellerUrl),
                siren = company?.siren,
                sector = sector,
                market = Market.FR,
                headcount = headcount,
                iosAppId = app?.appId,
                source = source.label,
            ),
            company?.siren, AccountDiscoveredPayload(source.key), now,
        ) { accountId -> lead.build(accountId, now) }
        discoveryState.markSeen(key, true, source.key)
        return if (isNew) Outcome.Created else Outcome.Refreshed
    }

    override suspend fun pollDemands(): Int = demandPoller.poll()

    /** Reads the legal officers of the accounts that have a SIREN and were never looked up. */
    suspend fun refreshOfficers(): Int = officerRefresher.refresh()
}
