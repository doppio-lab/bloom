package lab.doppio.bloom.data.persistence

import kotlinx.coroutines.test.runTest
import lab.doppio.bloom.core.model.Account
import lab.doppio.bloom.core.model.Channel
import lab.doppio.bloom.core.model.Contact
import lab.doppio.bloom.core.model.Draft
import lab.doppio.bloom.core.model.DraftStatus
import lab.doppio.bloom.core.model.EnrollmentStatus
import lab.doppio.bloom.core.model.Persona
import lab.doppio.bloom.core.model.Sector
import lab.doppio.bloom.core.sequence.DefaultSequences
import lab.doppio.bloom.data.TestDatabase
import lab.doppio.bloom.data.persistence.account.ExposedAccountRepository
import lab.doppio.bloom.data.persistence.contact.ExposedContactRepository
import lab.doppio.bloom.data.persistence.draft.ExposedDraftRepository
import lab.doppio.bloom.data.persistence.sequence.ExposedSequenceRepository
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccountRepositoryTest {
    private val accounts = ExposedAccountRepository()
    private val contacts = ExposedContactRepository()
    private val sequences = ExposedSequenceRepository()
    private val drafts = ExposedDraftRepository()

    @BeforeTest
    fun clean() = TestDatabase.reset()

    @Test
    fun `réintégrer un compte bloqué par son nom le retire aussi de la liste noire`() = runTest {
        val id = accounts.upsertAccount(Account(name = "Velomoov SAS"))
        assertEquals(id, accounts.blockName("Vélomoov", "concurrent"))
        assertTrue(accounts.blockedNames().isNotEmpty())

        accounts.restoreAccount(id)

        assertTrue(accounts.blockedNames().isEmpty())
        assertTrue(accounts.blacklist().isEmpty())
    }

    @Test
    fun `la liste noire montre une seule ligne pour un nom déjà écarté et garde les noms sans compte`() = runTest {
        val id = accounts.upsertAccount(Account(name = "Velomoov SAS"))
        accounts.blockName("Vélomoov", "concurrent")
        accounts.blockName("Inconnu SARL", null)

        val lines = accounts.blacklist()

        assertEquals(2, lines.size)
        assertEquals(id, lines.first { it.accountId != null }.accountId)
        assertNull(lines.first { it.accountId == null }.reason)
    }

    @Test
    fun `la découverte ne complète que les champs vides et ne touche pas aux autres`() = runTest {
        val (id, created) = accounts.mergeDiscovered(Account(name = "Alpha", domain = "alpha.fr", sector = Sector.RETAIL, headcount = 40))
        assertTrue(created)

        val (sameId, createdAgain) = accounts.mergeDiscovered(
            Account(name = "Alpha", domain = "other.fr", siren = "123456789", sector = Sector.BANK_INSURANCE, headcount = 999, iosAppId = 42),
        )

        assertEquals(id, sameId)
        assertFalse(createdAgain)
        val a = accounts.account(id)!!
        assertEquals("alpha.fr", a.domain, "an existing domain is kept")
        assertEquals(40, a.headcount, "an existing headcount is kept")
        assertEquals(Sector.RETAIL, a.sector, "a known sector is kept")
        assertEquals("123456789", a.siren, "an empty siren is filled")
        assertEquals(42L, a.iosAppId, "an empty app id is filled")
    }

    @Test
    fun `la découverte remplace un secteur inconnu`() = runTest {
        val (id, _) = accounts.mergeDiscovered(Account(name = "Beta", sector = Sector.OTHER))
        accounts.mergeDiscovered(Account(name = "Beta", sector = Sector.HEALTH))
        assertEquals(Sector.HEALTH, accounts.account(id)!!.sector)
    }

    @Test
    fun `écarter un compte arrête ses séquences et rejette ses brouillons en attente`() = runTest {
        sequences.ensureSequences(DefaultSequences.all())
        val accountId = accounts.upsertAccount(Account(name = "Gamma"))
        val contactId = contacts.addContact(Contact(accountId = accountId, fullName = "Alex", role = "CTO", persona = Persona.CTO, email = "a@gamma.fr", dataSource = "test"))
        val enrollmentId = sequences.enroll(contactId, sequences.all().first().id)
        fun draft(step: Int, status: DraftStatus) = Draft(enrollmentId = enrollmentId, stepIndex = step, channel = Channel.EMAIL, subject = "S", body = "B",
            citedFactIds = emptyList(), referenceUsed = null, rationale = "t", status = status)
        val pending = drafts.createDraft(draft(0, DraftStatus.PENDING_REVIEW))
        val approved = drafts.createDraft(draft(1, DraftStatus.APPROVED))
        val sent = drafts.createDraft(draft(2, DraftStatus.SENT))

        accounts.excludeAccount(accountId, "client")

        assertEquals(EnrollmentStatus.STOPPED_MANUAL, sequences.enrollment(enrollmentId)!!.status)
        assertEquals(DraftStatus.REJECTED, drafts.draft(pending)!!.status)
        assertEquals(DraftStatus.REJECTED, drafts.draft(approved)!!.status)
        assertEquals(DraftStatus.SENT, drafts.draft(sent)!!.status, "what already left is history")
        assertTrue(accounts.accountsByScore().none { it.account.id == accountId }, "an excluded account leaves every list")
    }
}
