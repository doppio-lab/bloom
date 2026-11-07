package lab.doppio.bloom.core.usecase

import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlinx.serialization.Serializable
import lab.doppio.bloom.core.model.Channel
import lab.doppio.bloom.core.model.DealStage
import lab.doppio.bloom.core.model.DraftStatus
import lab.doppio.bloom.core.model.EventType
import lab.doppio.bloom.core.model.Limits
import lab.doppio.bloom.core.model.SendingPolicy
import lab.doppio.bloom.core.model.SignalType
import lab.doppio.bloom.core.port.AccountRepository
import lab.doppio.bloom.core.port.AccountRow
import lab.doppio.bloom.core.port.AgentRunRepository
import lab.doppio.bloom.core.port.DealRepository
import lab.doppio.bloom.core.port.DraftRepository
import lab.doppio.bloom.core.port.EventRepository
import lab.doppio.bloom.core.port.FactRepository
import lab.doppio.bloom.core.port.ReplyRepository
import lab.doppio.bloom.core.sequence.SequenceEngine

@Serializable data class Kpis(
    val sent: Int, val replies: Int, val positive: Int, val meetings: Int, val unsubscribed: Int, val bounced: Int,
    val replyRate: Double, val positiveRate: Double, val unsubscribeRate: Double, val bounceRate: Double,
)
@Serializable data class Queues(val pendingReview: Int, val approvedEmails: Int, val tasks: Int, val openReplies: Int, val toComplete: Int = 0, val discoveredThisWeek: Int = 0)
@Serializable data class SignalRow(val label: String, val sent: Int, val positive: Int)
@Serializable data class StageRow(val stage: DealStage, val count: Int, val amountEur: Long)
@Serializable data class AgentRow(val agent: String, val runs: Int, val failures: Int, val inputTokens: Long, val outputTokens: Long, val avgMs: Long)
@Serializable data class DayCount(val date: String, val sent: Int, val replies: Int)
@Serializable data class Overview(
    val periodDays: Int, val kpis: Kpis, val queues: Queues, val pipeline: List<StageRow>,
    val accounts: List<AccountRow>, val agents: List<AgentRow>, val dryRun: Boolean,
    val bySignal: List<SignalRow> = emptyList(),
    /** Emails sent and replies received per day (Paris time), oldest first, for the Pilotage chart. */
    val activity: List<DayCount> = emptyList(),
    val mailboxes: Int = 0,
    /** Start of the next sending window, whatever the queue holds. */
    val nextSendAt: String? = null,
)

private val signalLabels = mapOf(
    SignalType.REFERRAL to "Lien chaud", SignalType.HIRING_STUCK to "Recrutement bloqué", SignalType.QUALITY_COMPLAINTS to "Plaintes qualité (avis)",
    SignalType.RATING_DROP to "Note en baisse", SignalType.HIRING_KMP to "Recrutement KMP", SignalType.NEW_TECH_LEADER to "Nouveau dirigeant tech",
    SignalType.FUNDING to "Levée de fonds", SignalType.HIRING_MOBILE to "Recrutement mobile", SignalType.PRODUCT_LAUNCH to "Lancement produit",
    SignalType.RELEASE_STALL to "App sans mise à jour", SignalType.CONTENT_ENGAGEMENT to "Interaction LinkedIn", SignalType.AI_ADOPTION to "Équipe LLM",
    SignalType.PUBLIC_TENDER to "Marché public", SignalType.KMP_ADOPTION to "Code KMP public",
    SignalType.CONTRACT_RENEWAL to "Marché à renouveler",
)

