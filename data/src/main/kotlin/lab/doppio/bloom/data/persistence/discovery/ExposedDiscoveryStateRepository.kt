package lab.doppio.bloom.data.persistence.discovery

import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import lab.doppio.bloom.core.discovery.BoardProvider
import lab.doppio.bloom.core.discovery.BoardRef
import lab.doppio.bloom.core.model.AccountId
import lab.doppio.bloom.core.model.Demand
import lab.doppio.bloom.core.port.DiscoveryStateRepository
import lab.doppio.bloom.data.persistence.dbQuery
import lab.doppio.bloom.data.persistence.decodeList
import lab.doppio.bloom.data.persistence.encode
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.upsert

class ExposedDiscoveryStateRepository(private val clock: Clock = Clock.systemDefaultZone()) : DiscoveryStateRepository {

    override suspend fun wasSeen(key: String, withinDays: Long): Boolean = dbQuery {
        val since = clock.instant().minus(withinDays, ChronoUnit.DAYS)
        DiscoverySeenTable.selectAll().where { (DiscoverySeenTable.key eq key) and (DiscoverySeenTable.checkedAt greaterEq since) }.any()
    }

    override suspend fun markSeen(key: String, kept: Boolean, reason: String?) {
        dbQuery { DiscoverySeenTable.upsert { it[this.key] = key; it[this.kept] = kept; it[this.reason] = reason; it[checkedAt] = clock.instant() } }
    }

    override suspend fun saveDemand(d: Demand): Boolean = dbQuery {
        DemandsTable.insertIgnore {
            it[key] = d.key; it[origin] = d.source; it[title] = d.title; it[url] = d.url; it[budget] = d.budget
            it[categories] = d.categories.encode(); it[publishedAt] = d.publishedAt?.let(Instant::parse)
        }.insertedCount > 0
    }

    override suspend fun openDemands(limit: Int): List<Demand> = dbQuery {
        DemandsTable.selectAll().where { DemandsTable.dismissedAt.isNull() }.orderBy(DemandsTable.publishedAt, SortOrder.DESC_NULLS_LAST).limit(limit).map {
            Demand(it[DemandsTable.key], it[DemandsTable.origin], it[DemandsTable.title], it[DemandsTable.url], it[DemandsTable.budget],
                it[DemandsTable.categories].decodeList(), it[DemandsTable.publishedAt]?.toString())
        }
    }

    override suspend fun dismissDemand(key: String): Boolean = dbQuery {
        DemandsTable.update({ DemandsTable.key eq key }) { it[dismissedAt] = clock.instant() } > 0
    }

    override suspend fun purgeOldDemands(now: Instant?): Int = dbQuery {
        val limit = (now ?: clock.instant()).minus(90, ChronoUnit.DAYS)
        DemandsTable.deleteWhere { DemandsTable.createdAt.less(limit) }
    }

    override suspend fun jobBoard(accountId: AccountId): Pair<BoardRef?, Instant>? = dbQuery {
        AccountJobBoardsTable.selectAll().where { AccountJobBoardsTable.accountId eq accountId }.firstOrNull()?.let { row ->
            val provider = row[AccountJobBoardsTable.provider]?.let { runCatching { BoardProvider.valueOf(it) }.getOrNull() }
            val token = row[AccountJobBoardsTable.token]
            (if (provider != null && token != null) BoardRef(provider, token) else null) to row[AccountJobBoardsTable.checkedAt]
        }
    }

    override suspend fun saveJobBoard(accountId: AccountId, ref: BoardRef?) {
        dbQuery {
            AccountJobBoardsTable.upsert {
                it[this.accountId] = accountId; it[provider] = ref?.provider?.name; it[token] = ref?.token; it[checkedAt] = clock.instant()
            }
        }
    }
}
