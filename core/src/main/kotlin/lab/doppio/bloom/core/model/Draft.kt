package lab.doppio.bloom.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class DraftStatus {
    PENDING_REVIEW, APPROVED,
    /** Claimed by the sender: SMTP is being called. Never picked again, even after a crash. */
    SENDING,
    REJECTED, SENT, TASK_DONE,
    /** Gave up after repeated SMTP errors, or interrupted while sending: a human checks the sent folder. */
    FAILED,
}

@Serializable
data class Draft(
    val id: Long = 0,
    val enrollmentId: Long,
    val stepIndex: Int,
    val channel: Channel,
    val subject: String?,
    val body: String,
    val citedFactIds: List<FactId>,
    val referenceUsed: String?,
    val rationale: String,
    val status: DraftStatus,
    val guardReport: List<String> = emptyList(),
)
