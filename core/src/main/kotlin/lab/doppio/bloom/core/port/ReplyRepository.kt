package lab.doppio.bloom.core.port

import java.time.Instant
import java.time.LocalDate
import lab.doppio.bloom.core.model.ReplyIntent

/** Inbound replies and the suppression list (opt-outs, by address or by "@domain"). */
interface ReplyRepository {
    suspend fun saveReply(
        enrollmentId: Long?, from: String, subject: String?, body: String, intent: ReplyIntent,
        confidence: Double, followUpOn: LocalDate?, summary: String, receivedAt: Instant, inboundKey: String? = null,
    ): Long

    /** True when this inbound email was already recorded (see [saveReply]'s inboundKey). */
    suspend fun replyRecorded(inboundKey: String): Boolean

    suspend fun openReplies(): List<ReplyRow>

    suspend fun countOpenReplies(): Long

    suspend fun markReplyHandled(id: Long)

    suspend fun isSuppressed(email: String): Boolean

    suspend fun suppress(value: String, reason: String)
}
