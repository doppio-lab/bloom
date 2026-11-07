package lab.doppio.bloom.data.persistence.sequence

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.CurrentTimestamp
import org.jetbrains.exposed.sql.javatime.timestamp

internal object SequencesTable : Table("sequences") {
    val id = long("id").autoIncrement()
    val name = text("name")
    val offer = text("offer")
    val steps = text("steps")
    override val primaryKey = PrimaryKey(id)
}

internal object EnrollmentsTable : Table("enrollments") {
    val id = long("id").autoIncrement()
    val contactId = long("contact_id")
    val sequenceId = long("sequence_id")
    val status = text("status")
    val nextStep = integer("next_step").default(0)
    val lastActionAt = timestamp("last_action_at").nullable()
    val enrolledAt = timestamp("enrolled_at").defaultExpression(CurrentTimestamp)
    val threadMessageId = text("thread_message_id").nullable()
    override val primaryKey = PrimaryKey(id)
}
