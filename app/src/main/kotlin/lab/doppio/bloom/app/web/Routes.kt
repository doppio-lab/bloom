package lab.doppio.bloom.app.web

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import lab.doppio.bloom.app.AppContext
import lab.doppio.bloom.core.guard.GuardInput
import lab.doppio.bloom.core.guard.MessageGuard
import lab.doppio.bloom.core.model.Account
import lab.doppio.bloom.core.model.Channel
import lab.doppio.bloom.core.model.Contact
import lab.doppio.bloom.core.model.Deal
import lab.doppio.bloom.core.model.DealStage
import lab.doppio.bloom.core.model.Draft
import lab.doppio.bloom.core.model.DraftStatus
import lab.doppio.bloom.core.model.Fact
import lab.doppio.bloom.core.model.Offer
import lab.doppio.bloom.core.model.Signal
import lab.doppio.bloom.core.port.AccountRow
import lab.doppio.bloom.core.model.Dossier
import lab.doppio.bloom.core.port.ReplyRow
import java.time.temporal.ChronoUnit

/** Answer to a decision on a draft that is no longer waiting for review (decided elsewhere, blacklisted, sent). */
private const val NOT_PENDING = "ce brouillon n'est plus à valider"

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

private const val ACTIVITY_DAYS = 90L

/** One entry per day over the last [ACTIVITY_DAYS] days, today included, empty days at zero. */
private suspend fun activity(ctx: AppContext): List<DayCount> {
    val zone = java.time.ZoneId.of(ctx.policy.timezone)
    val today = java.time.LocalDate.now(ctx.clock.withZone(zone))
    val first = today.minusDays(ACTIVITY_DAYS - 1)
    val events = ctx.eventRepo.eventTimes(setOf("EMAIL_SENT", "REPLY_RECEIVED"), first.atStartOfDay(zone).toInstant())
        .groupBy({ it.second.atZone(zone).toLocalDate() }, { it.first })
    return (0 until ACTIVITY_DAYS).map { first.plusDays(it) }.map { day ->
        val types = events[day].orEmpty()
        DayCount(day.toString(), types.count { it == "EMAIL_SENT" }, types.count { it == "REPLY_RECEIVED" })
    }
}
@Serializable data class DraftView(val draft: Draft, val contactName: String, val contactRole: String, val accountName: String, val facts: List<Fact>, val linkedinUrl: String?)
@Serializable data class AccountDetail(val account: Account, val facts: List<Fact>, val signals: List<Signal>, val dossier: Dossier?, val contacts: List<Contact>)
@Serializable data class NewAccount(val account: Account, val contacts: List<Contact> = emptyList(), val researchNow: Boolean = true)
@Serializable data class EnrollRequest(val offer: Offer? = null)
@Serializable data class DecisionRequest(val subject: String? = null, val body: String? = null)

private val researchLog = org.slf4j.LoggerFactory.getLogger("Research")

/** The research agent takes minutes: it never runs inside an HTTP request. */
private fun launchResearch(ctx: AppContext, accountId: Long) {
    ctx.background.launch {
        runCatching { ctx.research.research(accountId) }.onFailure { researchLog.warn("Recherche échouée pour le compte $accountId", it) }
    }
}

