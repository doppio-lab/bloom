package lab.doppio.bloom.app.outreach

import lab.doppio.bloom.core.port.AccountRepository
import lab.doppio.bloom.core.port.ContactRepository
import lab.doppio.bloom.core.port.DiscoveryStateRepository
import lab.doppio.bloom.core.port.DraftRepository
import lab.doppio.bloom.core.port.EventRepository
import lab.doppio.bloom.core.port.FactRepository
import lab.doppio.bloom.core.port.JobRunRepository
import lab.doppio.bloom.core.port.OfficerRepository
import lab.doppio.bloom.core.port.ReplyRepository
import lab.doppio.bloom.core.port.SequenceRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import lab.doppio.bloom.agents.ReplyClassifierAgent
import lab.doppio.bloom.agents.ResearchAgent
import lab.doppio.bloom.agents.WeeklyCoachAgent
import lab.doppio.bloom.agents.WriterAgent
import lab.doppio.bloom.core.guard.MessageGuard
import lab.doppio.bloom.core.model.Channel
import lab.doppio.bloom.core.model.DraftStatus
import lab.doppio.bloom.core.model.EnrollmentStatus
import lab.doppio.bloom.core.model.ReplyIntent
import lab.doppio.bloom.core.model.SendingPolicy
import lab.doppio.bloom.core.sequence.NextAction
import lab.doppio.bloom.core.sequence.SequenceEngine
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import kotlin.time.Duration.Companion.minutes

/**
 * Boucles de fond. Chaque boucle est idempotente : un redémarrage ne double ni un envoi ni un brouillon.
 *
 *  - planification : prépare les brouillons dus (agent rédacteur) ;
 *  - envoi : envoie les emails approuvés, dans la fenêtre, sous les plafonds, une boîte après l'autre ;
 *  - relève : classe les réponses, arrête les séquences, gère l'opposition ;
 *  - nuit : recherche et rescoring des comptes ; lundi : note du coach ; mensuel : purge RGPD.
 */
private const val MAX_REJECTIONS_PER_STEP = 3

