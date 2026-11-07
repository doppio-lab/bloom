package lab.doppio.bloom.core.port

import java.time.Instant
import lab.doppio.bloom.core.model.AccountId
import lab.doppio.bloom.core.model.ContactId

/** The event journal that feeds the dashboard and the weekly coach. */
interface EventRepository {
    suspend fun logEvent(type: String, accountId: AccountId? = null, contactId: ContactId? = null, draftId: Long? = null, payload: String = "{}")

    /** Payload and time of the most recent event of a type. */
    suspend fun lastEvent(type: String): Pair<String, Instant>?

    suspend fun countEvents(type: String, since: Instant): Int

    /** When each event of these types happened since [since], for the activity chart of the dashboard. */
    suspend fun eventTimes(types: Set<String>, since: Instant): List<Pair<String, Instant>>

    suspend fun eventsSince(since: Instant): List<Triple<String, Long?, Instant>>
}