/** The numbers of the dashboard: funnel, queues, pipeline, agent usage, activity and conversion per signal. */
class GetOverview(
    private val eventRepo: EventRepository,
    private val dealRepo: DealRepository,
    private val draftRepo: DraftRepository,
    private val replyRepo: ReplyRepository,
    private val accountRepo: AccountRepository,
    private val agentRunRepo: AgentRunRepository,
    private val factRepo: FactRepository,
    private val policy: SendingPolicy,
    private val clock: Clock,
    private val dryRun: Boolean,
    private val mailboxCount: Int,
) {
    suspend operator fun invoke(days: Int): Overview {
        val since = clock.instant().minus(days.toLong(), ChronoUnit.DAYS)
        val sent = eventRepo.countEvents(EventType.EMAIL_SENT, since)
        val replies = eventRepo.countEvents(EventType.REPLY_RECEIVED, since)
        val positive = eventRepo.countEvents(EventType.REPLY_POSITIVE, since)
        val unsub = eventRepo.countEvents(EventType.UNSUBSCRIBED, since)
        val bounced = eventRepo.countEvents(EventType.BOUNCED, since)
        fun rate(n: Int) = if (sent == 0) 0.0 else n.toDouble() / sent
        val deals = dealRepo.deals()
        return Overview(
            periodDays = days,
            kpis = Kpis(sent, replies, positive, eventRepo.countEvents(EventType.MEETING_BOOKED, since), unsub, bounced, rate(replies), rate(positive), rate(unsub), rate(bounced)),
            queues = Queues(
                pendingReview = draftRepo.draftsByStatus(DraftStatus.PENDING_REVIEW).size,
                approvedEmails = draftRepo.draftsByStatus(DraftStatus.APPROVED, Channel.EMAIL).size,
                tasks = draftRepo.draftsByStatus(DraftStatus.APPROVED).count { it.channel != Channel.EMAIL },
                openReplies = replyRepo.countOpenReplies().toInt(),
                toComplete = accountRepo.accountsToComplete().size,
                discoveredThisWeek = eventRepo.countEvents(EventType.ACCOUNT_DISCOVERED, clock.instant().minus(Limits.WEEK_DAYS, ChronoUnit.DAYS)),
            ),
            pipeline = DealStage.entries.map { s -> deals.filter { it.stage == s }.let { StageRow(s, it.size, it.sumOf { d -> d.amountEur }) } },
            accounts = accountRepo.accountsByScore(Limits.OVERVIEW_ACCOUNTS),
            agents = agentRunRepo.agentStats(clock.instant().minus(Limits.WEEK_DAYS, ChronoUnit.DAYS)).map { AgentRow(it.agent, it.runs, it.failures, it.inputTokens, it.outputTokens, it.avgMs) },
            dryRun = dryRun,
            bySignal = conversionBySignal(),
            activity = activity(),
            mailboxes = mailboxCount,
            nextSendAt = SequenceEngine.nextSendingSlot(clock.instant(), policy).toString(),
        )
    }

    /** One entry per day over the last [ACTIVITY_DAYS] days, today included, empty days at zero. */
    private suspend fun activity(): List<DayCount> {
        val zone = ZoneId.of(policy.timezone)
        val today = LocalDate.now(clock.withZone(zone))
        val first = today.minusDays(ACTIVITY_DAYS - 1)
        val events = eventRepo.eventTimes(setOf(EventType.EMAIL_SENT, EventType.REPLY_RECEIVED), first.atStartOfDay(zone).toInstant())
            .groupBy({ it.second.atZone(zone).toLocalDate() }, { it.first })
        return (0 until ACTIVITY_DAYS).map { first.plusDays(it) }.map { day ->
            val types = events[day].orEmpty()
            DayCount(day.toString(), types.count { it == EventType.EMAIL_SENT }, types.count { it == EventType.REPLY_RECEIVED })
        }
    }

    /** Réponses positives par signal dominant des comptes contactés, sur 90 jours. */
    private suspend fun conversionBySignal(): List<SignalRow> {
        val events = eventRepo.eventsSince(clock.instant().minus(Limits.REPLY_RATE_DAYS, ChronoUnit.DAYS))
        val sent = events.filter { it.first == EventType.EMAIL_SENT }.mapNotNull { it.second }.toSet()
        val positive = events.filter { it.first == EventType.REPLY_POSITIVE }.mapNotNull { it.second }.toSet()
        return sent.groupBy { factRepo.dominantSignal(it) }
            .map { (type, ids) -> SignalRow(signalLabels[type] ?: "Sans signal", ids.size, ids.count { it in positive }) }
            .sortedByDescending { it.sent }
    }

    private companion object {
        const val ACTIVITY_DAYS = 90L
    }
}
