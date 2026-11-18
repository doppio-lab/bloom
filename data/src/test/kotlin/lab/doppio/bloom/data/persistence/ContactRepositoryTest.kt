package lab.doppio.bloom.data.persistence

import org.jetbrains.exposed.sql.selectAll
import lab.doppio.bloom.data.persistence.sequence.ExposedSequenceRepository
import lab.doppio.bloom.data.persistence.reply.RepliesTable
import lab.doppio.bloom.data.persistence.reply.ExposedReplyRepository
import lab.doppio.bloom.data.persistence.event.ExposedEventRepository
import lab.doppio.bloom.data.persistence.event.EventsTable
import lab.doppio.bloom.data.persistence.draft.ExposedDraftRepository
import lab.doppio.bloom.core.sequence.DefaultSequences
import lab.doppio.bloom.core.model.ReplyIntent
import lab.doppio.bloom.core.model.EventType
import lab.doppio.bloom.core.model.DraftStatus
import lab.doppio.bloom.core.model.Draft
import lab.doppio.bloom.core.model.Channel
import kotlinx.coroutines.test.runTest
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import lab.doppio.bloom.data.persistence.ExposedTransactions
import lab.doppio.bloom.core.model.Account
import lab.doppio.bloom.core.model.Contact
import lab.doppio.bloom.core.model.Persona
import lab.doppio.bloom.data.TestDatabase
import lab.doppio.bloom.data.persistence.account.ExposedAccountRepository
import lab.doppio.bloom.data.persistence.contact.ExposedContactRepository
import lab.doppio.bloom.data.persistence.contact.ContactsTable
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.update
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ContactRepositoryTest {
    private val accounts = ExposedAccountRepository()
    private val contacts = ExposedContactRepository()

    @BeforeTest
    fun clean() = TestDatabase.reset()

    private suspend fun contact(accountId: Long, name: String, linkedin: String? = null) =
        contacts.addContact(Contact(accountId = accountId, fullName = name, role = "CTO", persona = Persona.CTO, email = "$name@x.fr", linkedinUrl = linkedin, dataSource = "test"))

    @Test
    fun `un lien de profil n'est gardé que s'il est en https et l'email est mis en minuscules`() = runTest {
        val accountId = accounts.upsertAccount(Account(name = "Delta"))
        val http = contact(accountId, "Http", "http://linkedin.com/in/a")
        val https = contact(accountId, "Https", " https://linkedin.com/in/b ")

        assertNull(contacts.contact(http)!!.linkedinUrl)
        assertEquals("https://linkedin.com/in/b", contacts.contact(https)!!.linkedinUrl)
        assertEquals(https, contacts.contactByEmail("HTTPS@X.FR")!!.id)
    }

    @Test
    fun `la purge RGPD supprime les contacts sans échange depuis trois ans et garde les autres`() = runTest {
        val accountId = accounts.upsertAccount(Account(name = "Epsilon"))
        val old = contact(accountId, "Ancien")
        val answered = contact(accountId, "Repondu")
        val recent = contact(accountId, "Recent")
        contacts.markInbound(answered)
        val fourYearsAgo = Instant.now().minus(4 * 365L, ChronoUnit.DAYS)
        dbQuery { ContactsTable.update({ ContactsTable.id inList listOf(old, answered) }) { it[createdAt] = fourYearsAgo } }

        assertEquals(1, contacts.purgeStaleContacts())

        assertNull(contacts.contact(old))
        assertEquals(answered, contacts.contact(answered)?.id, "a recent inbound message keeps an old contact")
        assertEquals(recent, contacts.contact(recent)?.id)
    }

    @Test
    fun `la purge d'un contact emporte ses réponses, brouillons, inscriptions et événements`() = runTest {
        val sequences = ExposedSequenceRepository()
        val drafts = ExposedDraftRepository()
        val replies = ExposedReplyRepository()
        val events = ExposedEventRepository()
        sequences.ensureSequences(DefaultSequences.all())
        val accountId = accounts.upsertAccount(Account(name = "Zeta"))
        val stale = contact(accountId, "Perime")
        val kept = contact(accountId, "Garde")
        val sequenceId = sequences.all().first().id
        val staleEnrollment = sequences.enroll(stale, sequenceId)
        val keptEnrollment = sequences.enroll(kept, sequenceId)
        listOf(staleEnrollment, keptEnrollment).forEach {
            drafts.createDraft(Draft(enrollmentId = it, stepIndex = 0, channel = Channel.EMAIL, subject = "S", body = "B", citedFactIds = emptyList(),
                referenceUsed = null, rationale = "r", status = DraftStatus.SENT))
            replies.saveReply(it, "x@y.fr", "Re", "texte du prospect", ReplyIntent.NOT_NOW, 0.9, null, "plus tard", Instant.now(), "<r$it@y>")
        }
        events.logEvent(EventType.EMAIL_SENT, accountId, stale)
        events.logEvent(EventType.EMAIL_SENT, accountId, kept)
        dbQuery { ContactsTable.update({ ContactsTable.id eq stale }) { it[createdAt] = Instant.now().minus(4 * 365L, ChronoUnit.DAYS) } }

        assertEquals(1, contacts.purgeStaleContacts())

        assertEquals(listOf(keptEnrollment), dbQuery { RepliesTable.selectAll().map { it[RepliesTable.enrollmentId] } }, "the stale contact's reply is gone, not orphaned")
        assertEquals(listOf(kept), dbQuery { EventsTable.selectAll().map { it[EventsTable.contactId] } })
        assertEquals(listOf(keptEnrollment), sequences.activeEnrollments().map { it.id })
        assertEquals(1, drafts.draftsByStatus(DraftStatus.SENT).size)
    }

    @Test
    fun `un compte et ses contacts sont créés ensemble ou pas du tout`() = runTest {
        val transactions = ExposedTransactions()
        val person = Contact(accountId = 0, fullName = "Alex Martin", role = "CTO", persona = Persona.CTO, email = "alex@velomoov.fr", dataSource = "test")
        suspend fun create(vararg people: Contact) = transactions.atomically {
            val id = accounts.upsertAccount(Account(name = "Velomoov"))
            people.forEach { contacts.addContact(it.copy(accountId = id)) }
            id
        }

        assertFailsWith<Exception> { create(person, person.copy(fullName = "Alex Bis")) }
        assertTrue(accounts.accountsByScore(10).isEmpty(), "the duplicate email rolled the account back")

        val id = create(person)
        assertEquals(1, contacts.contactsOf(id).size)
    }
}
