package lab.doppio.bloom.agents

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.executeStructured
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import lab.doppio.bloom.core.model.Account
import lab.doppio.bloom.core.model.Fact
import lab.doppio.bloom.core.model.FactKind
import lab.doppio.bloom.core.model.Offer
import lab.doppio.bloom.core.model.Signal
import lab.doppio.bloom.core.model.SignalType
import lab.doppio.bloom.core.scoring.ScoreBreakdown
import lab.doppio.bloom.core.scoring.Scoring
import lab.doppio.bloom.data.db.Dossier
import lab.doppio.bloom.data.db.Repository
import lab.doppio.bloom.data.sources.AppStoreSource
import lab.doppio.bloom.data.sources.CompanyRegistrySource
import lab.doppio.bloom.data.sources.FranceTravailSource
import lab.doppio.bloom.data.sources.WebPageSource
import lab.doppio.bloom.data.sources.fact
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * Agent de recherche, en trois temps :
 *  1. Collecte déterministe (App Store, France Travail, annuaire) : faits et signaux sans LLM.
 *  2. Exploration du site par un agent Koog outillé, qui ne peut garder que des citations vérifiées mot pour mot.
 *  3. Synthèse structurée du dossier (résumé, hypothèses reliées aux faits, angle, offre).
 * Puis recalcul du score, déterministe.
 */
class ResearchAgent(
    private val llm: Llm,
    private val repo: Repository,
    private val tracker: RunTracker,
    private val appStore: AppStoreSource,
    private val registry: CompanyRegistrySource,
    private val jobs: FranceTravailSource?,
    private val web: WebPageSource,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    suspend fun research(accountId: Long): ScoreBreakdown {
        val account = repo.account(accountId) ?: error("Compte $accountId introuvable")

        // 1. Sources déterministes. Chaque source échoue isolément.
        val facts = mutableListOf<Fact>()
        val signals = mutableListOf<Signal>()
        account.iosAppId?.let { id ->
            runCatching { appStore.analyze(account.id, id) }
                .onSuccess { facts += it.facts; signals += it.signals }
                .onFailure { log.warn("App Store indisponible pour ${account.name}", it) }
        }
        account.siren?.let { siren ->
            runCatching { registry.facts(account.id, siren) }.onSuccess { facts += it }.onFailure { log.warn("Annuaire indisponible", it) }
        }
        jobs?.let { j ->
            runCatching { j.mobileJobs(account.id, account.name) }
                .onSuccess { facts += it.facts; signals += it.signals }
                .onFailure { log.warn("France Travail indisponible", it) }
        }
        repo.saveFacts(facts)
        repo.saveSignals(signals)

        // 2. Exploration du site, outillée et vérifiée (seulement si le site est connu)
        val domain = account.domain
        if (domain != null) {
            val siteTools = SiteResearchTools(account.id, domain, web)
            runCatching {
                val agent = AIAgent(
                    promptExecutor = llm.executor,
                    llmModel = llm.research,
                    systemPrompt = Prompts.researcher,
                    toolRegistry = ToolRegistry { tools(siteTools) },
                    maxIterations = 20,
                )
                tracker.track("research-explore", account.id, llm.research) {
                    agent.run(
                        "Entreprise : ${account.name}, site https://$domain. " +
                            "Commence par la page d'accueil, puis cherche une page carrières et un blog tech.",
                    ) to null
                }
            }.onFailure { log.warn("Exploration du site échouée pour ${account.name}", it) }
            repo.saveFacts(siteTools.recorded)
            repo.saveSignals(siteTools.signals)
        }

        // 3. Synthèse
        val allFacts = repo.factsOf(account.id)
        if (allFacts.isNotEmpty()) synthesize(account, allFacts)

        val score = Scoring.score(account, repo.signalsOf(account.id), Instant.now())
        repo.saveScore(score)
        repo.logEvent("ACCOUNT_RESEARCHED", accountId = account.id, payload = """{"score":${score.total}}""")
        return score
    }

    @Serializable
    @SerialName("AccountDossier")
    @LLMDescription("Dossier de prospection d'un compte")
    data class DossierOutput(
        @property:LLMDescription("Résumé factuel, 3 phrases maximum")
        val summary: String,
        @property:LLMDescription("1 à 3 hypothèses de besoin, chacune avec les identifiants des faits qui l'appuient")
        val hypotheses: List<Hypothesis>,
        @property:LLMDescription("Angle d'approche en une phrase, centré sur le problème du prospect")
        val angle: String,
        @property:LLMDescription("Offre Doppio recommandée : DISCOVERY, FORFAIT, REGIE, TRAINING_KMP, TRAINING_ANTHROPIC ou NONE")
        val offer: String,
        @property:LLMDescription("Raisons de ne pas contacter ce compte, liste vide s'il n'y en a pas")
        val disqualifiers: List<String>,
    )

    @Serializable
    @SerialName("Hypothesis")
    data class Hypothesis(
        @property:LLMDescription("L'hypothèse, formulée prudemment")
        val statement: String,
        @property:LLMDescription("Identifiants des faits qui l'appuient")
        val factIds: List<String>,
    )

    private suspend fun synthesize(account: Account, facts: List<Fact>) {
        val known = facts.associateBy { it.id }
        val factList = facts.joinToString("\n") { "[${it.id}] (${it.kind}) ${it.statement}" + (it.excerpt?.let { e -> "\n    extrait : ${e.take(300)}" } ?: "") }
        val output = tracker.track("research-synthesis", account.id, llm.research) {
            val r = llm.executor.executeStructured<DossierOutput>(
                prompt = prompt("dossier-${account.id}") {
                    system(Prompts.synthesis)
                    user("Compte : ${account.name} (${account.domain ?: "site inconnu"}), secteur ${account.sector}, marché ${account.market}.\nFaits :\n$factList")
                },
                model = llm.research,
            ).getOrThrow()
            r.data to r
        }
        // Les hypothèses qui citent des faits inexistants sont écartées : pas d'hypothèse sans preuve.
        val hypotheses = output.hypotheses
            .filter { h -> h.factIds.isNotEmpty() && h.factIds.all { it in known } }
            .map { h -> "${h.statement} " + h.factIds.joinToString(" ") { "[F:$it]" } }
        val offer = Offer.entries.firstOrNull { it.name == output.offer.uppercase() }
        repo.saveDossier(
            Dossier(
                accountId = account.id,
                summary = output.summary + (if (output.disqualifiers.isNotEmpty()) "\nÀ ne pas contacter si : " + output.disqualifiers.joinToString("; ") else ""),
                hypotheses = hypotheses,
                angle = output.angle,
                offer = offer ?: Offer.DISCOVERY,
            ),
        )
        if (offer == null) repo.logEvent("DOSSIER_NO_FIT", accountId = account.id)
    }
}

