package lab.doppio.bloom.agents

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.executeStructured
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import lab.doppio.bloom.core.model.Account
import lab.doppio.bloom.core.model.AccountResearchedPayload
import lab.doppio.bloom.core.model.Dossier
import lab.doppio.bloom.core.model.EventType
import lab.doppio.bloom.core.model.Fact
import lab.doppio.bloom.core.model.Offer
import lab.doppio.bloom.core.model.Signal
import lab.doppio.bloom.core.guard.MessageGuard
import lab.doppio.bloom.core.model.latestPerTopic
import lab.doppio.bloom.core.port.AccountRepository
import lab.doppio.bloom.core.port.AppStoreFacts
import lab.doppio.bloom.core.port.PlayStoreFacts
import lab.doppio.bloom.core.port.CompanyRegistryFacts
import lab.doppio.bloom.core.port.EventRepository
import lab.doppio.bloom.core.port.FactRepository
import lab.doppio.bloom.core.port.JobOfferFacts
import lab.doppio.bloom.core.port.Researcher
import lab.doppio.bloom.core.port.WebPages
import lab.doppio.bloom.core.scoring.ScoreBreakdown
import lab.doppio.bloom.core.scoring.Scoring
import org.slf4j.LoggerFactory
import java.time.Clock

/**
 * Agent de recherche, en trois temps :
 *  1. Collecte déterministe (App Store, France Travail, annuaire) : faits et signaux sans LLM.
 *  2. Exploration du site par un agent Koog outillé, qui ne peut garder que des citations vérifiées mot pour mot.
 *  3. Synthèse structurée du dossier (résumé, hypothèses reliées aux faits, angle, offre).
 * Puis recalcul du score, déterministe.
 */
