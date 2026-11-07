package lab.doppio.bloom.data.persistence.reply

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.CurrentTimestamp
import org.jetbrains.exposed.sql.javatime.date
import org.jetbrains.exposed.sql.javatime.timestamp

internal object RepliesTable : Table("replies") {
    val id = long("id").autoIncrement()
    val enrollmentId = long("enrollment_id").nullable()
    val fromAddress = text("from_address")
    val subject = text("subject").nullable()
    val body = text("body")
    val intent = text("intent")
    val confidence = double("confidence")
    val followUpOn = date("follow_up_on").nullable()
    val summary = text("summary")
    val receivedAt = timestamp("received_at")
    val handled = bool("handled").default(false)
    val inboundKey = text("inbound_key").nullable()
    override val primaryKey = PrimaryKey(id)
}

internal object SuppressionsTable : Table("suppressions") {
    val value = text("value")
    val reason = text("reason")
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
    override val primaryKey = PrimaryKey(value)
}