/**
 * Outils exposés à l'agent d'exploration. L'agent lit des pages et propose des citations ;
 * le code vérifie qu'elles existent mot pour mot avant d'en faire des faits.
 */
@LLMDescription("Outils pour lire le site public d'une entreprise et enregistrer des citations vérifiées")
class SiteResearchTools(private val accountId: Long, private val domain: String, private val web: WebPageSource) : ToolSet {
    private val pages = mutableMapOf<String, String>()
    val recorded = mutableListOf<Fact>()
    val signals = mutableListOf<Signal>()
    private val maxPages = 6

    private val sameSite = Regex("^https?://([a-z0-9-]+\\.)*${Regex.escape(domain)}(/.*)?$", RegexOption.IGNORE_CASE)

    @Tool
    @LLMDescription("Lit une page du site de l'entreprise et renvoie son texte visible (tronqué) et ses liens internes")
    suspend fun readPage(
        @LLMDescription("URL complète, sur le domaine de l'entreprise uniquement")
        url: String,
    ): String {
        if (!sameSite.matches(url)) return "Refusé : seules les pages de $domain sont autorisées."
        if (pages.size >= maxPages && url !in pages) return "Limite de $maxPages pages atteinte. Termine ton analyse."
        val (_, text) = web.read(accountId, url) ?: return "Page illisible : $url"
        pages[url] = text
        return text.take(5_000)
    }

    @Tool
    @LLMDescription("Enregistre une citation exacte d'une page déjà lue, comme fait sourcé. Refusée si la citation n'est pas trouvée mot pour mot.")
    fun recordQuote(
        @LLMDescription("URL de la page déjà lue avec readPage")
        url: String,
        @LLMDescription("Citation exacte, copiée de la page, 300 caractères maximum")
        quote: String,
        @LLMDescription("Ce que la citation établit, en une phrase neutre")
        meaning: String,
        @LLMDescription("Catégorie : STACK, MOBILE_PROJECT, HIRING, AI_USAGE, APP_ISSUE, OTHER")
        category: String,
    ): String {
        val page = pages[url] ?: return "Refusé : lis d'abord la page avec readPage."
        val normalized = quote.trim().replace(Regex("\\s+"), " ")
        if (normalized.length !in 12..300) return "Refusé : la citation doit faire entre 12 et 300 caractères."
        if (!page.replace(Regex("\\s+"), " ").contains(normalized, ignoreCase = true)) {
            return "Refusé : citation introuvable mot pour mot sur la page. Copie le texte exact."
        }
        val f = fact(accountId, FactKind.WEB_PAGE, "$meaning (« $normalized »)", url, excerpt = normalized)
        recorded += f
        when (category.uppercase()) {
            "AI_USAGE" -> signals += Signal(accountId, SignalType.AI_ADOPTION, 0.6, Instant.now().toString(), listOf(f.id))
            "MOBILE_PROJECT" -> signals += Signal(accountId, SignalType.PRODUCT_LAUNCH, 0.6, Instant.now().toString(), listOf(f.id))
            "APP_ISSUE" -> signals += Signal(accountId, SignalType.QUALITY_COMPLAINTS, 0.4, Instant.now().toString(), listOf(f.id))
        }
        return "Enregistré sous l'identifiant ${f.id}."
    }
}
