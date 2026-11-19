package lab.doppio.bloom.data.persistence.officer

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.CurrentTimestamp
import org.jetbrains.exposed.v1.javatime.timestamp

internal object OfficersTable : Table("officers") {
    val accountId = long("account_id")
    val fullName = text("full_name")
    val role = text("role")
    val sourceUrl = text("source_url")
    val fetchedAt = timestamp("fetched_at").defaultExpression(CurrentTimestamp)
    override val primaryKey = PrimaryKey(accountId, fullName, role)
}
