package lab.doppio.bloom.app.web

import lab.doppio.bloom.core.model.latestPerTopic
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import lab.doppio.bloom.core.model.Contact
import lab.doppio.bloom.core.model.EmailAddress
import lab.doppio.bloom.core.model.Deal
import lab.doppio.bloom.core.model.DealStage
import lab.doppio.bloom.core.model.DraftStatus
import lab.doppio.bloom.core.model.EventType
import lab.doppio.bloom.core.model.Limits
import lab.doppio.bloom.core.port.AccountRepository
import lab.doppio.bloom.core.port.ContactRepository
import lab.doppio.bloom.core.port.DealRepository
import lab.doppio.bloom.core.port.EventRepository
import lab.doppio.bloom.core.port.FactRepository
import lab.doppio.bloom.core.port.ReplyRepository
import lab.doppio.bloom.core.port.ReplyRow
import lab.doppio.bloom.core.port.Researcher
import lab.doppio.bloom.core.usecase.BookMeeting
import lab.doppio.bloom.core.usecase.CompleteContactEmail
import lab.doppio.bloom.core.usecase.CreateAccount
import lab.doppio.bloom.core.usecase.CompleteTask
import lab.doppio.bloom.core.usecase.DecideDraft
import lab.doppio.bloom.core.usecase.EnrollContact
import lab.doppio.bloom.core.usecase.GetBoard
import lab.doppio.bloom.core.usecase.GetOverview
import lab.doppio.bloom.core.usecase.ReviewQueue

/** Window of the overview counters when the request gives none. */
private const val DEFAULT_OVERVIEW_DAYS = 30

private val researchLog = org.slf4j.LoggerFactory.getLogger("Research")

