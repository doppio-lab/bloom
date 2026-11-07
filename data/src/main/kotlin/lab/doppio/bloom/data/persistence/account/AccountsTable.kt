package lab.doppio.bloom.data.persistence.account

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.CurrentTimestamp
import org.jetbrains.exposed.sql.javatime.timestamp

internal object AccountsTable : Table("accounts") {
    val id = long("id").autoIncrement()
    val name = text("name")
    val domain = text("domain").nullable()
    val slug = text("slug")
    // Named origin: Table already exposes a `source` member (ColumnSet.source)
    val origin = text("source").default("manual")
    val excludedReason = text("excluded_reason").nullable()
    val excludedAt = timestamp("excluded_at").nullable()
    val officersCheckedAt = timestamp("officers_checked_at").nullable()
    val siren = text("siren").nullable()
    val sector = text("sector")
    val market = text("market")
    val headcount = integer("headcount").nullable()
    val iosAppId = long("ios_app_id").nullable()
    val androidPackage = text("android_package").nullable()
    val warmPath = text("warm_path").nullable()
    val ownerNote = text("owner_note").nullable()
    val score = integer("score").default(0)
    val scoreDetail = text("score_detail").default("{}")
    val recommendedOffer = text("recommended_offer").nullable()
    val refreshedAt = timestamp("refreshed_at").nullable()
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
    override val primaryKey = PrimaryKey(id)
}

internal object BlocklistTable : Table("blocklist") {
    val id = long("id").autoIncrement()
    val name = text("name")
    val slug = text("slug")
    val reason = text("reason").nullable()
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
    override val primaryKey = PrimaryKey(id)
}
