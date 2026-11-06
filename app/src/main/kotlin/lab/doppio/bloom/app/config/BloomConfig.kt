package lab.doppio.bloom.app.config

import lab.doppio.bloom.agents.LlmConfig
import lab.doppio.bloom.agents.LlmProvider
import lab.doppio.bloom.app.outreach.MailConfig
import lab.doppio.bloom.app.outreach.Mailbox
import lab.doppio.bloom.core.model.SendingPolicy
import lab.doppio.bloom.data.db.DbConfig

/** HTTP server and the dashboard login. */
data class ServerConfig(val port: Int, val adminPassword: String)

/** LLM client settings plus the daily token cap (0: no limit). */
data class LlmSettings(val client: LlmConfig, val dailyTokenBudget: Long)

/** Sending limits and the name signed at the bottom of the drafts. */
data class SendingConfig(val policy: SendingPolicy, val senderName: String)

/** France Travail API credentials (the secret is required as soon as the id is set). */
data class FranceTravailCredentials(val clientId: String, val clientSecret: String)

/**
 * Discovery knobs. [sources] is the set chosen by DISCOVERY_SOURCES, lowercased. The press feeds are in the
 * default list for now, even though their reuse terms still have to be checked by the owner.
 */
data class DiscoverySettings(
    val sources: Set<String>,
    val appsPerRun: Int,
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
) {
    companion object {
        const val DEFAULT_DISCOVERY_SOURCES = "bodacc,boamp,ted,decp,codeur,press,github,jobboards"

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
                    contextLength = e.value("LLM_CONTEXT_LENGTH", "16384").toLong(),
                ),
                dailyTokenBudget = e.optional("LLM_DAILY_TOKEN_BUDGET")?.toLong() ?: 0,
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
                    "Doppio Lab (Advisely), Paris. Vos coordonnées professionnelles proviennent de sources publiques " +
                        "(site de votre entreprise, annuaire des entreprises) et ne servent qu'à ce contact.",
                ),
                legalFooterEn = e.value(
                    "LEGAL_FOOTER_EN",
                    "Doppio Lab (Advisely), Paris, France. Your business contact details come from public sources and are used only for this contact.",
                ),
                dryRun = dryRun,
            )

            val discovery = DiscoverySettings(
                sources = e.value("DISCOVERY_SOURCES", DEFAULT_DISCOVERY_SOURCES).split(',').map { it.trim().lowercase() }.toSet(),
                appsPerRun = e.value("DISCOVERY_APPS_PER_RUN", "400").toInt(),
                extraExclusions = e.value("DISCOVERY_EXCLUDE", "").split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
                bodaccMinCapitalEur = e.value("BODACC_MIN_CAPITAL", "100000").toLong(),
                decpRenewalMonths = e.value("DECP_RENEWAL_MONTHS", "12").toLong(),
                codeurMinBudgetEur = e.value("CODEUR_MIN_BUDGET", "1000").toInt(),
                githubToken = e.optional("GITHUB_TOKEN"),
                franceTravail = e.optional("FT_CLIENT_ID")?.let { FranceTravailCredentials(it, e.value("FT_CLIENT_SECRET")) },
                dropcontactApiKey = e.optional("DROPCONTACT_API_KEY"),
            )

            return BloomConfig(
                server = ServerConfig(port = e.value("PORT", "8080").toInt(), adminPassword = adminPassword),
                database = DbConfig(
                    e.value("DATABASE_URL", "jdbc:postgresql://localhost:5432/bloom"),
                    e.value("DB_USER", "bloom"),
                    e.value("DB_PASSWORD", "bloom"),
                ),
                llm = llm,
                mail = mail,
                sending = SendingConfig(
                    policy = SendingPolicy(maxPerMailboxPerDay = e.value("MAX_PER_MAILBOX_PER_DAY", "25").toInt()),
                    senderName = e.value("SENDER_NAME", "l'équipe Doppio"),
                ),
                discovery = discovery,
            )
        }

        /** MAILBOXES="prenom@doppio-studio.fr:motdepasseapplication:Prénom Nom,..." (the name may itself contain colons). */
        internal fun parseMailboxes(raw: String): List<Mailbox> = raw.split(',').filter { it.isNotBlank() }.map {
            val (address, password, name) = it.split(':', limit = 3)
            Mailbox(address.trim(), password.trim(), name.trim())
        }
    }
}

/** Reads variables from a map. An empty value for a defaulted variable is kept as is, like System.getenv. */
internal class EnvReader(private val env: Map<String, String>) {
    /** The value, else [default], else an error naming the variable. */
    fun value(name: String, default: String? = null): String =
        env[name] ?: default ?: error("Variable d'environnement manquante : $name")

    /** An empty value in .env (KEY=) counts as an absent key. */
    fun optional(name: String): String? = env[name]?.takeIf { it.isNotBlank() }
}
