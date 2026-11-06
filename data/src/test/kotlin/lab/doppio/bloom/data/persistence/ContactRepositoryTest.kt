package lab.doppio.bloom.data.persistence

import kotlinx.coroutines.test.runTest
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
}
