package lab.doppio.bloom.data.persistence.deal

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.CurrentTimestamp
import org.jetbrains.exposed.v1.javatime.date
import org.jetbrains.exposed.v1.javatime.timestamp

internal object DealsTable : Table("deals") {
    val id = long("id").autoIncrement()
    val accountId = long("account_id")
    val offer = text("offer")
    val stage = text("stage")
    val amountEur = long("amount_eur")
    val sourceSignal = text("source_signal").nullable()
    val expectedClose = date("expected_close").nullable()
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
    val updatedAt = timestamp("updated_at").defaultExpression(CurrentTimestamp)
    override val primaryKey = PrimaryKey(id)
}
