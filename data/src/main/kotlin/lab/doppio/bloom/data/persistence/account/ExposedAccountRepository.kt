package lab.doppio.bloom.data.persistence.account

import java.time.Clock
import lab.doppio.bloom.core.discovery.CompanyNames
import lab.doppio.bloom.core.model.Account
import lab.doppio.bloom.core.model.AccountId
import lab.doppio.bloom.core.model.DraftStatus
import lab.doppio.bloom.core.model.EnrollmentStatus
import lab.doppio.bloom.core.model.Market
import lab.doppio.bloom.core.model.Offer
import lab.doppio.bloom.core.model.Sector
import lab.doppio.bloom.core.port.AccountRepository
import lab.doppio.bloom.core.port.AccountRow
import lab.doppio.bloom.core.port.Blocked
import lab.doppio.bloom.core.scoring.ScoreBreakdown
import lab.doppio.bloom.data.persistence.contact.ContactsTable
import lab.doppio.bloom.data.persistence.dbQuery
import lab.doppio.bloom.data.persistence.draft.DraftsTable
import lab.doppio.bloom.data.persistence.json
import lab.doppio.bloom.data.persistence.sequence.EnrollmentsTable
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNotNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.statements.UpdateBuilder
import org.jetbrains.exposed.sql.update

class ExposedAccountRepository(private val clock: Clock = Clock.systemDefaultZone()) : AccountRepository {

    private fun findAccountIdTx(a: Account): AccountId? {
        val slug = CompanyNames.normalize(a.name)
        return AccountsTable.selectAll().where { AccountsTable.slug eq slug }.firstOrNull()?.get(AccountsTable.id)
            ?: a.domain?.let { d -> AccountsTable.selectAll().where { AccountsTable.domain eq d }.firstOrNull()?.get(AccountsTable.id) }
    }

    override suspend fun upsertAccount(a: Account): AccountId = dbQuery {
        val existing = findAccountIdTx(a)
        if (existing != null) {
            AccountsTable.update({ AccountsTable.id eq existing }) { it.fill(a) }
            existing
        } else {
            AccountsTable.insert { it.fill(a); it[origin] = a.source } get AccountsTable.id
        }
    }

    override suspend fun mergeDiscovered(a: Account): Pair<AccountId, Boolean> = dbQuery {
        val existing = findAccountIdTx(a)
        if (existing == null) {
            (AccountsTable.insert { it.fill(a); it[origin] = a.source } get AccountsTable.id) to true
        } else {
            val row = AccountsTable.selectAll().where { AccountsTable.id eq existing }.first()
            val fillDomain = row[AccountsTable.domain] == null && a.domain != null
            val fillSiren = row[AccountsTable.siren] == null && a.siren != null
            val fillHeadcount = row[AccountsTable.headcount] == null && a.headcount != null
            val fillApp = row[AccountsTable.iosAppId] == null && a.iosAppId != null
            val fillSector = row[AccountsTable.sector] == "OTHER" && a.sector.name != "OTHER"
            // Exposed refuses an UPDATE that sets nothing: an account that is already complete is left alone
            if (fillDomain || fillSiren || fillHeadcount || fillApp || fillSector) {
                AccountsTable.update({ AccountsTable.id eq existing }) {
                    if (fillDomain) it[domain] = a.domain
                    if (fillSiren) it[siren] = a.siren
                    if (fillHeadcount) it[headcount] = a.headcount
                    if (fillApp) it[iosAppId] = a.iosAppId
                    if (fillSector) it[sector] = a.sector.name
                }
            }
            existing to false
        }
    }

    override suspend fun excludeAccount(id: AccountId, reason: String) = dbQuery {
        AccountsTable.update({ AccountsTable.id eq id }) { it[excludedReason] = reason; it[excludedAt] = clock.instant() }
        val contactIds = ContactsTable.selectAll().where { ContactsTable.accountId eq id }.map { it[ContactsTable.id] }
        if (contactIds.isNotEmpty()) {
            val open = EnrollmentsTable.selectAll()
                .where { (EnrollmentsTable.contactId inList contactIds) and (EnrollmentsTable.status inList listOf(EnrollmentStatus.ACTIVE.name, EnrollmentStatus.PAUSED.name)) }
                .map { it[EnrollmentsTable.id] }
            if (open.isNotEmpty()) {
                EnrollmentsTable.update({ EnrollmentsTable.id inList open }) { it[status] = EnrollmentStatus.STOPPED_MANUAL.name }
                DraftsTable.update({ (DraftsTable.enrollmentId inList open) and (DraftsTable.status inList listOf(DraftStatus.PENDING_REVIEW.name, DraftStatus.APPROVED.name)) }) {
                    it[status] = DraftStatus.REJECTED.name
                }
            }
        }
    }

    override suspend fun restoreAccount(id: AccountId) = dbQuery {
        AccountsTable.update({ AccountsTable.id eq id }) { it[excludedReason] = null; it[excludedAt] = null }
        val slug = AccountsTable.selectAll().where { AccountsTable.id eq id }.firstOrNull()?.get(AccountsTable.slug)
        if (slug != null) BlocklistTable.deleteWhere { BlocklistTable.slug eq slug }
    }

