package lab.doppio.bloom.app.outreach

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
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
import lab.doppio.bloom.data.db.Repository
import org.slf4j.LoggerFactory
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Boucles de fond. Chaque boucle est idempotente : un redémarrage ne double ni un envoi ni un brouillon.
 *
 *  - planification : prépare les brouillons dus (agent rédacteur) ;
 *  - envoi : envoie les emails approuvés, dans la fenêtre, sous les plafonds, une boîte après l'autre ;
 *  - relève : classe les réponses, arrête les séquences, gère l'opposition ;
 *  - nuit : recherche et rescoring des comptes ; lundi : note du coach ; mensuel : purge RGPD.
 */
private const val MAX_REJECTIONS_PER_STEP = 3

/** Upper bound of one SMTP send and of one IMAP pass over every mailbox. */
private val SEND_TIMEOUT = 60.seconds
private val POLL_TIMEOUT = 120.seconds

class Orchestrator(
    private val repo: Repository,
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
    private val instanceLock: () -> Boolean = { lab.doppio.bloom.data.db.Db.tryHoldSingleInstanceLock() },
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
            val interrupted = repo.reconcileInterruptedSends()
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

    suspend fun planDrafts(now: Instant = Instant.now()) = planning.withLock { planDraftsLocked(now) }

    private suspend fun planDraftsLocked(now: Instant) {
        val sequences = repo.sequences().associateBy { it.id }
        for (e in repo.activeEnrollments()) {
            val seq = sequences[e.sequenceId] ?: continue
            when (val action = SequenceEngine.next(e, seq, policy, now)) {
                is NextAction.PrepareDraft -> if (!repo.hasOpenDraft(e.id, action.step.index)) {
                    val contact = repo.contact(e.contactId) ?: continue
                    if (contact.email != null && repo.isSuppressed(contact.email!!)) {
                        repo.setEnrollmentStatus(e.id, EnrollmentStatus.STOPPED_UNSUBSCRIBE)
                        continue
                    }
                    // Trois brouillons rejetés pour la même étape : la séquence s'arrête au lieu d'en réécrire sans fin
                    if (repo.rejectedDrafts(e.id, action.step.index) >= MAX_REJECTIONS_PER_STEP) {
                        repo.setEnrollmentStatus(e.id, EnrollmentStatus.STOPPED_MANUAL)
                        repo.logEvent("ENROLLMENT_STOPPED_REJECTIONS", contact.accountId, contact.id)
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
                is NextAction.Finish -> if (action.status != e.status) repo.setEnrollmentStatus(e.id, action.status)
                is NextAction.WaitUntil -> {}
            }
        }
    }

    suspend fun sendApproved(now: Instant = Instant.now()) {
        if (!SequenceEngine.inWindow(now, policy)) return
        val startOfDay = now.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant()
        val draft = repo.nextToSend(now) ?: return

        // Une seule boîte disponible par passage : on respecte l'écart minimal entre deux envois.
        val mailbox = mail.mailboxes.firstOrNull { mb ->
            repo.sentTodayBy(mb.address, startOfDay) < policy.maxPerMailboxPerDay &&
                (repo.lastSentAt(mb.address)?.let { Duration.between(it, now).seconds >= policy.minSecondsBetweenSends } ?: true)
        } ?: return

        val enrollment = repo.enrollment(draft.enrollmentId) ?: run { repo.decideDraft(draft.id, DraftStatus.REJECTED); return }
        if (enrollment.status != EnrollmentStatus.ACTIVE) {
            repo.decideDraft(draft.id, DraftStatus.REJECTED); return
        }
        val contact = repo.contact(enrollment.contactId) ?: run { repo.decideDraft(draft.id, DraftStatus.REJECTED); return }
        val to = contact.email ?: run { repo.decideDraft(draft.id, DraftStatus.REJECTED); return }
        if (repo.isSuppressed(to)) {
            repo.decideDraft(draft.id, DraftStatus.REJECTED)
            repo.setEnrollmentStatus(enrollment.id, EnrollmentStatus.STOPPED_UNSUBSCRIBE)
            return
        }

        // Réservé avant l'appel SMTP : après un crash, ce brouillon ne repart jamais tout seul.
        val messageId = gateway.newMessageId(mailbox)
        if (!repo.claimForSending(draft.id, messageId, mailbox.address)) return
        val thread = repo.threadMessageId(enrollment.id)
        try {
            // Bounded: the socket timeouts cover one stalled call, this covers the whole exchange
            withTimeout(SEND_TIMEOUT) {
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
            }
        } catch (e: TimeoutCancellationException) {
            // Not a real cancellation: the draft must be settled like any other failed send
            val status = repo.recordSendFailure(draft.id, "Délai d'envoi dépassé", now, retryable = false)
            log.warn("Envoi du brouillon ${draft.id} sans réponse du serveur, statut $status")
            repo.logEvent("EMAIL_FAILED", contact.accountId, contact.id, draft.id)
            return
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Only a refusal received before acceptance is retried: after a timeout the message may have left
            val status = repo.recordSendFailure(draft.id, e.message ?: e.javaClass.simpleName, now, retryable = isRetryableSmtpFailure(e))
            log.warn("Envoi du brouillon ${draft.id} en échec, statut $status", e)
            if (status == DraftStatus.FAILED) repo.logEvent("EMAIL_FAILED", contact.accountId, contact.id, draft.id)
            return
        }
        repo.recordSent(draft, threadMessageId = thread ?: messageId, now = now)
        repo.logEvent("EMAIL_SENT", contact.accountId, contact.id, draft.id)
    }

    /** Une tâche LinkedIn ou téléphone faite à la main fait avancer la séquence. */
    suspend fun completeTask(draftId: Long) {
        val draft = repo.draft(draftId) ?: return
        repo.decideDraft(draftId, DraftStatus.TASK_DONE)
        repo.advance(draft.enrollmentId, draft.stepIndex + 1)
        val e = repo.enrollment(draft.enrollmentId)
        val c = e?.let { repo.contact(it.contactId) }
        repo.logEvent("${draft.channel}_TASK_DONE", c?.accountId, c?.id, draftId)
    }

    /**
     * Each message is marked seen only once processed. One that fails stays unread and is retried at the
     * next pass; one already recorded (marking it seen failed last time) is skipped.
     */
    suspend fun processReplies() {
        val processed = mutableMapOf<String, MutableList<Long>>()
        for (mail in withTimeout(POLL_TIMEOUT) { poller.poll() }) {
            try {
                if (!repo.replyRecorded(mail.key)) processReply(mail)
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
        val enrollmentId = mail.inReplyTo.firstNotNullOfOrNull { repo.enrollmentByMessageId(it) }
        val enrolled = enrollmentId?.let { repo.enrollment(it) }?.let { repo.contact(it.contactId) }
        // Un rebond vient du serveur de messagerie : le contact est celui de l'email d'origine
        val contact = if (mail.isBounce) enrolled else repo.contactByEmail(mail.from) ?: enrolled
        val accountId = contact?.accountId
        val result = if (mail.isBounce) {
            ReplyClassifierAgent.Result(ReplyIntent.BOUNCE, 1.0, null, null, "Rebond : adresse invalide", null)
        } else {
            classifier.classify(mail.subject, mail.body, accountId)
        }

        // Arrêt de toutes les séquences du contact, quelle que soit l'intention
        val enrollments = contact?.let { repo.enrollmentsOfContact(it.id) }.orEmpty()
            .filter { it.status == EnrollmentStatus.ACTIVE || it.status == EnrollmentStatus.PAUSED }
        enrollments.forEach { repo.setEnrollmentStatus(it.id, SequenceEngine.onReply(result.intent)) }
        when (result.intent) {
            ReplyIntent.UNSUBSCRIBE -> repo.suppress(mail.from, "réponse : ${result.summary}")
            ReplyIntent.BOUNCE -> contact?.email?.let { repo.suppress(it, "rebond") }
            else -> {}
        }
        if (!mail.isBounce) contact?.let { repo.markInbound(it.id) }

        // Enregistrée en dernier : si une étape plus haut échoue, le message sera retraité en entier
        repo.saveReply(enrollmentId, mail.from, mail.subject, mail.body, result.intent, result.confidence, result.followUpOn,
            result.summary, mail.receivedAt, inboundKey = mail.key)
        when (result.intent) {
            ReplyIntent.UNSUBSCRIBE -> repo.logEvent("UNSUBSCRIBED", accountId, contact?.id)
            ReplyIntent.BOUNCE -> repo.logEvent("BOUNCED", accountId, contact?.id)
            ReplyIntent.INTERESTED, ReplyIntent.MEETING_REQUEST, ReplyIntent.REFERRAL -> repo.logEvent("REPLY_POSITIVE", accountId, contact?.id)
            else -> {}
        }
        repo.logEvent("REPLY_RECEIVED", accountId, contact?.id, payload = """{"intent":"${result.intent}"}""")
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
        val stale = Instant.now().minus(14, ChronoUnit.DAYS)
        val rows = repo.accountsByScore(500).filter { it.score >= 45 }
        val withoutDossier = rows.filter { repo.dossier(it.account.id) == null }
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
    suspend fun nightly(now: ZonedDateTime = ZonedDateTime.now(zone)) {
        val today = now.toLocalDate()
        // Every tick: the Codeur feed only keeps the latest projects, a nightly read would miss most of them
        runCatching { discovery.pollDemands() }.onFailure { log.warn("Lecture de Codeur en échec", it) }

        if (now.hour in 2..4) job("discovery", today) {
            runDiscovery()
            if (repo.claimJob("research", today)) runJob("research") { researchBatch() }
        }
        if (now.hour >= 5) job("research", today) { researchBatch() }

        val monday = today.with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        if (today != monday || now.hour >= 7) job("coach", monday) {
            val memo = coach.weeklyMemo()
            repo.logEvent("WEEKLY_MEMO", payload = kotlinx.serialization.json.JsonPrimitive(memo).toString())
        }

        val firstOfMonth = today.withDayOfMonth(1)
        if (today != firstOfMonth || now.hour >= 4) job("gdpr-purge", firstOfMonth) {
            val purged = repo.purgeStaleContacts()
            val officers = repo.purgeStaleOfficers()
            val demands = repo.purgeOldDemands()
            repo.logEvent("GDPR_PURGE", payload = """{"contacts":$purged,"officers":$officers,"demands":$demands}""")
        }
    }

    /** Claims the job for its period, then runs it apart from the loops. */
    private suspend fun job(name: String, period: java.time.LocalDate, block: suspend () -> Unit) {
        if (!repo.claimJob(name, period)) return
        val scope = jobs
        if (scope == null) runJob(name, block) else scope.launch { runJob(name, block) }
    }

    private suspend fun runJob(name: String, block: suspend () -> Unit) {
        try {
            block()
            repo.finishJob(name)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("Tâche $name en échec", e)
            repo.finishJob(name, e.message ?: e.javaClass.simpleName)
        }
    }
}
