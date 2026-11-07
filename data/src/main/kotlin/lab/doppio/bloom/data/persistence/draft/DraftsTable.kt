package lab.doppio.bloom.data.persistence.draft

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.CurrentTimestamp
import org.jetbrains.exposed.sql.javatime.timestamp

internal object DraftsTable : Table("drafts") {
    val id = long("id").autoIncrement()
    val enrollmentId = long("enrollment_id")
    val stepIndex = integer("step_index")
    val channel = text("channel")
    val subject = text("subject").nullable()
    val body = text("body")
    val citedFactIds = text("cited_fact_ids").default("[]")
    val referenceUsed = text("reference_used").nullable()
    val rationale = text("rationale")
    val status = text("status")
    val guardReport = text("guard_report").default("[]")
    val editedByHuman = bool("edited_by_human").default(false)
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
    val decidedAt = timestamp("decided_at").nullable()
    val sentAt = timestamp("sent_at").nullable()
    val messageId = text("message_id").nullable()
    val mailbox = text("mailbox").nullable()
    val sendAttempts = integer("send_attempts").default(0)
    val nextAttemptAt = timestamp("next_attempt_at").nullable()
    val lastError = text("last_error").nullable()
    override val primaryKey = PrimaryKey(id)
}
