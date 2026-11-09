package lab.doppio.bloom.core.model

import kotlinx.serialization.Serializable
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

@Serializable
enum class ReplyIntent {
    INTERESTED, MEETING_REQUEST, NOT_NOW, WRONG_PERSON, REFERRAL, NOT_INTERESTED, UNSUBSCRIBE, OUT_OF_OFFICE, BOUNCE, OTHER
}
