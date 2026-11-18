package lab.doppio.bloom.data.persistence.contact

import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import lab.doppio.bloom.core.model.AccountId
import lab.doppio.bloom.core.model.Contact
import lab.doppio.bloom.core.model.ContactId
import lab.doppio.bloom.core.model.Persona
import lab.doppio.bloom.core.model.Retention
import lab.doppio.bloom.core.port.ContactRepository
import lab.doppio.bloom.data.persistence.dbQuery
import lab.doppio.bloom.data.persistence.event.EventsTable
import lab.doppio.bloom.data.persistence.reply.RepliesTable
import lab.doppio.bloom.data.persistence.sequence.EnrollmentsTable
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update

class ExposedContactRepository(private val clock: Clock = Clock.systemDefaultZone()) : ContactRepository {

    override suspend fun addContact(c: Contact): ContactId = dbQuery {
        ContactsTable.insert {
            it[accountId] = c.accountId; it[fullName] = c.fullName; it[role] = c.role; it[persona] = c.persona.name
            // A profile link is kept only when it is https, whatever route the contact comes from
            it[email] = c.email?.lowercase(); it[linkedinUrl] = c.linkedinUrl?.trim()?.takeIf { u -> u.startsWith("https://") }
            it[dataSource] = c.dataSource; it[language] = c.language
        } get ContactsTable.id
    }

    private fun ResultRow.toContact() = Contact(
        id = this[ContactsTable.id], accountId = this[ContactsTable.accountId], fullName = this[ContactsTable.fullName], role = this[ContactsTable.role],
        persona = Persona.valueOf(this[ContactsTable.persona]), email = this[ContactsTable.email], linkedinUrl = this[ContactsTable.linkedinUrl],
        dataSource = this[ContactsTable.dataSource], language = this[ContactsTable.language],
    )

    override suspend fun contact(id: ContactId): Contact? = dbQuery { ContactsTable.selectAll().where { ContactsTable.id eq id }.firstOrNull()?.toContact() }

    override suspend fun contactsOf(accountId: AccountId): List<Contact> = dbQuery { ContactsTable.selectAll().where { ContactsTable.accountId eq accountId }.map { it.toContact() } }

    override suspend fun contactsByAccount(): Map<AccountId, List<Contact>> = dbQuery {
        ContactsTable.selectAll().orderBy(ContactsTable.createdAt, SortOrder.ASC).map { it.toContact() }.groupBy { it.accountId }
    }

    override suspend fun contactByEmail(email: String): Contact? = dbQuery { ContactsTable.selectAll().where { ContactsTable.email eq email.lowercase() }.firstOrNull()?.toContact() }

    override suspend fun updateContactEmail(contactId: ContactId, email: String, source: String) {
        dbQuery { ContactsTable.update({ ContactsTable.id eq contactId }) { it[this.email] = email.lowercase(); it[dataSource] = source } }
    }

    override suspend fun markInbound(contactId: ContactId) {
        dbQuery { ContactsTable.update({ ContactsTable.id eq contactId }) { it[lastInboundAt] = clock.instant() } }
    }

    override suspend fun purgeStaleContacts(now: Instant?): Int = dbQuery {
        val limit = (now ?: clock.instant()).minus(Retention.CONTACT_DAYS, ChronoUnit.DAYS)
        val contactIds = ContactsTable.selectAll()
            .where { (ContactsTable.createdAt.less(limit)) and (ContactsTable.lastInboundAt.isNull() or ContactsTable.lastInboundAt.less(limit)) }
            .map { it[ContactsTable.id] }
        if (contactIds.isEmpty()) return@dbQuery 0
        // Enrollments and drafts follow by cascade, but a reply keeps its row (ON DELETE SET NULL) and events have no
        // foreign key: delete them here, in the same transaction, or a purged contact's emails and history would stay.
        val enrollmentIds = EnrollmentsTable.selectAll().where { EnrollmentsTable.contactId inList contactIds }.map { it[EnrollmentsTable.id] }
        if (enrollmentIds.isNotEmpty()) RepliesTable.deleteWhere { RepliesTable.enrollmentId inList enrollmentIds }
        EventsTable.deleteWhere { EventsTable.contactId inList contactIds }
        ContactsTable.deleteWhere { ContactsTable.id inList contactIds }
    }
}
