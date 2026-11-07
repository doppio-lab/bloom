package lab.doppio.bloom.core.port

import java.time.Instant
import lab.doppio.bloom.core.model.AccountId
import lab.doppio.bloom.core.model.ContactId
import lab.doppio.bloom.core.model.EventType

/** The event journal that feeds the dashboard and the weekly coach. */
interface EventRepository {
    suspend fun logEvent(type: EventType, accountId: AccountId? = null, contactId: ContactId? = null, draftId: Long? = null, payload: String = "{}")

    /** Payload and time of the most recent event of a type. */
    suspend fun lastEvent(type: EventType): Pair<String, Instant>?

    suspend fun countEvents(type: EventType, since: Instant): Int

    /** When each event of these types happened since [since], for the activity chart of the dashboard. */
    suspend fun eventTimes(types: Set<EventType>, since: Instant): List<Pair<EventType, Instant>>

    /** Events of every known type since [since], as (type, account, time). */
    suspend fun eventsSince(since: Instant): List<Triple<EventType, Long?, Instant>>
}
