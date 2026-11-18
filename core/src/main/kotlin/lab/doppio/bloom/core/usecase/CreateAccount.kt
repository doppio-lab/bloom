package lab.doppio.bloom.core.usecase

import lab.doppio.bloom.core.model.Account
import lab.doppio.bloom.core.model.AccountId
import lab.doppio.bloom.core.model.Contact
import lab.doppio.bloom.core.model.EmailAddress
import lab.doppio.bloom.core.port.AccountRepository
import lab.doppio.bloom.core.port.ContactRepository
import lab.doppio.bloom.core.port.Transactions

/**
 * Creates an account with its contacts, as one unit: when a contact is refused (invalid or duplicate email) no
 * account is left behind without it. Every address is checked before anything is written.
 */
class CreateAccount(
    private val accountRepo: AccountRepository,
    private val contactRepo: ContactRepository,
    private val transactions: Transactions,
) {
    suspend operator fun invoke(account: Account, contacts: List<Contact>): AccountId {
        val checked = contacts.map { it.copy(email = EmailAddress.orNull(it.email)) }
        return transactions.atomically {
            val id = accountRepo.upsertAccount(account)
            checked.forEach { contactRepo.addContact(it.copy(accountId = id)) }
            id
        }
    }
}
