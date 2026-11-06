package lab.doppio.bloom.core.usecase

import kotlinx.coroutines.CancellationException
import lab.doppio.bloom.core.model.InboundMail
import lab.doppio.bloom.core.model.ReplyClassification
import lab.doppio.bloom.core.model.ReplyIntent
import lab.doppio.bloom.core.port.ContactRepository
import lab.doppio.bloom.core.port.DraftRepository
import lab.doppio.bloom.core.port.EventRepository
import lab.doppio.bloom.core.port.Inbox
import lab.doppio.bloom.core.port.ReplyClassifier
import lab.doppio.bloom.core.port.ReplyRepository
import lab.doppio.bloom.core.port.SequenceRepository
import lab.doppio.bloom.core.sequence.SequenceEngine
import org.slf4j.LoggerFactory

/**
 * Reads the inbox, classifies each reply, stops the sequences of the contact and records opt-outs and bounces.
 * Each message is marked seen only once processed. One that fails stays unread and is retried at the
 * next pass; one already recorded (marking it seen failed last time) is skipped.
 */
class ProcessReplies(
    private val inbox: Inbox,
    private val classifier: ReplyClassifier,
    private val replyRepo: ReplyRepository,
    private val draftRepo: DraftRepository,
    private val sequenceRepo: SequenceRepository,
    private val contactRepo: ContactRepository,
    private val eventRepo: EventRepository,
    private val stop: StopContactSequences,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    suspend operator fun invoke() {
        val processed = mutableMapOf<String, MutableList<Long>>()
        for (mail in inbox.poll()) {
            try {
                if (!replyRepo.replyRecorded(mail.key)) process(mail)
                processed.getOrPut(mail.mailbox) { mutableListOf() } += mail.uid
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Réponse non traitée, relue à la prochaine passe : ${mail.key}", e)
            }
        }
        processed.forEach { (mailbox, uids) ->
            runCatching { inbox.markSeen(mailbox, uids) }.onFailure { log.warn("Impossible de marquer lus les messages de $mailbox", it) }
        }
    }

    private suspend fun process(mail: InboundMail) {
        val enrollmentId = mail.inReplyTo.firstNotNullOfOrNull { draftRepo.enrollmentByMessageId(it) }
        val enrolled = enrollmentId?.let { sequenceRepo.enrollment(it) }?.let { contactRepo.contact(it.contactId) }
        // Un rebond vient du serveur de messagerie : le contact est celui de l'email d'origine
        val contact = if (mail.isBounce) enrolled else contactRepo.contactByEmail(mail.from) ?: enrolled
        val accountId = contact?.accountId
        val result = if (mail.isBounce) {
            ReplyClassification(ReplyIntent.BOUNCE, 1.0, null, null, "Rebond : adresse invalide", null)
        } else {
            classifier.classify(mail.subject, mail.body, accountId)
        }

        // Arrêt de toutes les séquences du contact, quelle que soit l'intention
        contact?.let { stop(it.id, SequenceEngine.onReply(result.intent)) }
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
}
