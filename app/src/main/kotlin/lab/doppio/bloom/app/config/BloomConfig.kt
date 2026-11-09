package lab.doppio.bloom.app.config

import lab.doppio.bloom.agents.LlmConfig
import lab.doppio.bloom.agents.LlmProvider
import lab.doppio.bloom.agents.RunTracker
import lab.doppio.bloom.app.outreach.LoopIntervals
import lab.doppio.bloom.app.outreach.MailConfig
import lab.doppio.bloom.app.outreach.MailTexts
import lab.doppio.bloom.core.model.Mailbox
import lab.doppio.bloom.core.model.SendingPolicy
import lab.doppio.bloom.core.discovery.Decp
import lab.doppio.bloom.core.usecase.ResearchBatch
import lab.doppio.bloom.data.discovery.DiscoveryConfig
import lab.doppio.bloom.data.sources.http.REQUEST_TIMEOUT_MILLIS
import lab.doppio.bloom.data.persistence.DbConfig
import lab.doppio.bloom.data.sources.discovery.BoampSource
import lab.doppio.bloom.data.sources.discovery.BodaccSource
import lab.doppio.bloom.data.sources.discovery.CodeurSource
import lab.doppio.bloom.data.sources.discovery.DecpSource
import lab.doppio.bloom.data.sources.discovery.GitHubSource
import lab.doppio.bloom.data.sources.discovery.PressSource
import lab.doppio.bloom.data.sources.discovery.TedSource
import lab.doppio.bloom.data.sources.facts.JobBoardSource
import kotlin.time.Duration.Companion.minutes

/** HTTP server and the dashboard login. */
data class ServerConfig(val port: Int, val adminPassword: String)

/** LLM client settings plus the daily token cap (0: no limit). */
data class LlmSettings(val client: LlmConfig, val dailyTokenBudget: Long)

/** Sending limits and the name signed at the bottom of the drafts. */
data class SendingConfig(val policy: SendingPolicy, val senderName: String)

/** France Travail API credentials (the secret is required as soon as the id is set). */
data class FranceTravailCredentials(val clientId: String, val clientSecret: String)

/** How many accounts one research batch handles, and the age after which a dossier is read again. */
data class ResearchSettings(val batchSize: Int, val staleDays: Long)

/**
 * Discovery knobs. [sources] is the set chosen by DISCOVERY_SOURCES, lowercased. The press feeds are in the
 * default list for now, even though their reuse terms still have to be checked by the owner.
 */
data class DiscoverySettings(
    val sources: Set<String>,
    val appsPerRun: Int,
    /** Days before an already examined App Store app is looked at again. */
    val recheckAfterDays: Long,
    /** Leads handled per added source and per run. */
    val leadsPerSource: Int,
    val jobBoardLookupsPerRun: Int,
    val officerLookupsPerRun: Int,
    /** Publication window of the France Travail offers, one of [DiscoveryConfig.ALLOWED_JOBS_WITHIN_DAYS]. */
    val jobsWithinDays: Int,
    /** Current clients and partners never to prospect. */
    val extraExclusions: Set<String>,
    val bodaccMinCapitalEur: Long,
    val decpRenewalMonths: Long,
    val codeurMinBudgetEur: Int,
    val githubToken: String?,
    val franceTravail: FranceTravailCredentials?,
    /** Optional: without a Dropcontact key, contacts are entered by hand. */
    val dropcontactApiKey: String?,
)

/**
 * Everything Bloom reads from the environment, parsed once and immutable. [from] is the only place that touches
 * variable names, defaults and validation: it throws on the first invalid value, before anything else starts.
 */
