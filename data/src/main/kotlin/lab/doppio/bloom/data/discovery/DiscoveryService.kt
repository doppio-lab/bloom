package lab.doppio.bloom.data.discovery

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import lab.doppio.bloom.core.discovery.CompanyNames
import lab.doppio.bloom.core.discovery.Exclusions
import lab.doppio.bloom.core.discovery.SectorMapping
import lab.doppio.bloom.core.model.Account
import lab.doppio.bloom.core.model.Market
import lab.doppio.bloom.core.model.Sector
import lab.doppio.bloom.core.scoring.Scoring
import lab.doppio.bloom.data.db.Demand
import lab.doppio.bloom.data.db.OfficerRow
import lab.doppio.bloom.data.db.Repository
import lab.doppio.bloom.data.sources.AppMeta
import lab.doppio.bloom.data.sources.AppStoreSource
import lab.doppio.bloom.data.sources.CodeurSource
import lab.doppio.bloom.data.sources.CompanyRegistrySource
import lab.doppio.bloom.data.sources.FranceTravailSource
import lab.doppio.bloom.data.sources.JobBoardSource
import lab.doppio.bloom.data.sources.SourceResult
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.temporal.ChronoUnit

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
@Serializable
data class DiscoveryReport(
    val newAccounts: Int = 0,
    val refreshedAccounts: Int = 0,
    val excluded: Int = 0,
    val offersRead: Int = 0,
    val appsChecked: Int = 0,
    val notes: List<String> = emptyList(),
    /** New accounts per source label, in the order the sources ran. */
    val bySource: Map<String, Int> = emptyMap(),
) {
    operator fun plus(other: DiscoveryReport) = DiscoveryReport(
        newAccounts + other.newAccounts, refreshedAccounts + other.refreshedAccounts, excluded + other.excluded,
        offersRead + other.offersRead, appsChecked + other.appsChecked, notes + other.notes, bySource + other.bySource,
    )
}

data class DiscoveryConfig(
    val jobKeywords: List<String> = listOf(
        "développeur iOS", "développeur Android", "développeur mobile", "Swift iOS", "Kotlin Android", "Kotlin Multiplatform",
    ),
    /** Fenêtre de publication des offres lues (1, 3, 7, 14 ou 31 jours). */
    val jobsPublishedWithinDays: Int = 7,
    val chartGenres: List<Int> = listOf(
        6015, 6024, 6023, 6003, 6010, 6013, 6020, 6000, 6007, 6011, 6012, 6004,
        // News, education, entertainment, utilities, social, reference, weather, books, photo
        6009, 6017, 6016, 6002, 6005, 6006, 6001, 6018, 6008,
    ),
    /** Charts read for each genre: the free apps and the apps that earn the most (100 apps each). */
    val chartKinds: List<String> = listOf("topfreeapplications", "topgrossingapplications"),
    /** Apps analysées par nuit (environ 5 secondes chacune avec la limite de débit Apple). */
    val appsPerRun: Int = 400,
    /** Une app déjà examinée n'est revue qu'après ce délai. */
    val recheckAfterDays: Long = 30,
    /** Noms à ne jamais prospecter en plus de la liste intégrée (clients actuels, partenaires, concurrents). */
    val extraExclusions: Set<String> = emptySet(),
    /** Leads handled per added source and per run: each one costs registry and sometimes App Store calls. */
    val leadsPerSource: Int = 60,
    /** Company sites searched for a job board per run. Boards already known are always read. */
    val jobBoardLookupsPerRun: Int = 30,
    /** Accounts whose legal officers are read from the registry per run, for the contact book. */
    val officerLookupsPerRun: Int = 150,
)