fun Route.apiRoutes(ctx: AppContext) = route("/api") {

    get("/overview") {
        val days = call.request.queryParameters["days"]?.toIntOrNull() ?: 30
        val since = ctx.clock.instant().minus(days.toLong(), ChronoUnit.DAYS)
        val sent = ctx.eventRepo.countEvents("EMAIL_SENT", since)
        val replies = ctx.eventRepo.countEvents("REPLY_RECEIVED", since)
        val positive = ctx.eventRepo.countEvents("REPLY_POSITIVE", since)
        val unsub = ctx.eventRepo.countEvents("UNSUBSCRIBED", since)
        val bounced = ctx.eventRepo.countEvents("BOUNCED", since)
        fun rate(n: Int) = if (sent == 0) 0.0 else n.toDouble() / sent
        val deals = ctx.dealRepo.deals()
        call.respond(
            Overview(
                periodDays = days,
                kpis = Kpis(sent, replies, positive, ctx.eventRepo.countEvents("MEETING_BOOKED", since), unsub, bounced, rate(replies), rate(positive), rate(unsub), rate(bounced)),
                queues = Queues(
                    pendingReview = ctx.draftRepo.draftsByStatus(DraftStatus.PENDING_REVIEW).size,
                    approvedEmails = ctx.draftRepo.draftsByStatus(DraftStatus.APPROVED, Channel.EMAIL).size,
                    tasks = ctx.draftRepo.draftsByStatus(DraftStatus.APPROVED).count { it.channel != Channel.EMAIL },
                    openReplies = ctx.replyRepo.countOpenReplies().toInt(),
                    toComplete = ctx.accountRepo.accountsToComplete().size,
                    discoveredThisWeek = ctx.eventRepo.countEvents("ACCOUNT_DISCOVERED", ctx.clock.instant().minus(7, ChronoUnit.DAYS)),
                ),
                pipeline = DealStage.entries.map { s -> deals.filter { it.stage == s }.let { StageRow(s, it.size, it.sumOf { d -> d.amountEur }) } },
                accounts = ctx.accountRepo.accountsByScore(50),
                agents = ctx.agentRunRepo.agentStats(ctx.clock.instant().minus(7, ChronoUnit.DAYS)).map { AgentRow(it.agent, it.runs, it.failures, it.inputTokens, it.outputTokens, it.avgMs) },
                dryRun = ctx.mail.dryRun,
                bySignal = conversionBySignal(ctx),
                activity = activity(ctx),
                mailboxes = ctx.mail.mailboxes.size,
                nextSendAt = lab.doppio.bloom.core.sequence.SequenceEngine.nextSendingSlot(ctx.clock.instant(), ctx.policy).toString(),
            ),
        )
    }

    // --- Comptes ---------------------------------------------------------------------------------
    get("/accounts") { call.respond(ctx.accountRepo.accountsByScore(500)) }

    post("/accounts") {
        val req = call.receive<NewAccount>()
        val id = ctx.accountRepo.upsertAccount(req.account)
        req.contacts.forEach { ctx.contactRepo.addContact(it.copy(accountId = id)) }
        // The research takes minutes: it runs in the background, the dossier appears when it is done
        if (req.researchNow) launchResearch(ctx, id)
        call.respond(HttpStatusCode.Created, mapOf("id" to id.toString(), "research" to if (req.researchNow) "lancée" else "non demandée"))
    }

    get("/accounts/{id}") {
        val id = call.parameters["id"]!!.toLong()
        val account = ctx.accountRepo.account(id) ?: return@get call.respond(HttpStatusCode.NotFound)
        call.respond(AccountDetail(account, ctx.factRepo.factsOf(id), ctx.factRepo.signalsOf(id), ctx.factRepo.dossier(id), ctx.contactRepo.contactsOf(id)))
    }

    post("/accounts/{id}/research") {
        val id = call.parameters["id"]!!.toLong()
        ctx.accountRepo.account(id) ?: return@post call.respond(HttpStatusCode.NotFound)
        launchResearch(ctx, id)
        call.respond(HttpStatusCode.Accepted, mapOf("status" to "lancée"))
    }

    post("/contacts") {
        val c = call.receive<Contact>()
        call.respond(HttpStatusCode.Created, mapOf("id" to ctx.contactRepo.addContact(c)))
    }

    post("/contacts/{id}/enroll") {
        val contact = ctx.contactRepo.contact(call.parameters["id"]!!.toLong()) ?: return@post call.respond(HttpStatusCode.NotFound)
        require(contact.email == null || !ctx.replyRepo.isSuppressed(contact.email!!)) { "Ce contact s'est opposé à la prospection" }
        val dossier = ctx.factRepo.dossier(contact.accountId)
        val requested = call.receive<EnrollRequest>().offer
        require(requested != null || dossier?.noFit != true) { "Le dossier conclut qu'aucune offre ne convient : choisir l'offre explicitement" }
        val offer = requested ?: dossier?.offer ?: Offer.DISCOVERY
        val seq = ctx.sequenceRepo.sequenceFor(offer) ?: ctx.sequenceRepo.sequenceFor(Offer.DISCOVERY)!!
        val id = ctx.sequenceRepo.enroll(contact.id, seq.id)
        ctx.eventRepo.logEvent("ENROLLED", contact.accountId, contact.id)
        ctx.orchestrator.planSoon()
        call.respond(HttpStatusCode.Created, mapOf("enrollmentId" to id))
    }

    // --- File de validation -------------------------------------------------------------------
    get("/drafts") {
        val status = call.request.queryParameters["status"]?.let(DraftStatus::valueOf) ?: DraftStatus.PENDING_REVIEW
        val views = ctx.draftRepo.draftsByStatus(status).mapNotNull { d ->
            val e = ctx.sequenceRepo.enrollment(d.enrollmentId) ?: return@mapNotNull null
            val c = ctx.contactRepo.contact(e.contactId) ?: return@mapNotNull null
            val a = ctx.accountRepo.account(c.accountId) ?: return@mapNotNull null
            DraftView(d, c.fullName, c.role, a.name, ctx.factRepo.factsOf(a.id).filter { it.id in d.citedFactIds || "[F:${it.id}]" in d.body }, c.linkedinUrl)
        }
        call.respond(views)
    }

    post("/drafts/{id}/approve") {
        val id = call.parameters["id"]!!.toLong()
        val req = runCatching { call.receive<DecisionRequest>() }.getOrDefault(DecisionRequest())
        val draft = ctx.draftRepo.draft(id) ?: return@post call.respond(HttpStatusCode.NotFound)
        // A draft already decided elsewhere (other user, blacklist, sent) is never approved again
        if (draft.status != DraftStatus.PENDING_REVIEW) return@post call.respond(HttpStatusCode.Conflict, mapOf("error" to NOT_PENDING))
        val e = ctx.sequenceRepo.enrollment(draft.enrollmentId)!!
        val c = ctx.contactRepo.contact(e.contactId)!!
        val a = ctx.accountRepo.account(c.accountId)!!
        val seq = ctx.sequenceRepo.all().first { it.id == e.sequenceId }
        // Le garde-fou s'applique aussi aux corrections humaines
        val violations = MessageGuard.check(
            GuardInput(
                draft.channel, draft.stepIndex, req.subject ?: draft.subject, req.body ?: draft.body, draft.citedFactIds,
                ctx.factRepo.factsOf(a.id), draft.referenceUsed, a.sector, seq.offer, c.language,
            ),
        )
        if (!MessageGuard.isPublishable(violations)) {
            return@post call.respond(HttpStatusCode.UnprocessableEntity, violations.map { it.toString() })
        }
        ctx.draftRepo.decideDraft(id, DraftStatus.APPROVED, req.subject, req.body)
        ctx.eventRepo.logEvent(if (req.body != null) "DRAFT_EDITED_APPROVED" else "DRAFT_APPROVED", a.id, c.id, id)
        call.respond(HttpStatusCode.OK, mapOf("status" to "APPROVED"))
    }

    post("/drafts/{id}/reject") {
        val id = call.parameters["id"]!!.toLong()
        val draft = ctx.draftRepo.draft(id) ?: return@post call.respond(HttpStatusCode.NotFound)
        if (draft.status != DraftStatus.PENDING_REVIEW) return@post call.respond(HttpStatusCode.Conflict, mapOf("error" to NOT_PENDING))
        ctx.draftRepo.decideDraft(id, DraftStatus.REJECTED)
        ctx.eventRepo.logEvent("DRAFT_REJECTED", draftId = id)
        call.respond(HttpStatusCode.OK, mapOf("status" to "REJECTED"))
    }

    post("/drafts/{id}/done") {
        val id = call.parameters["id"]!!.toLong()
        val draft = ctx.draftRepo.draft(id) ?: return@post call.respond(HttpStatusCode.NotFound)
        if (draft.status != DraftStatus.APPROVED || draft.channel == Channel.EMAIL) {
            return@post call.respond(HttpStatusCode.Conflict, mapOf("error" to "cette tâche n'est plus à faire"))
        }
        ctx.orchestrator.completeTask(id)
        call.respond(HttpStatusCode.OK, mapOf("status" to "TASK_DONE"))
    }

    // --- Réponses, pipeline, agents --------------------------------------------------------------
    get("/replies") { call.respond<List<ReplyRow>>(ctx.replyRepo.openReplies()) }
    post("/replies/{id}/handled") {
        ctx.replyRepo.markReplyHandled(call.parameters["id"]!!.toLong())
        call.respond(HttpStatusCode.OK, mapOf("status" to "handled"))
    }

    get("/deals") { call.respond(ctx.dealRepo.deals()) }
    post("/deals") {
        val d = call.receive<Deal>()
        val id = ctx.dealRepo.upsertDeal(d)
        if (d.stage == DealStage.MEETING) ctx.eventRepo.logEvent("MEETING_BOOKED", d.accountId)
        call.respond(HttpStatusCode.OK, mapOf("id" to id))
    }
}