class Orchestrator(
    private val accountRepo: AccountRepository,
    private val contactRepo: ContactRepository,
    private val discoveryState: DiscoveryStateRepository,
    private val draftRepo: DraftRepository,
    private val eventRepo: EventRepository,
    private val factRepo: FactRepository,
    private val jobRunRepo: JobRunRepository,
    private val officerRepo: OfficerRepository,
    private val replyRepo: ReplyRepository,
    private val sequenceRepo: SequenceRepository,
    private val writer: WriterAgent,
    private val research: ResearchAgent,
    private val classifier: ReplyClassifierAgent,
    private val coach: WeeklyCoachAgent,
    private val gateway: EmailGateway,
    private val poller: ImapPoller,
    private val mail: MailConfig,
    private val policy: SendingPolicy,
    private val discovery: lab.doppio.bloom.data.discovery.DiscoveryService,
    private val nightlyResearchBudget: Int = 25,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val instanceLock: () -> Boolean = { lab.doppio.bloom.data.persistence.Db.tryHoldSingleInstanceLock() },
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val zone = ZoneId.of(policy.timezone)

    /** Long jobs (discovery, research, coach) run here so they never hold up each other or the loops. */
    private var jobs: CoroutineScope? = null

    /** True once this instance holds the loops' lock. */
    @Volatile private var leader = false

    fun start(scope: CoroutineScope) {
        jobs = scope
        scope.launch {
            // Une seule instance fait tourner les boucles : sinon chaque email partirait deux fois
            while (!runCatching { instanceLock() }.getOrDefault(false)) {
                log.warn("Une autre instance de Bloom tient les boucles de fond : nouvel essai dans 30 s")
                delay(30_000)
            }
            leader = true
            // Avant toute boucle : un brouillon resté SENDING date d'avant le redémarrage
            val interrupted = draftRepo.reconcileInterruptedSends()
            if (interrupted > 0) log.warn("$interrupted email(s) interrompu(s) pendant l'envoi, passés en FAILED : vérifier la boîte d'envoi")
            loop("planification", 10.minutes) { planDrafts() }
            loop("envoi", 2.minutes) { sendApproved() }
            loop("relève", 5.minutes) { processReplies() }
            loop("nuit", 30.minutes) { nightly() }
        }
    }

    private fun CoroutineScope.loop(name: String, every: kotlin.time.Duration, block: suspend () -> Unit) = launch {
        while (isActive) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("Boucle $name en échec", e)
            }
            delay(every)
        }
    }

    /** One planning pass at a time: two passes would ask the writer twice for the same step. */
    private val planning = kotlinx.coroutines.sync.Mutex()

    /** Writer failures per (enrollment, step): the next try waits 10 min, 20 min... up to a day. */
    private val writerBackoff = java.util.concurrent.ConcurrentHashMap<Pair<Long, Int>, Pair<Int, Instant>>()

    /** Plans right away, in the background: an HTTP request never waits for the writer. */
    fun planSoon() {
        if (!leader) return // another instance runs the loops and will plan within 10 minutes
        jobs?.launch {
            try { planDrafts() } catch (e: CancellationException) { throw e } catch (e: Exception) { log.error("Planification en échec", e) }
        }
    }

    suspend fun planDrafts(now: Instant = clock.instant()) = planning.withLock { planDraftsLocked(now) }

    private suspend fun planDraftsLocked(now: Instant) {
        val sequences = sequenceRepo.all().associateBy { it.id }
        for (e in sequenceRepo.activeEnrollments()) {
            val seq = sequences[e.sequenceId] ?: continue
            when (val action = SequenceEngine.next(e, seq, policy, now)) {
                is NextAction.PrepareDraft -> if (!draftRepo.hasOpenDraft(e.id, action.step.index)) {
                    val contact = contactRepo.contact(e.contactId) ?: continue
                    if (contact.email != null && replyRepo.isSuppressed(contact.email!!)) {
                        sequenceRepo.setEnrollmentStatus(e.id, EnrollmentStatus.STOPPED_UNSUBSCRIBE)
                        continue
                    }
                    // Trois brouillons rejetés pour la même étape : la séquence s'arrête au lieu d'en réécrire sans fin
                    if (draftRepo.rejectedDrafts(e.id, action.step.index) >= MAX_REJECTIONS_PER_STEP) {
                        sequenceRepo.setEnrollmentStatus(e.id, EnrollmentStatus.STOPPED_MANUAL)
                        eventRepo.logEvent("ENROLLMENT_STOPPED_REJECTIONS", contact.accountId, contact.id)
                        continue
                    }
                    val key = e.id to action.step.index
                    if (writerBackoff[key]?.second?.isAfter(now) == true) continue
                    try {
                        writer.draft(e, seq, action.step)
                        writerBackoff.remove(key)
                    } catch (ex: CancellationException) {
                        throw ex
                    } catch (ex: Exception) {
                        val failures = (writerBackoff[key]?.first ?: 0) + 1
                        val wait = minOf(10L shl (failures - 1).coerceAtMost(8), 24 * 60L)
                        writerBackoff[key] = failures to now.plus(wait, ChronoUnit.MINUTES)
                        log.warn("Brouillon impossible pour l'inscription ${e.id} ($failures échec(s), nouvel essai dans $wait min)", ex)
                    }
                }
                is NextAction.Finish -> if (action.status != e.status) sequenceRepo.setEnrollmentStatus(e.id, action.status)
                is NextAction.WaitUntil -> {}
            }
        }
    }

    suspend fun sendApproved(now: Instant = clock.instant()) {
        if (!SequenceEngine.inWindow(now, policy)) return
        val startOfDay = now.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant()
        val draft = draftRepo.nextToSend(now) ?: return

        // Une seule boîte disponible par passage : on respecte l'écart minimal entre deux envois.
        val mailbox = mail.mailboxes.firstOrNull { mb ->
            draftRepo.sentTodayBy(mb.address, startOfDay) < policy.maxPerMailboxPerDay &&
                (draftRepo.lastSentAt(mb.address)?.let { Duration.between(it, now).seconds >= policy.minSecondsBetweenSends } ?: true)
        } ?: return

        val enrollment = sequenceRepo.enrollment(draft.enrollmentId) ?: run { draftRepo.decideDraft(draft.id, DraftStatus.REJECTED); return }
        if (enrollment.status != EnrollmentStatus.ACTIVE) {
            draftRepo.decideDraft(draft.id, DraftStatus.REJECTED); return
        }
        val contact = contactRepo.contact(enrollment.contactId) ?: run { draftRepo.decideDraft(draft.id, DraftStatus.REJECTED); return }
        val to = contact.email ?: run { draftRepo.decideDraft(draft.id, DraftStatus.REJECTED); return }
        if (replyRepo.isSuppressed(to)) {
            draftRepo.decideDraft(draft.id, DraftStatus.REJECTED)
            sequenceRepo.setEnrollmentStatus(enrollment.id, EnrollmentStatus.STOPPED_UNSUBSCRIBE)
            return
        }

        // Réservé avant l'appel SMTP : après un crash, ce brouillon ne repart jamais tout seul.
        val messageId = gateway.newMessageId(mailbox)
        if (!draftRepo.claimForSending(draft.id, messageId, mailbox.address)) return
        val thread = sequenceRepo.threadMessageId(enrollment.id)
        try {
            gateway.send(
                mailbox = mailbox,
                to = to,
                subject = draft.subject.orEmpty(),
                body = MessageGuard.stripCitations(draft.body),
                contactId = contact.id,
                language = contact.language,
                inReplyTo = thread,
                messageId = messageId,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val status = draftRepo.recordSendFailure(draft.id, e.message ?: e.javaClass.simpleName, now)
            log.warn("Envoi du brouillon ${draft.id} refusé, statut $status", e)
            if (status == DraftStatus.FAILED) eventRepo.logEvent("EMAIL_FAILED", contact.accountId, contact.id, draft.id)
            return
        }
        draftRepo.recordSent(draft, threadMessageId = thread ?: messageId, now = now)
        eventRepo.logEvent("EMAIL_SENT", contact.accountId, contact.id, draft.id)
    }

    /** Une tâche LinkedIn ou téléphone faite à la main fait avancer la séquence. */
    suspend fun completeTask(draftId: Long) {
        val draft = draftRepo.draft(draftId) ?: return
        draftRepo.decideDraft(draftId, DraftStatus.TASK_DONE)
        sequenceRepo.advance(draft.enrollmentId, draft.stepIndex + 1)
        val e = sequenceRepo.enrollment(draft.enrollmentId)
        val c = e?.let { contactRepo.contact(it.contactId) }
        eventRepo.logEvent("${draft.channel}_TASK_DONE", c?.accountId, c?.id, draftId)
    }

    /**
     * Each message is marked seen only once processed. One that fails stays unread and is retried at the
     * next pass; one already recorded (marking it seen failed last time) is skipped.
     */
    suspend fun processReplies() {
        val processed = mutableMapOf<String, MutableList<Long>>()
        for (mail in poller.poll()) {
            try {
                if (!replyRepo.replyRecorded(mail.key)) processReply(mail)
                processed.getOrPut(mail.mailbox) { mutableListOf() } += mail.uid
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Réponse non traitée, relue à la prochaine passe : ${mail.key}", e)
            }
        }
        processed.forEach { (mailbox, uids) ->
            runCatching { poller.markSeen(mailbox, uids) }.onFailure { log.warn("Impossible de marquer lus les messages de $mailbox", it) }
        }
    }

    private suspend fun processReply(mail: InboundMail) {
        val enrollmentId = mail.inReplyTo.firstNotNullOfOrNull { draftRepo.enrollmentByMessageId(it) }
        val enrolled = enrollmentId?.let { sequenceRepo.enrollment(it) }?.let { contactRepo.contact(it.contactId) }
        // Un rebond vient du serveur de messagerie : le contact est celui de l'email d'origine
        val contact = if (mail.isBounce) enrolled else contactRepo.contactByEmail(mail.from) ?: enrolled
        val accountId = contact?.accountId
        val result = if (mail.isBounce) {
            ReplyClassifierAgent.Result(ReplyIntent.BOUNCE, 1.0, null, null, "Rebond : adresse invalide", null)
        } else {
            classifier.classify(mail.subject, mail.body, accountId)
        }

        // Arrêt de toutes les séquences du contact, quelle que soit l'intention
        val enrollments = contact?.let { sequenceRepo.enrollmentsOfContact(it.id) }.orEmpty()
            .filter { it.status == EnrollmentStatus.ACTIVE || it.status == EnrollmentStatus.PAUSED }
        enrollments.forEach { sequenceRepo.setEnrollmentStatus(it.id, SequenceEngine.onReply(result.intent)) }
        when (result.intent) {
            ReplyIntent.UNSUBSCRIBE -> replyRepo.suppress(mail.from, "réponse : ${result.summary}")
            ReplyIntent.BOUNCE -> contact?.email?.let { replyRepo.suppress(it, "rebond") }
            else -> {}
        }
        if (!mail.isBounce) contact?.let { contactRepo.markInbound(it.id) }

        // Enregistrée en dernier : si une étape plus haut échoue, le message sera retraité en entier
        replyRepo.saveReply(enrollmentId, mail.from, mail.subject, mail.body, result.intent, result.confidence, result.followUpOn,
            result.summary, mail.receivedAt, inboundKey = mail.key)
        when (result.intent) {
            ReplyIntent.UNSUBSCRIBE -> eventRepo.logEvent("UNSUBSCRIBED", accountId, contact?.id)
            ReplyIntent.BOUNCE -> eventRepo.logEvent("BOUNCED", accountId, contact?.id)
            ReplyIntent.INTERESTED, ReplyIntent.MEETING_REQUEST, ReplyIntent.REFERRAL -> eventRepo.logEvent("REPLY_POSITIVE", accountId, contact?.id)
            else -> {}
        }
        eventRepo.logEvent("REPLY_RECEIVED", accountId, contact?.id, payload = """{"intent":"${result.intent}"}""")
    }

    private val discoveryFlag = java.util.concurrent.atomic.AtomicBoolean(false)

    val discoveryRunning: Boolean get() = discoveryFlag.get()

    /** Null when a run is already going on (night run or "Chercher maintenant"). */
    suspend fun runDiscovery(): lab.doppio.bloom.data.discovery.DiscoveryReport? {
        if (!discoveryFlag.compareAndSet(false, true)) return null
        return try { discovery.run() } finally { discoveryFlag.set(false) }
    }

    /**
     * Les comptes au-dessus de 45 sans dossier passent en premier, puis les dossiers de plus de 14 jours.
     * L'agent coûte des tokens : on ne l'utilise que là où le score déterministe dit que ça vaut le coup.
     */
    suspend fun researchBatch() {
        val stale = clock.instant().minus(14, ChronoUnit.DAYS)
        val rows = accountRepo.accountsByScore(500).filter { it.score >= 45 }
        val withoutDossier = rows.filter { factRepo.dossier(it.account.id) == null }
        val staleOnes = rows.filter { it !in withoutDossier && (it.refreshedAt == null || Instant.parse(it.refreshedAt) < stale) }
        (withoutDossier + staleOnes).take(nightlyResearchBudget).forEach { row ->
            runCatching { research.research(row.account.id) }.onFailure { log.warn("Recherche échouée : ${row.account.name}", it) }
        }
    }
    /**
     * Tâches périodiques, réservées en base pour leur période : un redémarrage ne les relance pas et ne les
     * oublie pas. Chacune tourne à part, une découverte longue ne fait plus sauter la recherche ni la purge.
     *  - découverte : chaque nuit entre 2h et 5h, suivie de la recherche ;
     *  - recherche (agent IA) : une fois par jour, à partir de 5h si la découverte ne l'a pas lancée ;
     *  - note du coach : une fois par semaine, à partir du lundi 7h ;
     *  - purge RGPD : une fois par mois, à partir du 1er à 4h.
     */
    suspend fun nightly(now: ZonedDateTime = ZonedDateTime.now(clock.withZone(zone))) {
        val today = now.toLocalDate()
        // Every tick: the Codeur feed only keeps the latest projects, a nightly read would miss most of them
        runCatching { discovery.pollDemands() }.onFailure { log.warn("Lecture de Codeur en échec", it) }

        if (now.hour in 2..4) job("discovery", today) {
            runDiscovery()
            if (jobRunRepo.claimJob("research", today)) runJob("research") { researchBatch() }
        }
        if (now.hour >= 5) job("research", today) { researchBatch() }

        val monday = today.with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        if (today != monday || now.hour >= 7) job("coach", monday) {
            val memo = coach.weeklyMemo()
            eventRepo.logEvent("WEEKLY_MEMO", payload = kotlinx.serialization.json.JsonPrimitive(memo).toString())
        }

        val firstOfMonth = today.withDayOfMonth(1)
        if (today != firstOfMonth || now.hour >= 4) job("gdpr-purge", firstOfMonth) {
            val purged = contactRepo.purgeStaleContacts()
            val officers = officerRepo.purgeStaleOfficers()
            val demands = discoveryState.purgeOldDemands()
            eventRepo.logEvent("GDPR_PURGE", payload = """{"contacts":$purged,"officers":$officers,"demands":$demands}""")
        }
    }

    /** Claims the job for its period, then runs it apart from the loops. */
    private suspend fun job(name: String, period: java.time.LocalDate, block: suspend () -> Unit) {
        if (!jobRunRepo.claimJob(name, period)) return
        val scope = jobs
        if (scope == null) runJob(name, block) else scope.launch { runJob(name, block) }
    }

    private suspend fun runJob(name: String, block: suspend () -> Unit) {
        try {
            block()
            jobRunRepo.finishJob(name)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("Tâche $name en échec", e)
            jobRunRepo.finishJob(name, e.message ?: e.javaClass.simpleName)
        }
    }
}
