package lab.doppio.bloom.data.persistence.discovery

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.CurrentTimestamp
import org.jetbrains.exposed.sql.javatime.timestamp

internal object DiscoverySeenTable : Table("discovery_seen") {
    val key = text("key")
    val kept = bool("kept")
    val reason = text("reason").nullable()
    val checkedAt = timestamp("checked_at").defaultExpression(CurrentTimestamp)
    override val primaryKey = PrimaryKey(key)
}

internal object DemandsTable : Table("demands") {
    val key = text("key")
    // Named origin: Table already exposes a `source` member (ColumnSet.source)
    val origin = text("source")
    val title = text("title")
    val url = text("url")
    val budget = text("budget").nullable()
    val categories = text("categories").default("[]")
    val publishedAt = timestamp("published_at").nullable()
    val dismissedAt = timestamp("dismissed_at").nullable()
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
    override val primaryKey = PrimaryKey(key)
}

internal object AccountJobBoardsTable : Table("job_boards") {
    val accountId = long("account_id")
    val provider = text("provider").nullable()
    val token = text("token").nullable()
    val checkedAt = timestamp("checked_at").defaultExpression(CurrentTimestamp)
    override val primaryKey = PrimaryKey(accountId)
}
