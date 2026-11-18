package lab.doppio.bloom.core.usecase

import kotlinx.coroutines.test.runTest
import lab.doppio.bloom.core.model.Account
import lab.doppio.bloom.core.model.AccountId
import lab.doppio.bloom.core.model.Contact
import lab.doppio.bloom.core.model.Persona
import lab.doppio.bloom.core.port.AccountRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CreateAccountTest {
    private val contacts = FakeContactRepository()
    private val created = mutableListOf<Account>()
    private val accounts = object : AccountRepository by FakeAccountRepository() {
        override suspend fun upsertAccount(a: Account): AccountId = (created.size + 10L).also { created += a }
    }
    private val create = CreateAccount(accounts, contacts, FakeTransactions())

    private fun contact(email: String?) = Contact(accountId = 0, fullName = "Jane", role = "CTO", persona = Persona.CTO, email = email, dataSource = "test")

    @Test
    fun `an account is created with its contacts, their addresses normalized`() = runTest {
        val id = create(Account(name = "Acme"), listOf(contact(" Jane@Acme.TEST "), contact(null)))
        assertEquals(10L, id)
        assertEquals(listOf("jane@acme.test", null), contacts.contacts.values.map { it.email })
        assertEquals(setOf(10L), contacts.contacts.values.map { it.accountId }.toSet())
    }

    @Test
    fun `a bad address is refused before anything is written`() = runTest {
        assertFailsWith<IllegalArgumentException> { create(Account(name = "Acme"), listOf(contact("jane@acme.test"), contact("a@x.fr, b@y.fr"))) }
        assertTrue(created.isEmpty(), "no account without its contacts")
        assertTrue(contacts.contacts.isEmpty())
    }
}
