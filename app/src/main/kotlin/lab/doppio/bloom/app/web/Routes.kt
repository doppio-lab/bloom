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
import lab.doppio.bloom.data.db.AccountRow
import lab.doppio.bloom.data.db.Dossier
import lab.doppio.bloom.data.db.ReplyRow
import java.time.Instant
import java.time.temporal.ChronoUnit

@Serializable data class Kpis(
    val sent: Int, val replies: Int, val positive: Int, val meetings: Int, val unsubscribed: Int, val bounced: Int,
    val replyRate: Double, val positiveRate: Double, val unsubscribeRate: Double, val bounceRate: Double,
)
@Serializable data class Queues(val pendingReview: Int, val approvedEmails: Int, val tasks: Int, val openReplies: Int, val toComplete: Int = 0, val discoveredThisWeek: Int = 0)
@Serializable data class SignalRow(val label: String, val sent: Int, val positive: Int)
@Serializable data class StageRow(val stage: DealStage, val count: Int, val amountEur: Long)
@Serializable data class AgentRow(val agent: String, val runs: Int, val failures: Int, val inputTokens: Long, val outputTokens: Long, val avgMs: Long)
@Serializable data class Overview(
    val periodDays: Int, val kpis: Kpis, val queues: Queues, val pipeline: List<StageRow>,
    val accounts: List<AccountRow>, val agents: List<AgentRow>, val dryRun: Boolean,
    val bySignal: List<SignalRow> = emptyList(),
)
@Serializable data class DraftView(val draft: Draft, val contactName: String, val contactRole: String, val accountName: String, val facts: List<Fact>, val linkedinUrl: String?)
@Serializable data class AccountDetail(val account: Account, val facts: List<Fact>, val signals: List<Signal>, val dossier: Dossier?, val contacts: List<Contact>)
@Serializable data class NewAccount(val account: Account, val contacts: List<Contact> = emptyList(), val researchNow: Boolean = true)
@Serializable data class EnrollRequest(val offer: Offer? = null)
@Serializable data class DecisionRequest(val subject: String? = null, val body: String? = null)

