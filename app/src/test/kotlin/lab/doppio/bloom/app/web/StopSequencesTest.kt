package lab.doppio.bloom.app.web

import kotlinx.coroutines.test.runTest
import lab.doppio.bloom.app.TestDatabase
import lab.doppio.bloom.core.model.Account
import lab.doppio.bloom.core.model.Contact
import lab.doppio.bloom.core.model.EnrollmentStatus
import lab.doppio.bloom.core.model.Persona
import lab.doppio.bloom.core.sequence.DefaultSequences
import lab.doppio.bloom.data.db.Repository
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class StopSequencesTest {
    private val repo = Repository()

    @BeforeTest
    fun clean() = TestDatabase.reset()

    @Test
    fun `stopping a contact's sequences leaves the finished ones as they were`() = runTest {
        repo.ensureSequences(DefaultSequences.all())
        val accountId = repo.upsertAccount(Account(name = "Velomoov"))
        val contactId = repo.addContact(Contact(accountId = accountId, fullName = "Alex Martin", role = "CTO", persona = Persona.CTO, email = "alex@velomoov.fr", dataSource = "test"))
        val other = repo.addContact(Contact(accountId = accountId, fullName = "Sam Durand", role = "CEO", persona = Persona.CEO_FOUNDER, email = "sam@velomoov.fr", dataSource = "test"))
        val seqId = repo.sequences().first().id
        // The unique index allows one open enrollment per contact: the finished ones are set before the open one
        val completed = repo.enroll(contactId, seqId).also { repo.setEnrollmentStatus(it, EnrollmentStatus.COMPLETED) }
        val replied = repo.enroll(contactId, seqId).also { repo.setEnrollmentStatus(it, EnrollmentStatus.REPLIED) }
        val paused = repo.enroll(contactId, seqId).also { repo.setEnrollmentStatus(it, EnrollmentStatus.PAUSED) }
        val otherActive = repo.enroll(other, seqId)

        assertEquals(1, repo.stopActiveSequences(contactId, EnrollmentStatus.STOPPED_UNSUBSCRIBE))

        assertEquals(EnrollmentStatus.STOPPED_UNSUBSCRIBE, repo.enrollment(paused)!!.status)
        assertEquals(EnrollmentStatus.COMPLETED, repo.enrollment(completed)!!.status)
        assertEquals(EnrollmentStatus.REPLIED, repo.enrollment(replied)!!.status)
        assertEquals(EnrollmentStatus.ACTIVE, repo.enrollment(otherActive)!!.status, "another contact is untouched")
    }
}
