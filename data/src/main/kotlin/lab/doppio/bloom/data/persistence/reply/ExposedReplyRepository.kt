package lab.doppio.bloom.data.persistence.reply

import java.time.Instant
import java.time.LocalDate
import lab.doppio.bloom.core.model.ReplyIntent
import lab.doppio.bloom.core.port.ReplyRepository
import lab.doppio.bloom.core.port.ReplyRow
import lab.doppio.bloom.data.persistence.account.AccountsTable
import lab.doppio.bloom.data.persistence.contact.ContactsTable
import lab.doppio.bloom.data.persistence.dbQuery
import lab.doppio.bloom.data.persistence.sequence.EnrollmentsTable
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update

class ExposedReplyRepository : ReplyRepository {

    override suspend fun saveReply(
        enrollmentId: Long?, from: String, subject: String?, body: String, intent: ReplyIntent,
        confidence: Double, followUpOn: LocalDate?, summary: String, receivedAt: Instant, inboundKey: String?,
    ): Long = dbQuery {
        RepliesTable.insert {
            it[this.enrollmentId] = enrollmentId; it[fromAddress] = from; it[this.subject] = subject; it[this.body] = body
            it[this.intent] = intent.name; it[this.confidence] = confidence; it[this.followUpOn] = followUpOn
            it[this.summary] = summary; it[this.receivedAt] = receivedAt; it[this.inboundKey] = inboundKey
        } get RepliesTable.id
    }

    override suspend fun replyRecorded(inboundKey: String): Boolean = dbQuery {
        RepliesTable.selectAll().where { RepliesTable.inboundKey eq inboundKey }.any()
    }

    override suspend fun openReplies(): List<ReplyRow> = dbQuery {
        // The company comes through the enrollment and the contact; a reply outside any sequence has none
        RepliesTable
            .join(EnrollmentsTable, JoinType.LEFT, RepliesTable.enrollmentId, EnrollmentsTable.id)
            .join(ContactsTable, JoinType.LEFT, EnrollmentsTable.contactId, ContactsTable.id)
            .join(AccountsTable, JoinType.LEFT, ContactsTable.accountId, AccountsTable.id)
            .select(RepliesTable.columns + AccountsTable.name)
            .where { RepliesTable.handled eq false }.orderBy(RepliesTable.receivedAt, SortOrder.DESC).map {
                ReplyRow(it[RepliesTable.id], it[RepliesTable.enrollmentId], it[RepliesTable.fromAddress], ReplyIntent.valueOf(it[RepliesTable.intent]), it[RepliesTable.summary], it[RepliesTable.followUpOn]?.toString(), it[RepliesTable.receivedAt].toString(), it[RepliesTable.handled], it.getOrNull(AccountsTable.name), it[RepliesTable.subject])
            }
    }

    override suspend fun countOpenReplies(): Long = dbQuery { RepliesTable.selectAll().where { RepliesTable.handled eq false }.count() }

    override suspend fun markReplyHandled(id: Long) {
        dbQuery { RepliesTable.update({ RepliesTable.id eq id }) { it[handled] = true } }
    }

    override suspend fun isSuppressed(email: String): Boolean = dbQuery {
        val e = email.lowercase()
        val domain = "@" + e.substringAfter('@')
        SuppressionsTable.selectAll().where { SuppressionsTable.value inList listOf(e, domain) }.any()
    }

    override suspend fun suppress(value: String, reason: String) {
        dbQuery { SuppressionsTable.insertIgnore { it[this.value] = value.lowercase(); it[this.reason] = reason } }
    }
}