fun Route.apiRoutes(ctx: AppContext) = route("/api") {
    val repo = ctx.repo

    get("/overview") {
        val days = call.request.queryParameters["days"]?.toIntOrNull() ?: 30
        val since = Instant.now().minus(days.toLong(), ChronoUnit.DAYS)
        val sent = repo.countEvents("EMAIL_SENT", since)
        val replies = repo.countEvents("REPLY_RECEIVED", since)
        val positive = repo.countEvents("REPLY_POSITIVE", since)
        val unsub = repo.countEvents("UNSUBSCRIBED", since)
        val bounced = repo.countEvents("BOUNCED", since)
        fun rate(n: Int) = if (sent == 0) 0.0 else n.toDouble() / sent
        val deals = repo.deals()
        call.respond(
            Overview(
                periodDays = days,
                kpis = Kpis(sent, replies, positive, repo.countEvents("MEETING_BOOKED", since), unsub, bounced, rate(replies), rate(positive), rate(unsub), rate(bounced)),
                queues = Queues(
                    pendingReview = repo.draftsByStatus(DraftStatus.PENDING_REVIEW).size,
                    approvedEmails = repo.draftsByStatus(DraftStatus.APPROVED, Channel.EMAIL).size,
                    tasks = repo.draftsByStatus(DraftStatus.APPROVED).count { it.channel != Channel.EMAIL },
                    openReplies = repo.openReplies().size,
                    toComplete = repo.accountsToComplete().size,
                    discoveredThisWeek = repo.countEvents("ACCOUNT_DISCOVERED", Instant.now().minus(7, ChronoUnit.DAYS)),
                ),
                pipeline = DealStage.entries.map { s -> deals.filter { it.stage == s }.let { StageRow(s, it.size, it.sumOf { d -> d.amountEur }) } },
                accounts = repo.accountsByScore(50),
                agents = repo.agentStats(Instant.now().minus(7, ChronoUnit.DAYS)).map { AgentRow(it.agent, it.runs, it.failures, it.inputTokens, it.outputTokens, it.avgMs) },
                dryRun = ctx.mail.dryRun,
                bySignal = conversionBySignal(ctx),
            ),
        )
    }

    // --- Comptes ---------------------------------------------------------------------------------
    get("/accounts") { call.respond(repo.accountsByScore(500)) }

    post("/accounts") {
        val req = call.receive<NewAccount>()
        val id = repo.upsertAccount(req.account)
        req.contacts.forEach { repo.addContact(it.copy(accountId = id)) }
        val score = if (req.researchNow) ctx.research.research(id) else null
        call.respond(HttpStatusCode.Created, mapOf("id" to id.toString(), "score" to (score?.total?.toString() ?: "")))
    }

    get("/accounts/{id}") {
        val id = call.parameters["id"]!!.toLong()
        val account = repo.account(id) ?: return@get call.respond(HttpStatusCode.NotFound)
        call.respond(AccountDetail(account, repo.factsOf(id), repo.signalsOf(id), repo.dossier(id), repo.contactsOf(id)))
    }

    post("/accounts/{id}/research") {
        call.respond(ctx.research.research(call.parameters["id"]!!.toLong()))
    }

    post("/contacts") {
        val c = call.receive<Contact>()
        call.respond(HttpStatusCode.Created, mapOf("id" to repo.addContact(c)))
    }

    post("/contacts/{id}/enroll") {
        val contact = repo.contact(call.parameters["id"]!!.toLong()) ?: return@post call.respond(HttpStatusCode.NotFound)
        require(contact.email == null || !repo.isSuppressed(contact.email!!)) { "Ce contact s'est opposé à la prospection" }
        val offer = call.receive<EnrollRequest>().offer ?: repo.dossier(contact.accountId)?.offer ?: Offer.DISCOVERY
        val seq = repo.sequenceFor(offer) ?: repo.sequenceFor(Offer.DISCOVERY)!!
        val id = repo.enroll(contact.id, seq.id)
        repo.logEvent("ENROLLED", contact.accountId, contact.id)
        ctx.orchestrator.planDrafts()
        call.respond(HttpStatusCode.Created, mapOf("enrollmentId" to id))
    }

    // --- File de validation -------------------------------------------------------------------
    get("/drafts") {
        val status = call.request.queryParameters["status"]?.let(DraftStatus::valueOf) ?: DraftStatus.PENDING_REVIEW
        val views = repo.draftsByStatus(status).mapNotNull { d ->
            val e = repo.enrollment(d.enrollmentId) ?: return@mapNotNull null
            val c = repo.contact(e.contactId) ?: return@mapNotNull null
            val a = repo.account(c.accountId) ?: return@mapNotNull null
            DraftView(d, c.fullName, c.role, a.name, repo.factsOf(a.id).filter { it.id in d.citedFactIds || "[F:${it.id}]" in d.body }, c.linkedinUrl)
        }
        call.respond(views)
    }

    post("/drafts/{id}/approve") {
        val id = call.parameters["id"]!!.toLong()
        val req = runCatching { call.receive<DecisionRequest>() }.getOrDefault(DecisionRequest())
        val draft = repo.draft(id) ?: return@post call.respond(HttpStatusCode.NotFound)
        val e = repo.enrollment(draft.enrollmentId)!!
        val c = repo.contact(e.contactId)!!
        val a = repo.account(c.accountId)!!
        val seq = repo.sequences().first { it.id == e.sequenceId }
        // Le garde-fou s'applique aussi aux corrections humaines
        val violations = MessageGuard.check(
            GuardInput(
                draft.channel, draft.stepIndex, req.subject ?: draft.subject, req.body ?: draft.body, draft.citedFactIds,
                repo.factsOf(a.id), draft.referenceUsed, a.sector, seq.offer, c.language,
            ),
        )
        if (!MessageGuard.isPublishable(violations)) {
            return@post call.respond(HttpStatusCode.UnprocessableEntity, violations.map { it.toString() })
        }
        repo.decideDraft(id, DraftStatus.APPROVED, req.subject, req.body)
        repo.logEvent(if (req.body != null) "DRAFT_EDITED_APPROVED" else "DRAFT_APPROVED", a.id, c.id, id)
        call.respond(HttpStatusCode.OK, mapOf("status" to "APPROVED"))
    }

    post("/drafts/{id}/reject") {
        val id = call.parameters["id"]!!.toLong()
        repo.decideDraft(id, DraftStatus.REJECTED)
        repo.logEvent("DRAFT_REJECTED", draftId = id)
        call.respond(HttpStatusCode.OK, mapOf("status" to "REJECTED"))
    }

    post("/drafts/{id}/done") {
        ctx.orchestrator.completeTask(call.parameters["id"]!!.toLong())
        call.respond(HttpStatusCode.OK, mapOf("status" to "TASK_DONE"))
    }

    // --- Réponses, pipeline, agents --------------------------------------------------------------
    get("/replies") { call.respond<List<ReplyRow>>(repo.openReplies()) }
    post("/replies/{id}/handled") {
        repo.markReplyHandled(call.parameters["id"]!!.toLong())
        call.respond(HttpStatusCode.OK, mapOf("status" to "handled"))
    }

    get("/deals") { call.respond(repo.deals()) }
    post("/deals") {
        val d = call.receive<Deal>()
        val id = repo.upsertDeal(d)
        if (d.stage == DealStage.MEETING) repo.logEvent("MEETING_BOOKED", d.accountId)
        call.respond(HttpStatusCode.OK, mapOf("id" to id))
    }
}

fun Route.unsubscribeRoutes(ctx: AppContext) {
    suspend fun unsubscribe(token: String): Boolean {
        val contactId = ctx.tokens.verify(token) ?: return false
        val contact = ctx.repo.contact(contactId)
        contact?.email?.let { ctx.repo.suppress(it, "lien de désinscription") }
        contact?.let { c -> ctx.repo.enrollmentsOfContact(c.id).forEach { ctx.repo.setEnrollmentStatus(it.id, lab.doppio.bloom.core.model.EnrollmentStatus.STOPPED_UNSUBSCRIBE) } }
        ctx.repo.logEvent("UNSUBSCRIBED", contact?.accountId, contactId)
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
)

/** Réponses positives par signal dominant des comptes contactés, sur 90 jours. */
private suspend fun conversionBySignal(ctx: AppContext): List<SignalRow> {
    val events = ctx.repo.eventsSince(Instant.now().minus(90, ChronoUnit.DAYS))
    val sent = events.filter { it.first == "EMAIL_SENT" }.mapNotNull { it.second }.toSet()
    val positive = events.filter { it.first == "REPLY_POSITIVE" }.mapNotNull { it.second }.toSet()
    return sent.groupBy { ctx.repo.dominantSignal(it)?.name ?: "NONE" }
        .map { (type, ids) -> SignalRow(signalLabels[type] ?: "Sans signal", ids.size, ids.count { it in positive }) }
        .sortedByDescending { it.sent }
}
