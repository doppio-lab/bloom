package lab.doppio.bloom.data.persistence.officer

import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import lab.doppio.bloom.core.model.AccountId
import lab.doppio.bloom.core.port.OfficerRepository
import lab.doppio.bloom.core.port.OfficerRow
import lab.doppio.bloom.data.persistence.account.AccountsTable
import lab.doppio.bloom.data.persistence.dbQuery
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNotNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update

class ExposedOfficerRepository(private val clock: Clock = Clock.systemDefaultZone()) : OfficerRepository {

    override suspend fun saveOfficers(accountId: AccountId, officers: List<OfficerRow>) {
        dbQuery {
            officers.forEach { o ->
                OfficersTable.insertIgnore { it[this.accountId] = accountId; it[fullName] = o.fullName; it[role] = o.role; it[sourceUrl] = o.sourceUrl }
            }
            AccountsTable.update({ AccountsTable.id eq accountId }) { it[officersCheckedAt] = clock.instant() }
        }
    }

    override suspend fun officersByAccount(): Map<AccountId, List<OfficerRow>> = dbQuery {
        OfficersTable.selectAll().groupBy({ it[OfficersTable.accountId] }) { OfficerRow(it[OfficersTable.fullName], it[OfficersTable.role], it[OfficersTable.sourceUrl]) }
    }

    override suspend fun accountsWithoutOfficers(limit: Int): List<Pair<AccountId, String>> = dbQuery {
        AccountsTable.selectAll()
            .where { AccountsTable.siren.isNotNull() and AccountsTable.officersCheckedAt.isNull() and AccountsTable.excludedReason.isNull() }
            .orderBy(AccountsTable.score, SortOrder.DESC).limit(limit).map { it[AccountsTable.id] to it[AccountsTable.siren]!! }
    }

    override suspend fun purgeStaleOfficers(now: Instant?): Int = dbQuery {
        val limit = (now ?: clock.instant()).minus(3 * 365L, ChronoUnit.DAYS)
        OfficersTable.deleteWhere { OfficersTable.fetchedAt.less(limit) }
    }
}