    override suspend fun blockName(name: String, reason: String?): AccountId? {
        val slug = CompanyNames.normalize(name)
        require(slug.isNotEmpty()) { "Nom vide" }
        dbQuery { BlocklistTable.insertIgnore { it[this.name] = name.trim(); it[this.slug] = slug; it[this.reason] = reason } }
        val existing = dbQuery { AccountsTable.selectAll().where { (AccountsTable.slug eq slug) and AccountsTable.excludedReason.isNull() }.firstOrNull()?.get(AccountsTable.id) }
        if (existing != null) excludeAccount(existing, reason ?: "liste noire")
        return existing
    }

    override suspend fun unblockName(id: Long) {
        dbQuery { BlocklistTable.deleteWhere { BlocklistTable.id eq id } }
    }

    override suspend fun blockedNames(): Set<String> = dbQuery { BlocklistTable.selectAll().map { it[BlocklistTable.name] }.toSet() }

    override suspend fun blacklist(): List<Blocked> = dbQuery {
        val excluded = AccountsTable.selectAll().where { AccountsTable.excludedReason.isNotNull() }
            .orderBy(AccountsTable.excludedAt, SortOrder.DESC_NULLS_LAST).toList()
        val accounts = excluded.map {
            Blocked(it[AccountsTable.id], it[AccountsTable.id], it[AccountsTable.name], it[AccountsTable.excludedReason], it[AccountsTable.excludedAt]?.toString())
        }
        val excludedSlugs = excluded.map { it[AccountsTable.slug] }.toSet()
        // A blocked name that already shows as an excluded account is listed once, as the account
        val names = BlocklistTable.selectAll().orderBy(BlocklistTable.createdAt, SortOrder.DESC).filter { it[BlocklistTable.slug] !in excludedSlugs }.map {
            Blocked(it[BlocklistTable.id], null, it[BlocklistTable.name], it[BlocklistTable.reason], it[BlocklistTable.createdAt].toString())
        }
        accounts + names
    }

    override suspend fun accountsToComplete(minScore: Int, limit: Int): List<AccountRow> {
        val withContact = dbQuery { ContactsTable.selectAll().map { it[ContactsTable.accountId] }.toSet() }
        return accountsByScore(500).filter { it.score >= minScore && it.account.id !in withContact }.take(limit)
    }

    private fun UpdateBuilder<*>.fill(a: Account) {
        this[AccountsTable.name] = a.name
        this[AccountsTable.slug] = CompanyNames.normalize(a.name)
        this[AccountsTable.domain] = a.domain
        this[AccountsTable.siren] = a.siren
        this[AccountsTable.sector] = a.sector.name
        this[AccountsTable.market] = a.market.name
        this[AccountsTable.headcount] = a.headcount
        this[AccountsTable.iosAppId] = a.iosAppId
        this[AccountsTable.androidPackage] = a.androidPackage
        this[AccountsTable.warmPath] = a.warmPath
        this[AccountsTable.ownerNote] = a.ownerNote
    }

    private fun ResultRow.toAccount() = Account(
        id = this[AccountsTable.id], name = this[AccountsTable.name], domain = this[AccountsTable.domain], siren = this[AccountsTable.siren],
        sector = Sector.valueOf(this[AccountsTable.sector]), market = Market.valueOf(this[AccountsTable.market]),
        headcount = this[AccountsTable.headcount], iosAppId = this[AccountsTable.iosAppId], androidPackage = this[AccountsTable.androidPackage],
        warmPath = this[AccountsTable.warmPath], ownerNote = this[AccountsTable.ownerNote], source = this[AccountsTable.origin],
    )

    override suspend fun account(id: AccountId): Account? = dbQuery {
        AccountsTable.selectAll().where { AccountsTable.id eq id }.firstOrNull()?.toAccount()
    }

    override suspend fun accountsByScore(limit: Int): List<AccountRow> = dbQuery {
        AccountsTable.selectAll().where { AccountsTable.excludedReason.isNull() }.orderBy(AccountsTable.score, SortOrder.DESC).limit(limit).map {
            val detail = runCatching { json.decodeFromString(ScoreBreakdown.serializer(), it[AccountsTable.scoreDetail]) }.getOrNull()
            AccountRow(
                it.toAccount(), it[AccountsTable.score], detail?.fit ?: 0, detail?.intent ?: 0, detail?.warm ?: 0,
                it[AccountsTable.recommendedOffer]?.let(Offer::valueOf),
                detail?.reasons.orEmpty(), it[AccountsTable.refreshedAt]?.toString(),
            )
        }
    }

    override suspend fun saveScore(s: ScoreBreakdown) {
        dbQuery {
            AccountsTable.update({ AccountsTable.id eq s.accountId }) {
                it[score] = s.total
                it[scoreDetail] = json.encodeToString(ScoreBreakdown.serializer(), s)
                it[recommendedOffer] = s.recommendedOffer?.name
                it[refreshedAt] = clock.instant()
            }
        }
    }
}
