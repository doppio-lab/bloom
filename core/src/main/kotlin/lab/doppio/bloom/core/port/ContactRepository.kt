package lab.doppio.bloom.core.port

import java.time.Instant
import lab.doppio.bloom.core.model.AccountId
import lab.doppio.bloom.core.model.Contact
import lab.doppio.bloom.core.model.ContactId

interface ContactRepository {
    suspend fun addContact(c: Contact): ContactId

    suspend fun contact(id: ContactId): Contact?

    suspend fun contactsOf(accountId: AccountId): List<Contact>

    suspend fun contactsByAccount(): Map<AccountId, List<Contact>>

    suspend fun contactByEmail(email: String): Contact?

    suspend fun updateContactEmail(contactId: ContactId, email: String, source: String)

    suspend fun markInbound(contactId: ContactId)

    /** RGPD : purge des contacts sans échange depuis 3 ans (recommandation CNIL). Les oppositions restent. [now] defaults to the clock. */
    suspend fun purgeStaleContacts(now: Instant? = null): Int
}
