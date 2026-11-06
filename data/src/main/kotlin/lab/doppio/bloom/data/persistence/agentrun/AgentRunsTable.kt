package lab.doppio.bloom.data.persistence.agentrun

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.CurrentTimestamp
import org.jetbrains.exposed.sql.javatime.timestamp

internal object AgentRunsTable : Table("agent_runs") {
    val id = long("id").autoIncrement()
    val agent = text("agent")
    val accountId = long("account_id").nullable()
    val model = text("model")
    val inputTokens = integer("input_tokens")
    val outputTokens = integer("output_tokens")
    val durationMs = long("duration_ms")
    val success = bool("success")
    val error = text("error").nullable()
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
    override val primaryKey = PrimaryKey(id)
}
