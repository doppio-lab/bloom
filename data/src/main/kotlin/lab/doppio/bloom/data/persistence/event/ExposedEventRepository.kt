package lab.doppio.bloom.data.persistence.event

import java.time.Instant
import lab.doppio.bloom.core.model.AccountId
import lab.doppio.bloom.core.model.ContactId
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

    override suspend fun logEvent(type: String, accountId: AccountId?, contactId: ContactId?, draftId: Long?, payload: String) {
        dbQuery {
            EventsTable.insert { it[this.type] = type; it[this.accountId] = accountId; it[this.contactId] = contactId; it[this.draftId] = draftId; it[this.payload] = payload }
        }
    }

    override suspend fun lastEvent(type: String): Pair<String, Instant>? = dbQuery {
        EventsTable.selectAll().where { EventsTable.type eq type }.orderBy(EventsTable.occurredAt, SortOrder.DESC).limit(1)
            .firstOrNull()?.let { it[EventsTable.payload] to it[EventsTable.occurredAt] }
    }

    override suspend fun countEvents(type: String, since: Instant): Int = dbQuery {
        EventsTable.selectAll().where { (EventsTable.type eq type) and (EventsTable.occurredAt greaterEq since) }.count().toInt()
    }

    override suspend fun eventTimes(types: Set<String>, since: Instant): List<Pair<String, Instant>> = dbQuery {
        EventsTable.select(EventsTable.type, EventsTable.occurredAt).where { (EventsTable.type inList types) and (EventsTable.occurredAt greaterEq since) }
            .map { it[EventsTable.type] to it[EventsTable.occurredAt] }
    }

    override suspend fun eventsSince(since: Instant): List<Triple<String, Long?, Instant>> = dbQuery {
        EventsTable.selectAll().where { EventsTable.occurredAt greaterEq since }.map { Triple(it[EventsTable.type], it[EventsTable.accountId], it[EventsTable.occurredAt]) }
    }
}
