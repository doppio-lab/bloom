package lab.doppio.bloom.app.web

import kotlinx.coroutines.test.runTest
import lab.doppio.bloom.app.TestDatabase
import lab.doppio.bloom.core.model.Account
import lab.doppio.bloom.core.model.Contact
import lab.doppio.bloom.core.model.Persona
import lab.doppio.bloom.data.db.Repository
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AccountCreationTest {
    private val repo = Repository()

    @BeforeTest
    fun clean() = TestDatabase.reset()

    @Test
    fun `an account and its contacts are created together or not at all`() = runTest {
        val contact = Contact(accountId = 0, fullName = "Alex Martin", role = "CTO", persona = Persona.CTO, email = "alex@velomoov.fr", dataSource = "test")
        assertFailsWith<Exception> { repo.createAccountWithContacts(Account(name = "Velomoov"), listOf(contact, contact.copy(fullName = "Alex Bis"))) }
        assertTrue(repo.accountsByScore(10).isEmpty(), "no account without its contacts")

        val id = repo.createAccountWithContacts(Account(name = "Velomoov"), listOf(contact))
        assertEquals(1, repo.contactsOf(id).size)
    }
}