fun Route.unsubscribeRoutes(ctx: AppContext) {
    suspend fun unsubscribe(token: String): Boolean {
        val contactId = ctx.tokens.verify(token) ?: return false
        val contact = ctx.contactRepo.contact(contactId)
        contact?.email?.let { ctx.replyRepo.suppress(it, "lien de désinscription") }
        contact?.let { c -> ctx.sequenceRepo.enrollmentsOfContact(c.id).forEach { ctx.sequenceRepo.setEnrollmentStatus(it.id, lab.doppio.bloom.core.model.EnrollmentStatus.STOPPED_UNSUBSCRIBE) } }
        ctx.eventRepo.logEvent("UNSUBSCRIBED", contact?.accountId, contactId)
        return true
    }

    // Lien cliqué par une personne : on confirme sans demander de compte ni de motif
    get("/u/{token}") {
        val ok = unsubscribe(call.parameters["token"]!!)
        call.respondText(
            if (ok) "<!doctype html><meta charset=utf-8><title>Désinscription</title><body style='font-family:system-ui;max-width:32rem;margin:4rem auto'>" +
                "<h1>C'est fait.</h1><p>Vous ne recevrez plus de message de Doppio Lab. Désolé pour le dérangement.</p></body>"
            else "Lien invalide.",
            ContentType.Text.Html,
            if (ok) HttpStatusCode.OK else HttpStatusCode.BadRequest,
        )
    }
    // RFC 8058 : POST en un clic depuis le client mail
    post("/u/{token}") {
        val ok = unsubscribe(call.parameters["token"]!!)
        call.respond(if (ok) HttpStatusCode.OK else HttpStatusCode.BadRequest)
    }
}

