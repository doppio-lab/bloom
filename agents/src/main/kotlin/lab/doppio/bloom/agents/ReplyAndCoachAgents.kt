package lab.doppio.bloom.agents

import lab.doppio.bloom.core.port.AccountRepository
import lab.doppio.bloom.core.port.AgentRunRepository
import lab.doppio.bloom.core.port.Coach
import lab.doppio.bloom.core.port.DealRepository
import lab.doppio.bloom.core.port.ReplyClassifier
import lab.doppio.bloom.core.port.EventRepository
import java.time.Clock
import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.executeStructured
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import lab.doppio.bloom.core.model.ReplyClassification
import lab.doppio.bloom.core.model.ReplyIntent
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Classifieur de réponses. Modèle rapide, sortie structurée.
 * Les décisions qui comptent (arrêt de séquence, opposition) sont prises par le code, pas par le LLM.
 */
class ReplyClassifierAgent(private val llm: Llm, private val tracker: RunTracker, private val clock: Clock = Clock.systemDefaultZone()) : ReplyClassifier {

    @Serializable
    @SerialName("ReplyClassification")
    data class Classification(
        @property:LLMDescription("Intention : INTERESTED, MEETING_REQUEST, NOT_NOW, WRONG_PERSON, REFERRAL, NOT_INTERESTED, UNSUBSCRIBE, OUT_OF_OFFICE, BOUNCE, OTHER")
        val intent: String,
        @property:LLMDescription("Confiance entre 0 et 1")
        val confidence: Double,
        @property:LLMDescription("Date de recontact ou de retour au format AAAA-MM-JJ, ou chaîne vide")
        val followUpOn: String,
        @property:LLMDescription("Personne recommandée (nom et rôle) si REFERRAL ou WRONG_PERSON, sinon chaîne vide")
        val referral: String,
        @property:LLMDescription("Résumé factuel en une phrase")
        val summary: String,
        @property:LLMDescription("Proposition de réponse courte si une réponse humaine est attendue, sinon chaîne vide")
        val suggestedReply: String,
    )

    override suspend fun classify(subject: String?, body: String, accountId: Long?): ReplyClassification {
        val today = LocalDate.now(clock)
        val trimmed = trimQuoted(body)
        if (isHardStop(trimmed)) {
            return ReplyClassification(ReplyIntent.UNSUBSCRIBE, 1.0, null, null, "Demande d'arrêt détectée par mot-clé", null)
        }
        val c = tracker.track("reply-classifier", accountId, llm.fast) {
            val r = llm.executor.executeStructured<Classification>(
                prompt = prompt("reply") {
                    system(Prompts.replyClassifier + "\nDate du jour : $today.")
                    user("Objet : ${subject.orEmpty()}\n\n$trimmed")
                },
                model = llm.fast,
            ).getOrThrow()
            r.data to r
        }
        val intent = ReplyIntent.entries.firstOrNull { it.name == c.intent.uppercase() } ?: ReplyIntent.OTHER
        return ReplyClassification(
            intent = intent,
            confidence = c.confidence.coerceIn(0.0, 1.0),
            followUpOn = runCatching { LocalDate.parse(c.followUpOn) }.getOrNull(),
            referral = c.referral.ifBlank { null },
            summary = c.summary,
            suggestedReply = c.suggestedReply.ifBlank { null },
        )
    }
}

/** Mots qui déclenchent l'opposition sans attendre le LLM, par prudence. */
private val hardStop = Regex("(?i)(désinscri|unsubscribe|ne plus me contacter|retirez[- ]moi|\\bstop\\b|remove me|opt[- ]out)")

/** Keeps the reply itself: cuts at the first quoted line, then at 4000 characters. */
internal fun trimQuoted(body: String): String =
    body.lines().takeWhile { line -> !line.startsWith(">") && !line.contains("a écrit") && !line.contains("wrote:") }.joinToString("\n").take(4_000)

/** True when the start of the reply holds an unambiguous request to stop. */
internal fun isHardStop(trimmed: String): Boolean = hardStop.containsMatchIn(trimmed.take(400))

