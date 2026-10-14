package lab.doppio.bloom.app.outreach

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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

/**
 * Boucles de fond. Chaque boucle est idempotente : un redémarrage ne double ni un envoi ni un brouillon.
 *
 *  - planification : prépare les brouillons dus (agent rédacteur) ;
 *  - envoi : envoie les emails approuvés, dans la fenêtre, sous les plafonds, une boîte après l'autre ;
 *  - relève : classe les réponses, arrête les séquences, gère l'opposition ;
 *  - nuit : recherche et rescoring des comptes ; lundi : note du coach ; mensuel : purge RGPD.
 */
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
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val zone = ZoneId.of(policy.timezone)

    fun start(scope: CoroutineScope) {
        scope.launch {
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

    suspend fun planDrafts(now: Instant = Instant.now()) {
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
                    runCatching { writer.draft(e, seq, action.step) }
                        .onFailure { log.warn("Brouillon impossible pour l'inscription ${e.id}", it) }
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
            val status = repo.recordSendFailure(draft.id, e.message ?: e.javaClass.simpleName, now)
            log.warn("Envoi du brouillon ${draft.id} refusé, statut $status", e)
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
        for (mail in poller.poll()) {
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

    private var lastNightly: java.time.LocalDate? = null
    private var lastDiscovery: java.time.LocalDate? = null

    @Volatile var discoveryRunning: Boolean = false
        private set

    suspend fun runDiscovery(): lab.doppio.bloom.data.discovery.DiscoveryReport? {
        if (discoveryRunning) return null
        discoveryRunning = true
        return try { discovery.run() } finally { discoveryRunning = false }
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
    private var lastCoach: java.time.LocalDate? = null
    private var lastPurge: java.time.LocalDate? = null

    suspend fun nightly(now: ZonedDateTime = ZonedDateTime.now(zone)) {
        val today = now.toLocalDate()
        // Every tick: the Codeur feed only keeps the latest projects, a nightly read would miss most of them
        runCatching { discovery.pollDemands() }.onFailure { log.warn("Lecture de Codeur en échec", it) }
        // 2h-4h : découverte de nouvelles entreprises (offres d'emploi, classements App Store)
        if (now.hour in 2..3 && lastDiscovery != today) {
            lastDiscovery = today
            runDiscovery()
        }
        // 5h-6h : recherche approfondie (agent IA) des comptes qui valent le coup, nouveaux d'abord
        if (now.hour in 5..6 && lastNightly != today) {
            lastNightly = today
            researchBatch()
        }
        if (now.dayOfWeek == DayOfWeek.MONDAY && now.hour == 7 && lastCoach != today) {
            lastCoach = today
            val memo = coach.weeklyMemo()
            repo.logEvent("WEEKLY_MEMO", payload = kotlinx.serialization.json.JsonPrimitive(memo).toString())
        }
        if (now.dayOfMonth == 1 && now.hour == 4 && lastPurge != today) {
            lastPurge = today
            val purged = repo.purgeStaleContacts()
            val officers = repo.purgeStaleOfficers()
            val demands = repo.purgeOldDemands()
            repo.logEvent("GDPR_PURGE", payload = """{"contacts":$purged,"officers":$officers,"demands":$demands}""")
        }
    }
}