private val signalLabels = mapOf(
    "REFERRAL" to "Lien chaud", "HIRING_STUCK" to "Recrutement bloqué", "QUALITY_COMPLAINTS" to "Plaintes qualité (avis)",
    "RATING_DROP" to "Note en baisse", "HIRING_KMP" to "Recrutement KMP", "NEW_TECH_LEADER" to "Nouveau dirigeant tech",
    "FUNDING" to "Levée de fonds", "HIRING_MOBILE" to "Recrutement mobile", "PRODUCT_LAUNCH" to "Lancement produit",
    "RELEASE_STALL" to "App sans mise à jour", "CONTENT_ENGAGEMENT" to "Interaction LinkedIn", "AI_ADOPTION" to "Équipe LLM",
    "PUBLIC_TENDER" to "Marché public", "KMP_ADOPTION" to "Code KMP public",
    "CONTRACT_RENEWAL" to "Marché à renouveler",
)

/** Réponses positives par signal dominant des comptes contactés, sur 90 jours. */
private suspend fun conversionBySignal(ctx: AppContext): List<SignalRow> {
    val events = ctx.eventRepo.eventsSince(ctx.clock.instant().minus(90, ChronoUnit.DAYS))
    val sent = events.filter { it.first == "EMAIL_SENT" }.mapNotNull { it.second }.toSet()
    val positive = events.filter { it.first == "REPLY_POSITIVE" }.mapNotNull { it.second }.toSet()
    return sent.groupBy { ctx.factRepo.dominantSignal(it)?.name ?: "NONE" }
        .map { (type, ids) -> SignalRow(signalLabels[type] ?: "Sans signal", ids.size, ids.count { it in positive }) }
        .sortedByDescending { it.sent }
}