/** Outils de lecture des métriques pour le coach hebdomadaire. Lecture seule. */
@LLMDescription("Lecture des métriques de prospection de Doppio")
class MetricsTools(
    private val eventRepo: EventRepository,
    private val accountRepo: AccountRepository,
    private val dealRepo: DealRepository,
    private val agentRunRepo: AgentRunRepository,
    private val clock: Clock = Clock.systemDefaultZone(),
) : ToolSet {

    @Tool
    @LLMDescription("Compte les événements d'un type sur les N derniers jours. Types : EMAIL_SENT, REPLY_RECEIVED, REPLY_POSITIVE, MEETING_BOOKED, UNSUBSCRIBED, BOUNCED, DRAFT_APPROVED, DRAFT_REJECTED, LINKEDIN_TASK_DONE")
    suspend fun countEvents(
        @LLMDescription("Type d'événement") type: String,
        @LLMDescription("Nombre de jours") days: Int,
    ): String = eventRepo.countEvents(type, clock.instant().minus(days.toLong(), ChronoUnit.DAYS)).toString()

    @Tool
    @LLMDescription("Liste les 15 comptes les mieux classés avec score, offre recommandée et raisons")
    suspend fun topAccounts(): String = accountRepo.accountsByScore(15).joinToString("\n") {
        "${it.account.name} : ${it.score} pts, offre ${it.recommendedOffer ?: "aucune"}, ${it.reasons.take(3).joinToString("; ")}"
    }

    @Tool
    @LLMDescription("Pipeline commercial : affaires par étape et par offre, avec montants")
    suspend fun pipeline(): String = dealRepo.deals().groupBy { it.stage }.entries.joinToString("\n") { (stage, deals) ->
        "$stage : ${deals.size} affaires, ${deals.sumOf { it.amountEur }} euros, offres ${deals.groupingBy { it.offer }.eachCount()}"
    }

    @Tool
    @LLMDescription("Taux de réponse positive par type de signal à l'origine du contact, sur 90 jours")
    suspend fun replyRateBySignal(): String {
        val since = clock.instant().minus(90, ChronoUnit.DAYS)
        val events = eventRepo.eventsSince(since)
        val sentByAccount = events.filter { it.first == "EMAIL_SENT" }.mapNotNull { it.second }.toSet()
        val positiveByAccount = events.filter { it.first == "REPLY_POSITIVE" }.mapNotNull { it.second }.toSet()
        val rows = accountRepo.accountsByScore(500).filter { it.account.id in sentByAccount }
        return rows.groupBy { it.recommendedOffer }.entries.joinToString("\n") { (offer, list) ->
            val pos = list.count { it.account.id in positiveByAccount }
            "${offer ?: "sans offre"} : ${list.size} comptes contactés, $pos réponses positives"
        }.ifBlank { "Pas encore de données." }
    }

    @Tool
    @LLMDescription("Coût et fiabilité des agents sur 7 jours")
    suspend fun agentCosts(): String = agentRunRepo.agentStats(clock.instant().minus(7, ChronoUnit.DAYS)).joinToString("\n") {
        "${it.agent} : ${it.runs} exécutions, ${it.failures} échecs, ${it.inputTokens} tokens en entrée, ${it.outputTokens} en sortie, ${it.avgMs} ms en moyenne"
    }
}

class WeeklyCoachAgent(
    private val llm: Llm,
    private val eventRepo: EventRepository,
    private val accountRepo: AccountRepository,
    private val dealRepo: DealRepository,
    private val agentRunRepo: AgentRunRepository,
    private val tracker: RunTracker,
    private val clock: Clock = Clock.systemDefaultZone(),
) : Coach {
    override suspend fun weeklyMemo(): String {
        val tokens = TokenCounter()
        val agent = AIAgent(
            promptExecutor = llm.executor,
            llmModel = llm.writer,
            systemPrompt = Prompts.coach,
            toolRegistry = ToolRegistry { tools(MetricsTools(eventRepo, accountRepo, dealRepo, agentRunRepo, clock)) },
            maxIterations = 15,
            installFeatures = tokens.feature,
        )
        return tracker.trackAgent("weekly-coach", null, llm.writer, tokens) {
            agent.run("Prépare la note du lundi ${LocalDate.now(clock)}.")
        }
    }
}