data class BloomConfig(
    val server: ServerConfig,
    val database: DbConfig,
    val llm: LlmSettings,
    val mail: MailConfig,
    val sending: SendingConfig,
    val discovery: DiscoverySettings,
    val research: ResearchSettings,
    val loops: LoopIntervals,
    /** Timeout of every request to a public source, in seconds. */
    val httpTimeoutSeconds: Long,
) {
    companion object {
        val DEFAULT_DISCOVERY_SOURCES = listOf(
            BodaccSource.KEY, BoampSource.KEY, TedSource.KEY, DecpSource.KEY, CodeurSource.KEY, PressSource.KEY, GitHubSource.KEY, JobBoardSource.KEY,
        ).joinToString(",")

        fun from(env: Map<String, String>): BloomConfig {
            val e = EnvReader(env)
            // Secrets and DRY_RUN first: an unusable one must stop the server before anything else is looked at
            val adminPassword = requireSecret("ADMIN_PASSWORD", env["ADMIN_PASSWORD"])
            val unsubscribeSecret = requireSecret("UNSUBSCRIBE_SECRET", env["UNSUBSCRIBE_SECRET"])
            val dryRun = parseFlag("DRY_RUN", env["DRY_RUN"], default = true)

            val provider = LlmProvider.parse(e.value("LLM_PROVIDER", "anthropic"))
            val llm = LlmSettings(
                client = LlmConfig(
                    provider = provider,
                    apiKey = when (provider) {
                        LlmProvider.ANTHROPIC -> e.optional("ANTHROPIC_API_KEY") ?: error("ANTHROPIC_API_KEY est obligatoire avec LLM_PROVIDER=anthropic")
                        LlmProvider.OPENAI -> e.optional("OPENAI_API_KEY")
                        LlmProvider.OLLAMA -> null
                    },
                    baseUrl = when (provider) {
                        LlmProvider.ANTHROPIC -> null
                        LlmProvider.OPENAI -> e.optional("OPENAI_BASE_URL")
                        LlmProvider.OLLAMA -> e.optional("OLLAMA_BASE_URL")
                    },
                    model = e.optional("LLM_MODEL"),
                    fastModel = e.optional("LLM_MODEL_FAST"),
                    contextLength = e.value("LLM_CONTEXT_LENGTH", LlmConfig.DEFAULT_CONTEXT_LENGTH.toString()).toLong(),
                ),
                dailyTokenBudget = e.optional("LLM_DAILY_TOKEN_BUDGET")?.toLong() ?: RunTracker.NO_TOKEN_LIMIT,
            )

            val mail = MailConfig(
                smtpHost = e.value("SMTP_HOST", "smtp.gmail.com"),
                smtpPort = e.value("SMTP_PORT", "587").toInt(),
                imapHost = e.value("IMAP_HOST", "imap.gmail.com"),
                mailboxes = parseMailboxes(e.value("MAILBOXES", "")),
                publicBaseUrl = e.value("PUBLIC_BASE_URL", "http://localhost:8080"),
                unsubscribeSecret = unsubscribeSecret,
                legalFooterFr = e.value(
                    "LEGAL_FOOTER_FR",
                    MailTexts.DEFAULT_LEGAL_FOOTER_FR,
                ),
                legalFooterEn = e.value(
                    "LEGAL_FOOTER_EN",
                    MailTexts.DEFAULT_LEGAL_FOOTER_EN,
                ),
                dryRun = dryRun,
            )

            val discovery = DiscoverySettings(
                sources = e.value("DISCOVERY_SOURCES", DEFAULT_DISCOVERY_SOURCES).split(',').map { it.trim().lowercase() }.toSet(),
                appsPerRun = e.value("DISCOVERY_APPS_PER_RUN", DiscoveryConfig.DEFAULT_APPS_PER_RUN.toString()).toInt(),
                recheckAfterDays = e.positiveLong("DISCOVERY_RECHECK_DAYS", DiscoveryConfig.DEFAULT_RECHECK_DAYS),
                leadsPerSource = e.positiveInt("DISCOVERY_LEADS_PER_SOURCE", DiscoveryConfig.DEFAULT_LEADS_PER_SOURCE),
                jobBoardLookupsPerRun = e.positiveInt("DISCOVERY_JOB_BOARD_LOOKUPS", DiscoveryConfig.DEFAULT_JOB_BOARD_LOOKUPS),
                officerLookupsPerRun = e.positiveInt("DISCOVERY_OFFICER_LOOKUPS", DiscoveryConfig.DEFAULT_OFFICER_LOOKUPS),
                jobsWithinDays = e.positiveInt("DISCOVERY_JOBS_WITHIN_DAYS", DiscoveryConfig.DEFAULT_JOBS_WITHIN_DAYS).also {
                    check(it in DiscoveryConfig.ALLOWED_JOBS_WITHIN_DAYS) {
                        "DISCOVERY_JOBS_WITHIN_DAYS doit valoir ${DiscoveryConfig.ALLOWED_JOBS_WITHIN_DAYS.sorted().joinToString(", ")} (France Travail n'accepte que ces fenêtres) : $it"
                    }
                },
                extraExclusions = e.value("DISCOVERY_EXCLUDE", "").split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
                bodaccMinCapitalEur = e.value("BODACC_MIN_CAPITAL", BodaccSource.DEFAULT_MIN_CAPITAL_EUR.toString()).toLong(),
                decpRenewalMonths = e.value("DECP_RENEWAL_MONTHS", Decp.RENEWAL_MAX_MONTHS.toString()).toLong(),
                codeurMinBudgetEur = e.value("CODEUR_MIN_BUDGET", CodeurSource.DEFAULT_MIN_BUDGET_EUR.toString()).toInt(),
                githubToken = e.optional("GITHUB_TOKEN"),
                franceTravail = e.optional("FT_CLIENT_ID")?.let { FranceTravailCredentials(it, e.value("FT_CLIENT_SECRET")) },
                dropcontactApiKey = e.optional("DROPCONTACT_API_KEY"),
            )

            return BloomConfig(
                server = ServerConfig(port = e.value("PORT", "8080").toInt(), adminPassword = adminPassword),
                database = DbConfig(
                    e.value("DATABASE_URL", "jdbc:postgresql://localhost:5433/bloom"),
                    e.value("DB_USER", "bloom"),
                    e.value("DB_PASSWORD", "bloom"),
                    maxPool = e.positiveInt("DB_MAX_POOL", DbConfig.DEFAULT_MAX_POOL),
                ),
                llm = llm,
                mail = mail,
                sending = SendingConfig(
                    policy = SendingPolicy(maxPerMailboxPerDay = e.value("MAX_PER_MAILBOX_PER_DAY", SendingPolicy.DEFAULT_MAX_PER_MAILBOX_PER_DAY.toString()).toInt()),
                    senderName = e.value("SENDER_NAME", MailTexts.DEFAULT_SENDER_NAME),
                ),
                discovery = discovery,
                research = ResearchSettings(
                    batchSize = e.positiveInt("RESEARCH_BATCH_SIZE", ResearchBatch.DEFAULT_BATCH_SIZE),
                    staleDays = e.positiveLong("RESEARCH_STALE_DAYS", ResearchBatch.DEFAULT_STALE_DAYS),
                ),
                loops = LoopIntervals(
                    plan = e.positiveInt("LOOP_PLAN_MINUTES", LoopIntervals.DEFAULT_PLAN.inWholeMinutes.toInt()).minutes,
                    send = e.positiveInt("LOOP_SEND_MINUTES", LoopIntervals.DEFAULT_SEND.inWholeMinutes.toInt()).minutes,
                    replies = e.positiveInt("LOOP_REPLIES_MINUTES", LoopIntervals.DEFAULT_REPLIES.inWholeMinutes.toInt()).minutes,
                    periodic = e.positiveInt("LOOP_PERIODIC_MINUTES", LoopIntervals.DEFAULT_PERIODIC.inWholeMinutes.toInt()).minutes,
                ),
                httpTimeoutSeconds = e.positiveLong("HTTP_TIMEOUT_SECONDS", REQUEST_TIMEOUT_MILLIS / 1_000),
            )
        }

        /** MAILBOXES="prenom@doppio-studio.fr:motdepasseapplication:Prénom Nom,..." (the name may itself contain colons). */
        internal fun parseMailboxes(raw: String): List<Mailbox> = raw.split(',').filter { it.isNotBlank() }.map {
            val (address, password, name) = it.split(':', limit = 3)
            Mailbox(address.trim(), password.trim(), name.trim())
        }
    }
}
