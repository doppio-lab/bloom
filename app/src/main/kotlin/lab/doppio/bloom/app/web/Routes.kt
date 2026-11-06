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
import lab.doppio.bloom.app.AppContext
import lab.doppio.bloom.core.model.Contact
import lab.doppio.bloom.core.model.Deal
import lab.doppio.bloom.core.model.DealStage
import lab.doppio.bloom.core.model.DraftStatus
import lab.doppio.bloom.core.port.ReplyRow
import lab.doppio.bloom.core.usecase.CompleteTask
import lab.doppio.bloom.core.usecase.DecideDraft
import lab.doppio.bloom.core.usecase.EnrollContact

/** Answer to a decision on a draft that is no longer waiting for review (decided elsewhere, blacklisted, sent). */
private const val NOT_PENDING = "ce brouillon n'est plus à valider"

private val researchLog = org.slf4j.LoggerFactory.getLogger("Research")

/** The research agent takes minutes: it never runs inside an HTTP request. */
private fun launchResearch(ctx: AppContext, accountId: Long) {
    ctx.background.launch {
        runCatching { ctx.research.research(accountId) }.onFailure { researchLog.warn("Recherche échouée pour le compte $accountId", it) }
    }
}

fun Route.apiRoutes(ctx: AppContext) = route("/api") {

    get("/overview") {
        call.respond(ctx.overview(call.request.queryParameters["days"]?.toIntOrNull() ?: 30))
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
        when (val result = ctx.enrollContact(contact, call.receive<EnrollRequest>().offer)) {
            is EnrollContact.Result.Enrolled -> call.respond(HttpStatusCode.Created, mapOf("enrollmentId" to result.enrollmentId))
            EnrollContact.Result.OptedOut -> throw IllegalArgumentException("Ce contact s'est opposé à la prospection")
            EnrollContact.Result.AlreadyEnrolled -> throw IllegalArgumentException("Ce contact est déjà inscrit dans une séquence")
            EnrollContact.Result.NoFit -> throw IllegalArgumentException("Le dossier conclut qu'aucune offre ne convient : choisir l'offre explicitement")
            EnrollContact.Result.NoSequence -> error("aucune séquence pour cette offre")
        }
    }

    // --- File de validation -------------------------------------------------------------------
    get("/drafts") {
        call.respond(ctx.reviewQueue(call.request.queryParameters["status"]?.let(DraftStatus::valueOf) ?: DraftStatus.PENDING_REVIEW))
    }

    post("/drafts/{id}/approve") {
        val req = runCatching { call.receive<DecisionRequest>() }.getOrDefault(DecisionRequest())
        when (val result = ctx.decideDraft.approve(call.parameters["id"]!!.toLong(), req.subject, req.body)) {
            DecideDraft.Result.Done -> call.respond(HttpStatusCode.OK, mapOf("status" to "APPROVED"))
            DecideDraft.Result.NotFound -> call.respond(HttpStatusCode.NotFound)
            DecideDraft.Result.NotPending -> call.respond(HttpStatusCode.Conflict, mapOf("error" to NOT_PENDING))
            is DecideDraft.Result.Violations -> call.respond(HttpStatusCode.UnprocessableEntity, result.messages)
        }
    }

    post("/drafts/{id}/reject") {
        when (ctx.decideDraft.reject(call.parameters["id"]!!.toLong())) {
            DecideDraft.Result.Done -> call.respond(HttpStatusCode.OK, mapOf("status" to "REJECTED"))
            DecideDraft.Result.NotFound -> call.respond(HttpStatusCode.NotFound)
            else -> call.respond(HttpStatusCode.Conflict, mapOf("error" to NOT_PENDING))
        }
    }

    post("/drafts/{id}/done") {
        when (ctx.completeTask(call.parameters["id"]!!.toLong())) {
            CompleteTask.Result.DONE -> call.respond(HttpStatusCode.OK, mapOf("status" to "TASK_DONE"))
            CompleteTask.Result.NOT_FOUND -> call.respond(HttpStatusCode.NotFound)
            CompleteTask.Result.NOT_A_PENDING_TASK -> call.respond(HttpStatusCode.Conflict, mapOf("error" to "cette tâche n'est plus à faire"))
        }
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
        ctx.unsubscribeContact(contactId)
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