class DiscoveryService(
    private val repo: Repository,
    private val appStore: AppStoreSource,
    private val registry: CompanyRegistrySource,
    private val jobs: FranceTravailSource?,
    private val config: DiscoveryConfig = DiscoveryConfig(),
    private val sources: List<DiscoverySource> = emptyList(),
    private val jobBoards: JobBoardSource? = null,
    private val codeur: CodeurSource? = null,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Names never prospected: the configured ones plus the blacklist kept in the dashboard, read at each run. */
    @Volatile private var blocked: Set<String> = config.extraExclusions

    private companion object {
        const val JOBS_LABEL = "offres d'emploi"
        const val CHARTS_LABEL = "classement App Store"
        const val DEMANDS_LABEL = "Codeur"
    }

    suspend fun run(now: Instant = Instant.now()): DiscoveryReport {
        blocked = config.extraExclusions + runCatching { repo.blockedNames() }.getOrDefault(emptySet())
        // Before the sources, for the accounts already known; again at the end, for the ones this run creates
        runCatching { refreshOfficers() }.onFailure { log.warn("Lecture des dirigeants en échec", it) }
        val ft = jobs
        val a = if (ft != null) runCatching { fromJobs(ft, now) }.getOrElse {
            log.warn("Découverte par les offres d'emploi en échec", it); DiscoveryReport(notes = listOf("offres d'emploi : ${it.message}"))
        } else DiscoveryReport(notes = listOf("offres d'emploi désactivées : FT_CLIENT_ID absent"))
        val b = runCatching { fromCharts(now) }.getOrElse {
            log.warn("Découverte par l'App Store en échec", it); DiscoveryReport(notes = listOf("App Store : ${it.message}"))
        }
        var report = a.copy(bySource = if (ft != null) mapOf(JOBS_LABEL to a.newAccounts) else emptyMap()) +
            b.copy(bySource = mapOf(CHARTS_LABEL to b.newAccounts))
        // One failing source never stops the others: it leaves a note and the run goes on
        for (source in sources) {
            report += runCatching { fromSource(source, now) }.getOrElse {
                log.warn("Découverte ${source.key} en échec", it); DiscoveryReport(notes = listOf("${source.label} : ${it.message}"))
            }
        }
        if (codeur != null) {
            report += runCatching { DiscoveryReport(bySource = mapOf(DEMANDS_LABEL to pollDemands())) }.getOrElse {
                log.warn("Lecture de Codeur en échec", it); DiscoveryReport(notes = listOf("$DEMANDS_LABEL : ${it.message}"))
            }
        }
        jobBoards?.let { boards ->
            report += runCatching { refreshJobBoards(boards, now) }.getOrElse {
                log.warn("Lecture des pages carrières en échec", it); DiscoveryReport(notes = listOf("pages carrières : ${it.message}"))
            }
        }
        runCatching { refreshOfficers() }.onFailure { log.warn("Lecture des dirigeants en échec", it) }
        repo.logEvent("DISCOVERY_RUN", payload = kotlinx.serialization.json.Json.encodeToString(DiscoveryReport.serializer(), report))
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
        val fresh = offers.filter { o -> ft.companyName(o) != null && ft.offerId(o)?.let { !repo.wasSeen("ft:$it", 365) } == true }
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
            if (outcome != Outcome.Skipped) companyOffers.forEach { o -> ft.offerId(o)?.let { repo.markSeen("ft:$it", outcome != Outcome.Excluded, outcome.name) } }
        }
        return DiscoveryReport(created, refreshed, excluded, offersRead = offers.size)
    }

    private enum class Outcome { Created, Refreshed, Excluded, Skipped }

    private suspend fun handleHiringCompany(ft: FranceTravailSource, name: String, offers: List<JsonObject>, now: Instant): Outcome {
        Exclusions.reason(name, extra = blocked)?.let { return Outcome.Excluded }

        val company = registry.matches(name).firstOrNull()
        if (company != null) Exclusions.reason(company.name, company.naf, blocked)?.let { return Outcome.Excluded }

        val app: AppMeta? = appStore.searchByCompany(name).firstOrNull { a -> a.sellerName?.let { CompanyNames.sameCompany(it, name) } == true }
        val domain = CompanyNames.domainOf(ft.companyUrl(offers.first())) ?: CompanyNames.domainOf(app?.sellerUrl)

        val (accountId, isNew) = repo.mergeDiscovered(
            Account(
                name = name,
                domain = domain,
                siren = company?.siren,
                sector = SectorMapping.fromNaf(company?.naf).takeIf { it != Sector.OTHER } ?: SectorMapping.fromGenre(app?.genreId),
                market = Market.FR,
                headcount = registry.headcountLowerBound(company?.headcountRange),
                iosAppId = app?.appId,
                source = JOBS_LABEL,
            ),
        )
        val result = ft.factsFromOffers(accountId, offers, now)
        persist(accountId, result, now)
        if (isNew) {
            company?.siren?.let { repo.saveFacts(registry.facts(accountId, it)) }
            repo.logEvent("ACCOUNT_DISCOVERED", accountId, payload = """{"source":"jobs","offers":${offers.size}}""")
        }
        return if (isNew) Outcome.Created else Outcome.Refreshed
    }

    // --- Classements App Store ------------------------------------------------------------------

    private suspend fun fromCharts(now: Instant): DiscoveryReport {
        val candidates = config.chartGenres
            .flatMap { g -> config.chartKinds.flatMap { kind -> runCatching { appStore.topChart(g, chart = kind) }.getOrDefault(emptyList()) } }
            .distinctBy { it.appId }
            .filter { !repo.wasSeen("app:${it.appId}", config.recheckAfterDays) }
            .take(config.appsPerRun)

        var created = 0; var refreshed = 0; var excluded = 0; var checked = 0
        for (app in candidates) {
            checked++
            val key = "app:${app.appId}"
            val byName = Exclusions.reason(app.artist, extra = blocked)
            if (byName != null) { repo.markSeen(key, false, byName); excluded++; continue }
            // On ne garde que les éditeurs immatriculés en France : le classement français contient aussi Netflix ou Uber.
            val company = registry.matches(app.artist).firstOrNull()
            if (company == null) { repo.markSeen(key, false, "éditeur non trouvé dans l'annuaire français"); continue }
            val byRegistry = Exclusions.reason(company.name, company.naf, blocked)
            if (byRegistry != null) { repo.markSeen(key, false, byRegistry); excluded++; continue }

            val analysis = runCatching { appStore.analyze(0, app.appId, now) }.getOrNull()
            if (analysis == null || analysis.signals.isEmpty()) {
                repo.markSeen(key, false, "aucun signal de difficulté"); continue
            }
            val (accountId, isNew) = repo.mergeDiscovered(
                Account(
                    name = app.artist,
                    domain = CompanyNames.domainOf(analysis.app?.sellerUrl),
                    siren = company.siren,
                    sector = SectorMapping.fromGenre(app.genreId).takeIf { it != Sector.OTHER } ?: SectorMapping.fromNaf(company.naf),
                    market = Market.FR,
                    headcount = registry.headcountLowerBound(company.headcountRange),
                    iosAppId = app.appId,
                    source = CHARTS_LABEL,
                ),
            )
            persist(accountId, analysis.copy(
                facts = analysis.facts.map { it.copy(accountId = accountId) },
                signals = analysis.signals.map { it.copy(accountId = accountId) },
            ), now)
            if (isNew) {
                repo.saveFacts(registry.facts(accountId, company.siren))
                repo.logEvent("ACCOUNT_DISCOVERED", accountId, payload = """{"source":"appstore","appId":${app.appId}}""")
                created++
            } else refreshed++
            repo.markSeen(key, true, "signaux : ${analysis.signals.joinToString { it.type.name }}")
        }
        return DiscoveryReport(created, refreshed, excluded, appsChecked = checked)
    }

    // --- Sources ajoutées -------------------------------------------------------------------------

    private suspend fun fromSource(source: DiscoverySource, now: Instant): DiscoveryReport {
        val leads = source.leads(now).filter { !repo.wasSeen(it.seenKey, source.recheckAfterDays) }.take(config.leadsPerSource)
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
        Exclusions.reason(lead.name, extra = blocked)?.let { repo.markSeen(key, false, it); return Outcome.Excluded }

        val company = if (lead.siren != null) registry.bySiren(lead.siren) else {
            val matches = registry.matches(lead.name)
            if (lead.requireUniqueMatch && matches.size > 1) {
                repo.markSeen(key, false, "plusieurs entreprises portent ce nom"); return Outcome.Skipped
            }
            matches.firstOrNull()
        }
        if (company == null && lead.requireRegistry) {
            repo.markSeen(key, false, "non trouvé dans l'annuaire français"); return Outcome.Skipped
        }
        if (company != null) Exclusions.reason(company.name, company.naf, blocked)?.let {
            repo.markSeen(key, false, it); return Outcome.Excluded
        }
        val headcount = registry.headcountLowerBound(company?.headcountRange)
        if (lead.minHeadcount != null && (headcount ?: 0) < lead.minHeadcount) {
            repo.markSeen(key, false, "effectif inférieur à ${lead.minHeadcount}"); return Outcome.Skipped
        }
        var sector = SectorMapping.fromNaf(company?.naf)
        var app: AppMeta? = null
        if (lead.requireSectorOrApp && sector == Sector.OTHER) {
            app = appStore.searchByCompany(lead.name).firstOrNull { a -> a.sellerName?.let { CompanyNames.sameCompany(it, lead.name) } == true }
            if (app == null) { repo.markSeen(key, false, "ni secteur couvert ni app sur l'App Store"); return Outcome.Skipped }
            sector = SectorMapping.fromGenre(app.genreId)
        }

        val (accountId, isNew) = repo.mergeDiscovered(
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
        )
        persist(accountId, lead.build(accountId, now), now)
        if (isNew) {
            company?.siren?.let { repo.saveFacts(registry.facts(accountId, it)) }
            repo.logEvent("ACCOUNT_DISCOVERED", accountId, payload = """{"source":"${source.key}"}""")
        }
        repo.markSeen(key, true, source.key)
        return if (isNew) Outcome.Created else Outcome.Refreshed
    }

    // --- Demandes publiées (Codeur) -----------------------------------------------------------------

    /**
     * Reads the Codeur feed and keeps the new mobile projects. The feed only holds the latest projects,
     * so this is also called between two nightly runs. Returns how many were new.
     */
    suspend fun pollDemands(): Int {
        val source = codeur ?: return 0
        return source.mobileProjects().count { p ->
            repo.saveDemand(Demand("codeur:${p.id}", DEMANDS_LABEL, p.title, p.url, p.budget, p.categories, p.publishedAt?.toString()))
        }
    }

    // --- Pages carrières des comptes connus ---------------------------------------------------------

    /** Finds the hosted job board of known accounts (once a month) and reads their mobile postings (every run). */
    private suspend fun refreshJobBoards(boards: JobBoardSource, now: Instant): DiscoveryReport {
        val stale = now.minus(30, ChronoUnit.DAYS)
        var lookups = 0; var read = 0; var withMobile = 0
        for (row in repo.accountsByScore(500)) {
            val domain = row.account.domain ?: continue
            val id = row.account.id
            val known = repo.jobBoard(id)
            val ref = if (known == null || known.second.isBefore(stale)) {
                if (lookups++ >= config.jobBoardLookupsPerRun) continue
                boards.find(domain).also { repo.saveJobBoard(id, it) }
            } else known.first
            if (ref == null) continue
            read++
            val result = boards.result(id, boards.postings(ref), now)
            if (result.facts.isNotEmpty()) { persist(id, result, now); withMobile++ }
        }
        return DiscoveryReport(refreshedAccounts = withMobile, notes = listOf("pages carrières : $read lues, $withMobile avec une offre mobile"))
    }

    // --- Dirigeants (carnet de contacts) --------------------------------------------------------------

    /**
     * Reads the legal officers of the accounts that have a SIREN and were never looked up. They feed the
     * contact book as suggestions: nobody is written to before a person picks them.
     */
    suspend fun refreshOfficers(): Int {
        var read = 0
        for ((accountId, siren) in repo.accountsWithoutOfficers(config.officerLookupsPerRun)) {
            val officers = runCatching { registry.officers(siren) }.getOrNull() ?: continue
            repo.saveOfficers(accountId, officers.map { OfficerRow(it.fullName, it.role, registry.pageUrl(siren)) })
            read++
        }
        return read
    }

    /** Enregistre faits et signaux, puis recalcule le score tout de suite (sans LLM). */
    private suspend fun persist(accountId: Long, result: SourceResult, now: Instant) {
        repo.saveFacts(result.facts)
        repo.saveSignals(result.signals)
        val account = repo.account(accountId) ?: return
        repo.saveScore(Scoring.score(account, repo.signalsOf(accountId), now))
    }
}
