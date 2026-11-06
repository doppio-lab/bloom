package lab.doppio.bloom.data.persistence.contact

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.CurrentTimestamp
import org.jetbrains.exposed.sql.javatime.timestamp

internal object ContactsTable : Table("contacts") {
    val id = long("id").autoIncrement()
    val accountId = long("account_id")
    val fullName = text("full_name")
    val role = text("role")
    val persona = text("persona")
    val email = text("email").nullable()
    val linkedinUrl = text("linkedin_url").nullable()
    val dataSource = text("data_source")
    val language = text("language").default("fr")
    val lastInboundAt = timestamp("last_inbound_at").nullable()
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
    override val primaryKey = PrimaryKey(id)
}
