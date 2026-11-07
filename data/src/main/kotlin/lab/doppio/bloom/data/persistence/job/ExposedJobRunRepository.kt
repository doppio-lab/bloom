package lab.doppio.bloom.data.persistence.job

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import lab.doppio.bloom.core.port.JobRunRepository
import lab.doppio.bloom.data.persistence.dbQuery
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.upsert

class ExposedJobRunRepository(private val clock: Clock = Clock.systemDefaultZone()) : JobRunRepository {

    override suspend fun claimJob(name: String, period: LocalDate, now: Instant?): Boolean = dbQuery {
        val at = now ?: clock.instant()
        JobRunsTable.upsert(
            onUpdate = { it[JobRunsTable.period] = period; it[JobRunsTable.startedAt] = at; it[JobRunsTable.finishedAt] = null; it[JobRunsTable.error] = null },
            where = { JobRunsTable.period less period },
        ) {
            it[this.name] = name; it[this.period] = period; it[startedAt] = at
        }.insertedCount > 0
    }

    override suspend fun finishJob(name: String, error: String?, now: Instant?) {
        val at = now ?: clock.instant()
        dbQuery { JobRunsTable.update({ JobRunsTable.name eq name }) { it[finishedAt] = at; it[this.error] = error?.take(500) } }
    }
}
