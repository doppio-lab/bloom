package lab.doppio.bloom.data.persistence.event

import java.time.Instant
import lab.doppio.bloom.core.model.AccountId
import lab.doppio.bloom.core.model.ContactId
import lab.doppio.bloom.core.model.EventType
import lab.doppio.bloom.core.port.EventRepository
import lab.doppio.bloom.data.persistence.dbQuery
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll

class ExposedEventRepository : EventRepository {

    override suspend fun logEvent(type: EventType, accountId: AccountId?, contactId: ContactId?, draftId: Long?, payload: String) {
        dbQuery {
            EventsTable.insert { it[this.type] = type.name; it[this.accountId] = accountId; it[this.contactId] = contactId; it[this.draftId] = draftId; it[this.payload] = payload }
        }
    }

    override suspend fun lastEvent(type: EventType): Pair<String, Instant>? = dbQuery {
        EventsTable.selectAll().where { EventsTable.type eq type.name }.orderBy(EventsTable.occurredAt, SortOrder.DESC).limit(1)
            .firstOrNull()?.let { it[EventsTable.payload] to it[EventsTable.occurredAt] }
    }

    override suspend fun countEvents(type: EventType, since: Instant): Int = dbQuery {
        EventsTable.selectAll().where { (EventsTable.type eq type.name) and (EventsTable.occurredAt greaterEq since) }.count().toInt()
    }

    override suspend fun eventTimes(types: Set<EventType>, since: Instant): List<Pair<EventType, Instant>> = dbQuery {
        EventsTable.select(EventsTable.type, EventsTable.occurredAt).where { (EventsTable.type inList types.map { it.name }) and (EventsTable.occurredAt greaterEq since) }
            .mapNotNull { row -> EventType.fromName(row[EventsTable.type])?.let { it to row[EventsTable.occurredAt] } }
    }

    override suspend fun eventsSince(since: Instant): List<Triple<EventType, Long?, Instant>> = dbQuery {
        EventsTable.selectAll().where { EventsTable.occurredAt greaterEq since }.mapNotNull { row -> EventType.fromName(row[EventsTable.type])?.let { Triple(it, row[EventsTable.accountId], row[EventsTable.occurredAt]) } }
    }
}
