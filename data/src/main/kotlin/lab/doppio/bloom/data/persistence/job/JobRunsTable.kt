package lab.doppio.bloom.data.persistence.job

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.CurrentTimestamp
import org.jetbrains.exposed.v1.javatime.date
import org.jetbrains.exposed.v1.javatime.timestamp

internal object JobRunsTable : Table("job_runs") {
    val name = text("name")
    val period = date("period")
    val startedAt = timestamp("started_at").defaultExpression(CurrentTimestamp)
    val finishedAt = timestamp("finished_at").nullable()
    val error = text("error").nullable()
    override val primaryKey = PrimaryKey(name)
}
