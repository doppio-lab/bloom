package lab.doppio.bloom.core.usecase

import kotlinx.coroutines.test.runTest
import lab.doppio.bloom.core.model.Account
import lab.doppio.bloom.core.model.Offer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AddContactTest {
    private val contacts = FakeContactRepository()
    private val replies = FakeReplyRepository()
    private val sequences = FakeSequenceRepository().apply { sequences += emailSequence(Offer.DISCOVERY, 1) }
    private val enroll = EnrollContact(sequences, replies, FakeFactRepository(), FakeAccountRepository(), FakeEventRepository())
    private val add = AddContact(contacts, replies, enroll)
    private val account = Account(10, "Acme")

    @Test
    fun `a contact with an email is saved and enrolled right away`() = runTest {
        val added = add(account, AddContact.Request(" Jane Doe ", "CTO", email = " jane@acme.test "))
        assertEquals("Jane Doe", added.contact.fullName)
        assertEquals("jane@acme.test", added.contact.email)
        assertTrue(added.enrolled)
        assertEquals(1, sequences.enrollmentsOfContact(added.contact.id).size)
    }

    @Test
    fun `without an email the contact is saved but not enrolled`() = runTest {
        val added = add(account, AddContact.Request("Jane Doe", "CTO"))
        assertNull(added.contact.email)
        assertFalse(added.enrolled)
        assertTrue(sequences.enrollments.isEmpty())
    }

    @Test
    fun `enroll=false saves the contact only`() = runTest {
        val added = add(account, AddContact.Request("Jane Doe", "CTO", email = "jane@acme.test", enroll = false))
        assertFalse(added.enrolled)
        assertTrue(sequences.enrollments.isEmpty())
    }

    @Test
    fun `an address that opted out is refused and nothing is saved`() = runTest {
        replies.suppressed["jane@acme.test"] = "opposition"
        assertFailsWith<IllegalArgumentException> { add(account, AddContact.Request("Jane Doe", "CTO", email = "jane@acme.test")) }
        assertTrue(contacts.contacts.isEmpty())
    }

    @Test
    fun `name and role are required, a LinkedIn url that is not https is dropped, a blank email is no email`() = runTest {
        assertFailsWith<IllegalArgumentException> { add(account, AddContact.Request("", "CTO")) }
        assertFailsWith<IllegalArgumentException> { add(account, AddContact.Request("Jane", " ")) }
        val added = add(account, AddContact.Request("Jane", "CTO", email = "  ", linkedinUrl = "http://linkedin.com/in/x"))
        assertNull(added.contact.email)
        assertNull(added.contact.linkedinUrl)
    }

    @Test
    fun `an email that is not one plain address is refused and nothing is saved`() = runTest {
        // A list would send to two people, and the second one would escape the suppression list
        for (bad in listOf("not-an-email", "jane@acme.test, other@acme.test", "Jane <jane@acme.test>", "jane@acme.test\r\nBcc: x@y.fr")) {
            assertFailsWith<IllegalArgumentException>(bad) { add(account, AddContact.Request("Jane", "CTO", email = bad)) }
        }
        assertTrue(contacts.contacts.isEmpty())
    }

    @Test
    fun `the opt-out check sees the normalized address`() = runTest {
        replies.suppressed["jane@acme.test"] = "opposition"
        assertFailsWith<IllegalArgumentException> { add(account, AddContact.Request("Jane Doe", "CTO", email = " Jane@Acme.TEST ")) }
    }
}
