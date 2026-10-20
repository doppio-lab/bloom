package lab.doppio.bloom.core.port

import java.time.Instant
import lab.doppio.bloom.core.model.Channel
import lab.doppio.bloom.core.model.Draft
import lab.doppio.bloom.core.model.DraftStatus

/** Drafts and LinkedIn tasks, and the send protocol: claim, send, record. A claimed draft is never sent twice. */
interface DraftRepository {
    suspend fun hasOpenDraft(enrollmentId: Long, stepIndex: Int): Boolean

    suspend fun rejectedDrafts(enrollmentId: Long, stepIndex: Int): Int

    suspend fun createDraft(d: Draft): Long

    suspend fun draft(id: Long): Draft?

    suspend fun draftsByStatus(status: DraftStatus, channel: Channel? = null): List<Draft>

    suspend fun decideDraft(id: Long, status: DraftStatus, editedSubject: String? = null, editedBody: String? = null)

    /** Exemples de messages corrigés à la main : servent d'exemples de style à l'agent rédacteur. */
    suspend fun humanEditedExamples(limit: Int = 5): List<Draft>

    /** Oldest approved email whose day has come and whose retry delay has passed. [now] defaults to the clock. */
    suspend fun nextToSend(now: Instant? = null): Draft?

    /** The step's due day: a draft prepared (and approved) earlier waits for it before it can leave. */
    suspend fun setSendAfter(id: Long, sendAfter: Instant)

    /**
     * Claims an approved draft before SMTP is called, with the Message-ID it will carry. Committed on its own,
     * so a crash during or after the send leaves it SENDING and it is never picked again.
     * False when someone else changed it first (rejected meanwhile, or claimed by another instance).
     */
    suspend fun claimForSending(id: Long, messageId: String, mailbox: String): Boolean

    /** The send went through: the draft is sent and the sequence moves on, in one transaction. */
    suspend fun recordSent(draft: Draft, threadMessageId: String, now: Instant? = null)

    /**
     * The send did not go through. When [retryable] (SMTP refused the message before accepting it), it goes back to
     * the queue after a growing delay (15, 30 min...) so the other emails keep going out, and becomes FAILED after
     * [maxAttempts]. Otherwise (timeout, lost connection: the message may have left) it is FAILED at once and a human
     * checks the sent folder. Returns the new status.
     */
    suspend fun recordSendFailure(id: Long, error: String, now: Instant? = null, maxAttempts: Int = MAX_SEND_ATTEMPTS, retryable: Boolean = true): DraftStatus

    /**
     * At startup: drafts still SENDING were interrupted mid-send. Whether they left is unknown, so they are
     * never sent again automatically: they become FAILED and a human checks the sent folder.
     */
    suspend fun reconcileInterruptedSends(): Int

    suspend fun sentTodayBy(mailbox: String, since: Instant): Int

    suspend fun lastSentAt(mailbox: String): Instant?

    suspend fun enrollmentByMessageId(messageId: String): Long?

    companion object {
        /** SMTP attempts before a draft becomes FAILED. */
        const val MAX_SEND_ATTEMPTS = 3

        /** Retry delay grows by this step per failed attempt (15, 30 min...). */
        const val RETRY_STEP_MINUTES = 15L
    }
}
