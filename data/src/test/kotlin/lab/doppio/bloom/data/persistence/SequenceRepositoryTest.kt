package lab.doppio.bloom.data.persistence

import kotlinx.coroutines.test.runTest
import lab.doppio.bloom.core.model.Account
import lab.doppio.bloom.core.model.Contact
import lab.doppio.bloom.core.model.EnrollmentStatus
import lab.doppio.bloom.core.model.Persona
import lab.doppio.bloom.core.sequence.DefaultSequences
import lab.doppio.bloom.data.TestDatabase
import lab.doppio.bloom.data.persistence.account.ExposedAccountRepository
import lab.doppio.bloom.data.persistence.contact.ExposedContactRepository
import lab.doppio.bloom.data.persistence.sequence.ExposedSequenceRepository
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SequenceRepositoryTest {
    private val accounts = ExposedAccountRepository()
    private val contacts = ExposedContactRepository()
    private val sequences = ExposedSequenceRepository()

    @BeforeTest
    fun clean() = TestDatabase.reset()

    @Test
    fun `les contacts déjà inscrits le restent, même après la fin de leur séquence`() = runTest {
        sequences.ensureSequences(DefaultSequences.all())
        val accountId = accounts.upsertAccount(Account(name = "Velomoov"))
        fun person(name: String) = Contact(accountId = accountId, fullName = name, role = "CTO", persona = Persona.CTO, email = "$name@velomoov.fr", dataSource = "test")
        val running = contacts.addContact(person("alex"))
        val finished = contacts.addContact(person("sam"))
        contacts.addContact(person("lou"))
        val seq = sequences.all().first().id
        sequences.enroll(running, seq)
        sequences.setEnrollmentStatus(sequences.enroll(finished, seq), EnrollmentStatus.COMPLETED)

        assertEquals(setOf(running, finished), sequences.enrolledContactIds())
    }
}
