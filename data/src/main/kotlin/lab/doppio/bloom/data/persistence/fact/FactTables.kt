package lab.doppio.bloom.data.persistence.fact

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.CurrentTimestamp
import org.jetbrains.exposed.sql.javatime.timestamp

internal object FactsTable : Table("facts") {
    val id = text("id")
    val accountId = long("account_id")
    val kind = text("kind")
    val statement = text("statement")
    val sourceUrl = text("source_url")
    val excerpt = text("excerpt").nullable()
    val retrievedAt = timestamp("retrieved_at")
    override val primaryKey = PrimaryKey(id)
}

internal object SignalsTable : Table("signals") {
    val id = long("id").autoIncrement()
    val accountId = long("account_id")
    val type = text("type")
    val strength = double("strength")
    val observedAt = timestamp("observed_at")
    val factIds = text("fact_ids").default("[]")
    override val primaryKey = PrimaryKey(id)
}

internal object DossiersTable : Table("dossiers") {
    val accountId = long("account_id")
    val summary = text("summary")
    val hypotheses = text("hypotheses").default("[]")
    val angle = text("angle")
    val offer = text("offer")
    val noFit = bool("no_fit").default(false)
    val updatedAt = timestamp("updated_at").defaultExpression(CurrentTimestamp)
    override val primaryKey = PrimaryKey(accountId)
}
