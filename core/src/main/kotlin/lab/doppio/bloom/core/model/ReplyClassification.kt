package lab.doppio.bloom.core.model

import java.time.LocalDate

/** Outcome of reading an inbound reply: the intent plus what the team needs to follow up. */
data class ReplyClassification(
    val intent: ReplyIntent,
    val confidence: Double,
    val followUpOn: LocalDate?,
    val referral: String?,
    val summary: String,
    val suggestedReply: String?,
)