/** The dashboard API: accounts, contacts, the review queue, replies and deals. Built by Koin (see webModule). */
class ApiRoutes(
    private val overview: GetOverview,
    private val accountRepo: AccountRepository,
    private val contactRepo: ContactRepository,
    private val factRepo: FactRepository,
    private val enrollContact: EnrollContact,
    private val createAccount: CreateAccount,
    private val reviewQueue: ReviewQueue,
    private val decideDraft: DecideDraft,
    private val completeTask: CompleteTask,
    private val getBoard: GetBoard,
    private val bookMeeting: BookMeeting,
    private val completeContactEmail: CompleteContactEmail,
    private val replyRepo: ReplyRepository,
    private val dealRepo: DealRepository,
    private val eventRepo: EventRepository,
    private val research: Researcher,
    /** Work that outlives the request that started it. */
    private val background: CoroutineScope,
) {
    /** The research agent takes minutes: it never runs inside an HTTP request. */
    private fun launchResearch(accountId: Long) {
        background.launch {
            runCatching { research.research(accountId) }.onFailure { researchLog.warn("Recherche échouée pour le compte $accountId", it) }
        }
    }

    fun mount(parent: Route) = parent.route("/api") {

        get("/overview") {
            call.respond(overview(call.request.queryParameters["days"]?.toIntOrNull() ?: DEFAULT_OVERVIEW_DAYS))
        }

        // --- Comptes ---------------------------------------------------------------------------------
        get("/accounts") { call.respond(accountRepo.accountsByScore(Limits.ALL_ACCOUNTS)) }

        post("/accounts") {
            val req = call.receive<NewAccount>()
            val id = createAccount(req.account, req.contacts)
            // The research takes minutes: it runs in the background, the dossier appears when it is done
            if (req.researchNow) launchResearch(id)
            call.respond(HttpStatusCode.Created, mapOf("id" to id.toString(), "research" to if (req.researchNow) ApiMessages.STARTED else ApiMessages.NOT_REQUESTED))
        }

        get("/accounts/{id}") {
            val id = call.parameters["id"]!!.toLong()
            val account = accountRepo.account(id) ?: return@get call.respond(HttpStatusCode.NotFound)
            call.respond(AccountDetail(account, factRepo.factsOf(id).latestPerTopic(), factRepo.signalsOf(id), factRepo.dossier(id), contactRepo.contactsOf(id)))
        }

        post("/accounts/{id}/research") {
            val id = call.parameters["id"]!!.toLong()
            accountRepo.account(id) ?: return@post call.respond(HttpStatusCode.NotFound)
            launchResearch(id)
            call.respond(HttpStatusCode.Accepted, mapOf("status" to ApiMessages.STARTED))
        }

        post("/contacts") {
            val c = call.receive<Contact>().let { it.copy(email = EmailAddress.orNull(it.email)) }
            call.respond(HttpStatusCode.Created, mapOf("id" to contactRepo.addContact(c)))
        }

        post("/contacts/{id}/enroll") {
            val contact = contactRepo.contact(call.parameters["id"]!!.toLong()) ?: return@post call.respond(HttpStatusCode.NotFound)
            when (val result = enrollContact(contact, call.receive<EnrollRequest>().offer, manual = true)) {
                is EnrollContact.Result.Enrolled -> call.respond(HttpStatusCode.Created, mapOf("enrollmentId" to result.enrollmentId))
                EnrollContact.Result.OptedOut -> throw IllegalArgumentException(ApiMessages.CONTACT_OPTED_OUT)
                EnrollContact.Result.AlreadyEnrolled -> throw IllegalArgumentException(ApiMessages.CONTACT_ALREADY_ENROLLED)
                EnrollContact.Result.NoFit -> throw IllegalArgumentException(ApiMessages.NO_FIT)
                EnrollContact.Result.NoSequence -> error("aucune séquence pour cette offre")
            }
        }

        post("/contacts/{id}/email") {
            val req = call.receive<EmailRequest>()
            when (val result = completeContactEmail(call.parameters["id"]!!.toLong(), req.email)) {
                CompleteContactEmail.Result.NotFound -> call.respond(HttpStatusCode.NotFound)
                is CompleteContactEmail.Result.Done -> call.respond(HttpStatusCode.OK, mapOf("message" to when (result.enrollment) {
                    is EnrollContact.Result.Enrolled -> ApiMessages.EMAIL_SAVED_SEQUENCE_STARTED
                    EnrollContact.Result.AlreadyEnrolled -> ApiMessages.EMAIL_SAVED_ALREADY_ENROLLED
                    EnrollContact.Result.NoFit -> ApiMessages.EMAIL_SAVED_NO_FIT
                    EnrollContact.Result.OptedOut -> throw IllegalArgumentException(ApiMessages.PERSON_OPTED_OUT)
                    EnrollContact.Result.NoSequence -> error("aucune séquence pour cette offre")
                }))
            }
        }

        // --- Parcours ---------------------------------------------------------------------------------
        get("/board") { call.respond(getBoard()) }

        post("/accounts/{id}/meeting") {
            val req = call.receiveOrDefault(MeetingRequest())
            when (bookMeeting(call.parameters["id"]!!.toLong(), req.replyId)) {
                BookMeeting.Result.Booked -> call.respond(HttpStatusCode.OK, mapOf("status" to ApiMessages.MEETING_BOOKED))
                BookMeeting.Result.AlreadyBooked -> call.respond(HttpStatusCode.OK, mapOf("status" to ApiMessages.MEETING_ALREADY_BOOKED))
                BookMeeting.Result.NotFound -> call.respond(HttpStatusCode.NotFound)
            }
        }

        // --- File de validation -------------------------------------------------------------------
        get("/drafts") {
            call.respond(reviewQueue(call.request.queryParameters["status"]?.let(DraftStatus::valueOf) ?: DraftStatus.PENDING_REVIEW))
        }

        post("/drafts/{id}/approve") {
            val req = call.receiveOrDefault(DecisionRequest())
            when (val result = decideDraft.approve(call.parameters["id"]!!.toLong(), req.subject, req.body)) {
                DecideDraft.Result.Done -> call.respond(HttpStatusCode.OK, mapOf("status" to DraftStatus.APPROVED.name))
                DecideDraft.Result.NotFound -> call.respond(HttpStatusCode.NotFound)
                DecideDraft.Result.NotPending -> call.respond(HttpStatusCode.Conflict, mapOf("error" to ApiMessages.DRAFT_NOT_PENDING))
                is DecideDraft.Result.Violations -> call.respond(HttpStatusCode.UnprocessableEntity, result.messages)
            }
        }

        post("/drafts/{id}/reject") {
            when (decideDraft.reject(call.parameters["id"]!!.toLong())) {
                DecideDraft.Result.Done -> call.respond(HttpStatusCode.OK, mapOf("status" to DraftStatus.REJECTED.name))
                DecideDraft.Result.NotFound -> call.respond(HttpStatusCode.NotFound)
                else -> call.respond(HttpStatusCode.Conflict, mapOf("error" to ApiMessages.DRAFT_NOT_PENDING))
            }
        }

        post("/drafts/{id}/done") {
            when (completeTask(call.parameters["id"]!!.toLong())) {
                CompleteTask.Result.DONE -> call.respond(HttpStatusCode.OK, mapOf("status" to DraftStatus.TASK_DONE.name))
                CompleteTask.Result.NOT_FOUND -> call.respond(HttpStatusCode.NotFound)
                CompleteTask.Result.NOT_A_PENDING_TASK -> call.respond(HttpStatusCode.Conflict, mapOf("error" to ApiMessages.TASK_NOT_PENDING))
            }
        }

        // --- Réponses, pipeline, agents --------------------------------------------------------------
        get("/replies") { call.respond<List<ReplyRow>>(replyRepo.openReplies()) }
        post("/replies/{id}/handled") {
            replyRepo.markReplyHandled(call.parameters["id"]!!.toLong())
            call.respond(HttpStatusCode.OK, mapOf("status" to "handled"))
        }

        get("/deals") { call.respond(dealRepo.deals()) }
        post("/deals") {
            val d = call.receive<Deal>()
            val id = dealRepo.upsertDeal(d)
            if (d.stage == DealStage.MEETING) eventRepo.logEvent(EventType.MEETING_BOOKED, d.accountId)
            call.respond(HttpStatusCode.OK, mapOf("id" to id))
        }
    }
}
