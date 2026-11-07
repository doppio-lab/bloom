package lab.doppio.bloom.data.persistence.officer

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.CurrentTimestamp
import org.jetbrains.exposed.sql.javatime.timestamp

internal object OfficersTable : Table("officers") {
    val accountId = long("account_id")
    val fullName = text("full_name")
    val role = text("role")
    val sourceUrl = text("source_url")
    val fetchedAt = timestamp("fetched_at").defaultExpression(CurrentTimestamp)
    override val primaryKey = PrimaryKey(accountId, fullName, role)
}
