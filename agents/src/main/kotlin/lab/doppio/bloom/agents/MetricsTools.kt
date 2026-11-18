package lab.doppio.bloom.agents

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import lab.doppio.bloom.core.model.EventType
import lab.doppio.bloom.core.model.Limits
import lab.doppio.bloom.core.port.AccountRepository
import lab.doppio.bloom.core.port.AgentRunRepository
import lab.doppio.bloom.core.port.DealRepository
import lab.doppio.bloom.core.port.EventRepository
import lab.doppio.bloom.core.port.FactRepository
import java.time.Clock
import java.time.temporal.ChronoUnit

/** Outils de lecture des métriques pour le coach hebdomadaire. Lecture seule. */
@LLMDescription(MetricsToolTexts.TOOLS)
class MetricsTools(
    private val eventRepo: EventRepository,
    private val accountRepo: AccountRepository,
    private val dealRepo: DealRepository,
    private val factRepo: FactRepository,
    private val agentRunRepo: AgentRunRepository,
    private val clock: Clock = Clock.systemDefaultZone(),
) : ToolSet {

    @Tool
    @LLMDescription(MetricsToolTexts.COUNT_EVENTS)
    suspend fun countEvents(
        @LLMDescription(MetricsToolTexts.COUNT_EVENTS_TYPE) type: String,
        @LLMDescription(MetricsToolTexts.COUNT_EVENTS_DAYS) days: Int,
    ): String {
        // An unknown type counts as zero, as before the journal was typed
        val eventType = EventType.fromName(type.trim().uppercase()) ?: return "0"
        return eventRepo.countEvents(eventType, clock.instant().minus(days.toLong(), ChronoUnit.DAYS)).toString()
    }

    @Tool
    @LLMDescription(MetricsToolTexts.TOP_ACCOUNTS)
    suspend fun topAccounts(): String = accountRepo.accountsByScore(CoachLimits.TOP_ACCOUNTS).joinToString("\n") {
        MetricsToolTexts.topAccount(it.account.name, it.score, it.recommendedOffer ?: MetricsToolTexts.NO_OFFER_RECOMMENDED, it.reasons.take(CoachLimits.TOP_ACCOUNT_REASONS).joinToString("; "))
    }

    @Tool
    @LLMDescription(MetricsToolTexts.PIPELINE)
    suspend fun pipeline(): String = dealRepo.deals().groupBy { it.stage }.entries.joinToString("\n") { (stage, deals) ->
        MetricsToolTexts.pipelineStage(stage, deals.size, deals.sumOf { it.amountEur }, deals.groupingBy { it.offer }.eachCount())
    }

    @Tool
    @LLMDescription(MetricsToolTexts.REPLY_RATE_BY_SIGNAL)
    suspend fun replyRateBySignal(): String {
        val since = clock.instant().minus(Limits.REPLY_RATE_DAYS, ChronoUnit.DAYS)
        val events = eventRepo.eventsSince(since)
        val sentByAccount = events.filter { it.first == EventType.EMAIL_SENT }.mapNotNull { it.second }.toSet()
        val positiveByAccount = events.filter { it.first == EventType.REPLY_POSITIVE }.mapNotNull { it.second }.toSet()
        val rows = accountRepo.accountsByScore(Limits.ALL_ACCOUNTS).filter { it.account.id in sentByAccount }
        // Grouped by the strongest signal of the account, as the tool promises, not by the recommended offer
        return rows.groupBy { factRepo.dominantSignal(it.account.id) }.entries.joinToString("\n") { (signal, list) ->
            val pos = list.count { it.account.id in positiveByAccount }
            MetricsToolTexts.replyRate(signal?.name ?: MetricsToolTexts.NO_SIGNAL, list.size, pos)
        }.ifBlank { MetricsToolTexts.NO_DATA_YET }
    }

    @Tool
    @LLMDescription(MetricsToolTexts.AGENT_COSTS)
    suspend fun agentCosts(): String = agentRunRepo.agentStats(clock.instant().minus(Limits.WEEK_DAYS, ChronoUnit.DAYS)).joinToString("\n") {
        MetricsToolTexts.agentCost(it.agent, it.runs, it.failures, it.inputTokens, it.outputTokens, it.avgMs)
    }
}