class ResearchAgent(
    private val llm: Llm,
    private val accountRepo: AccountRepository,
    private val eventRepo: EventRepository,
    private val factRepo: FactRepository,
    private val tracker: RunTracker,
    private val appStore: AppStoreFacts,
    private val registry: CompanyRegistryFacts,
    private val jobs: JobOfferFacts?,
    private val web: WebPages,
    private val playStore: PlayStoreFacts? = null,
    private val clock: Clock = Clock.systemDefaultZone(),
) : Researcher {
    private val log = LoggerFactory.getLogger(javaClass)

    override suspend fun research(accountId: Long): ScoreBreakdown {
        val account = accountRepo.account(accountId) ?: error("Compte $accountId introuvable")

        // 1. Sources déterministes. Chaque source échoue isolément.
        val facts = mutableListOf<Fact>()
        val signals = mutableListOf<Signal>()
        account.iosAppId?.let { id ->
            runCatching { appStore.analyze(account.id, id, clock.instant()) }
                .onSuccess { facts += it.facts; signals += it.signals }
                .onFailure { log.warn("App Store indisponible pour ${account.name}", it) }
        }
        account.androidPackage?.let { pkg ->
            playStore?.let { p ->
                runCatching { p.analyze(account.id, pkg, clock.instant()) }
                    .onSuccess { facts += it.facts; signals += it.signals }
                    .onFailure { log.warn("Google Play indisponible pour ${account.name}", it) }
            }
        }
        account.siren?.let { siren ->
            runCatching { registry.facts(account.id, siren) }.onSuccess { facts += it }.onFailure { log.warn("Annuaire indisponible", it) }
        }
        jobs?.let { j ->
            runCatching { j.mobileJobs(account.id, account.name, clock.instant()) }
                .onSuccess { facts += it.facts; signals += it.signals }
                .onFailure { log.warn("France Travail indisponible", it) }
        }
        factRepo.saveFacts(facts)
        factRepo.saveSignals(signals)

        // 2. Exploration du site, outillée et vérifiée (seulement si le site est connu)
        val domain = account.domain
        if (domain != null) {
            val siteTools = SiteResearchTools(account.id, domain, web, clock)
            runCatching {
                val tokens = TokenCounter()
                val agent = AIAgent(
                    promptExecutor = llm.executor,
                    llmModel = llm.research,
                    systemPrompt = Prompts.researcher,
                    toolRegistry = ToolRegistry { tools(siteTools) },
                    maxIterations = ResearchLimits.MAX_ITERATIONS,
                    installFeatures = tokens.feature,
                )
                tracker.trackAgent(AgentName.RESEARCH_EXPLORE, account.id, llm.research, tokens) {
                    agent.run(Prompts.researchTask(account.name, domain))
                }
            }.onFailure { log.warn("Exploration du site échouée pour ${account.name}", it) }
            factRepo.saveFacts(siteTools.recorded)
            factRepo.saveSignals(siteTools.signals)
        }

        // 3. Synthèse
        val allFacts = factRepo.factsOf(account.id).latestPerTopic()
        if (allFacts.isNotEmpty()) synthesize(account, allFacts)

        val score = Scoring.score(account, factRepo.signalsOf(account.id), clock.instant(), factRepo.factsOf(account.id))
        accountRepo.saveScore(score)
        eventRepo.logEvent(EventType.ACCOUNT_RESEARCHED, accountId = account.id, payload = AccountResearchedPayload(score.total).toJson())
        return score
    }

    @Serializable
    @SerialName("AccountDossier")
    @LLMDescription(OutputTexts.DOSSIER)
    data class DossierOutput(
        @property:LLMDescription(OutputTexts.DOSSIER_SUMMARY)
        val summary: String,
        @property:LLMDescription(OutputTexts.DOSSIER_HYPOTHESES)
        val hypotheses: List<Hypothesis>,
        @property:LLMDescription(OutputTexts.DOSSIER_ANGLE)
        val angle: String,
        @property:LLMDescription(OutputTexts.DOSSIER_OFFER)
        val offer: String,
        @property:LLMDescription(OutputTexts.DOSSIER_DISQUALIFIERS)
        val disqualifiers: List<String>,
    )

    @Serializable
    @SerialName("Hypothesis")
    data class Hypothesis(
        @property:LLMDescription(OutputTexts.HYPOTHESIS_STATEMENT)
        val statement: String,
        @property:LLMDescription(OutputTexts.HYPOTHESIS_FACT_IDS)
        val factIds: List<String>,
    )

    private suspend fun synthesize(account: Account, facts: List<Fact>) {
        val known = facts.associateBy { it.id }
        val output = tracker.track(AgentName.RESEARCH_SYNTHESIS, account.id, llm.research) {
            val r = llm.executor.executeStructured<DossierOutput>(
                prompt = prompt("dossier-${account.id}") {
                    system(Prompts.synthesis)
                    user(Prompts.synthesisInput(account, facts))
                },
                model = llm.research,
            ).getOrThrow()
            r.data to r
        }
        val grounding = facts.joinToString(" ") { it.statement + " " + it.excerpt.orEmpty() }
        val hypotheses = provenHypotheses(output.hypotheses.filter { ungroundedNumbers(it.statement, grounding).isEmpty() }, known.keys)
        val summary = withoutUngroundedSentences(output.summary, grounding)
        // An angle with a number no fact carries is not kept: the best proven hypothesis stands in for it
        val angle = output.angle.takeIf { ungroundedNumbers(it, grounding).isEmpty() }
            ?: hypotheses.firstOrNull()?.let { MessageGuard.stripCitations(it) }.orEmpty()
        val offer = parseOffer(output.offer)
        factRepo.saveDossier(
            Dossier(
                accountId = account.id,
                summary = OutputTexts.disqualifiedSummary(summary, output.disqualifiers),
                hypotheses = hypotheses,
                angle = angle,
                offer = offer ?: Offer.DISCOVERY,
                noFit = offer == null,
            ),
        )
        if (offer == null) eventRepo.logEvent(EventType.DOSSIER_NO_FIT, accountId = account.id)
    }
}
