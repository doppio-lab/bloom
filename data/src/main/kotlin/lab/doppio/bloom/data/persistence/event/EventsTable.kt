package lab.doppio.bloom.data.persistence.event

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.CurrentTimestamp
import org.jetbrains.exposed.sql.javatime.timestamp

internal object EventsTable : Table("events") {
    val id = long("id").autoIncrement()
    val type = text("type")
    val accountId = long("account_id").nullable()
    val contactId = long("contact_id").nullable()
    val draftId = long("draft_id").nullable()
    val payload = text("payload").default("{}")
    val occurredAt = timestamp("occurred_at").defaultExpression(CurrentTimestamp)
    override val primaryKey = PrimaryKey(id)
}
