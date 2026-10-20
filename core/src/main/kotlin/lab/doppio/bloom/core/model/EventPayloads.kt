package lab.doppio.bloom.core.model

import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * JSON stored in the payload column of the event journal. The default [Json] writes properties in declaration
 * order and leaves out null defaults, so each class serializes to the exact string stored before they existed.
 */
private fun <T> encode(serializer: SerializationStrategy<T>, value: T): String = Json.encodeToString(serializer, value)

@Serializable
data class ImportPayload(val created: Int, val updated: Int, val contacts: Int) {
    fun toJson(): String = encode(serializer(), this)
}

/** A reply given up after repeated failures: which message, and who it came from. */
@Serializable
data class ReplyUnreadablePayload(val key: String, val from: String) {
    fun toJson(): String = encode(serializer(), this)
}

@Serializable
data class ReplyReceivedPayload(val intent: ReplyIntent) {
    fun toJson(): String = encode(serializer(), this)
}

@Serializable
data class GdprPurgePayload(val contacts: Int, val officers: Int, val demands: Int, val replyBodies: Int, val signals: Int, val seenKeys: Int) {
    fun toJson(): String = encode(serializer(), this)
}

/** Which source found the account, plus the detail that source adds (offers count or App Store id). */
@Serializable
data class AccountDiscoveredPayload(val source: String, val offers: Int? = null, val appId: Long? = null) {
    fun toJson(): String = encode(serializer(), this)
}

@Serializable
data class AccountResearchedPayload(val score: Int) {
    fun toJson(): String = encode(serializer(), this)
}

@Serializable
data class DraftCreatedPayload(val attempts: Int, val publishable: Boolean) {
    fun toJson(): String = encode(serializer(), this)
}
