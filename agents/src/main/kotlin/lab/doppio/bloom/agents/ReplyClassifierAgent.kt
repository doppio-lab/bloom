package lab.doppio.bloom.agents

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.prompt.dsl.prompt
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import lab.doppio.bloom.core.model.ReplyClassification
import lab.doppio.bloom.core.model.ReplyIntent
import lab.doppio.bloom.core.port.ReplyClassifier
import java.time.Clock
import java.time.LocalDate

/**
 * Classifieur de réponses. Modèle rapide, sortie structurée.
 * Les décisions qui comptent (arrêt de séquence, opposition) sont prises par le code, pas par le LLM.
 */
class ReplyClassifierAgent(private val llm: Llm, private val tracker: RunTracker, private val clock: Clock = Clock.systemDefaultZone()) : ReplyClassifier {

    @Serializable
    @SerialName("ReplyClassification")
    data class Classification(
        @property:LLMDescription(OutputTexts.CLASSIFICATION_INTENT)
        val intent: String,
        @property:LLMDescription(OutputTexts.CLASSIFICATION_CONFIDENCE)
        val confidence: Double,
        @property:LLMDescription(OutputTexts.CLASSIFICATION_FOLLOW_UP)
        val followUpOn: String,
        @property:LLMDescription(OutputTexts.CLASSIFICATION_REFERRAL)
        val referral: String,
        @property:LLMDescription(OutputTexts.CLASSIFICATION_SUMMARY)
        val summary: String,
        @property:LLMDescription(OutputTexts.CLASSIFICATION_SUGGESTED_REPLY)
        val suggestedReply: String,
    )

    override suspend fun classify(subject: String?, body: String, accountId: Long?): ReplyClassification {
        val today = LocalDate.now(clock)
        val trimmed = trimQuoted(body)
        if (isHardStop(trimmed)) {
            return ReplyClassification(ReplyIntent.UNSUBSCRIBE, 1.0, null, null, OutputTexts.KEYWORD_STOP_SUMMARY, null)
        }
        val c = tracker.track(AgentName.REPLY_CLASSIFIER, accountId, llm.fast) {
            val r = llm.structured<Classification>(
                prompt = prompt("reply") {
                    system(Prompts.replyClassifierSystem(today))
                    user(Prompts.replyInput(subject, trimmed))
                },
                model = llm.fast,
            )
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

/** Keeps the reply itself: cuts at the first quoted line, then at [ReplyLimits.REPLY_CHARS] characters. */
internal fun trimQuoted(body: String): String =
    body.lines().takeWhile { line -> !line.startsWith(">") && !line.contains("a écrit") && !line.contains("wrote:") }.joinToString("\n").take(ReplyLimits.REPLY_CHARS)

/** True when the start of the reply holds an unambiguous request to stop. */
internal fun isHardStop(trimmed: String): Boolean = hardStop.containsMatchIn(trimmed.take(ReplyLimits.HARD_STOP_SCAN_CHARS))
