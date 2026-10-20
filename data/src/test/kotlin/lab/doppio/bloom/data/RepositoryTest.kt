package lab.doppio.bloom.data

import kotlinx.coroutines.test.runTest
import lab.doppio.bloom.core.model.Account
import lab.doppio.bloom.core.model.Channel
import lab.doppio.bloom.core.model.Contact
import lab.doppio.bloom.core.model.Draft
import lab.doppio.bloom.core.model.DraftStatus
import lab.doppio.bloom.core.model.EnrollmentStatus
import lab.doppio.bloom.core.model.Persona
import lab.doppio.bloom.core.model.ReplyIntent
import lab.doppio.bloom.core.model.Sector
import lab.doppio.bloom.core.sequence.DefaultSequences
import lab.doppio.bloom.data.db.Demand
import lab.doppio.bloom.data.db.Demands
import lab.doppio.bloom.data.db.Repository
import lab.doppio.bloom.data.db.dbQuery
import org.jetbrains.exposed.sql.update
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RepositoryTest {
    private val repo = Repository()

    @BeforeTest
    fun clean() = TestDatabase.reset()

    @Test
    fun `réintégrer un compte bloqué par son nom le retire aussi de la liste noire`() = runTest {
        val id = repo.upsertAccount(Account(name = "Velomoov SAS"))
        assertEquals(id, repo.blockName("Vélomoov", "concurrent"))
        assertTrue(repo.blockedNames().isNotEmpty())

        repo.restoreAccount(id)

        assertTrue(repo.blockedNames().isEmpty())
        assertTrue(repo.blacklist().isEmpty())
    }

    @Test
    fun `écarter une demande inconnue est signalé`() = runTest {
        repo.saveDemand(Demand("codeur:1", "codeur", "Appli iOS", "https://www.codeur.com/projects/1"))
        assertTrue(repo.dismissDemand("codeur:1"))
        assertFalse(repo.dismissDemand("codeur:404"))
        assertTrue(repo.openDemands().isEmpty())
    }

    @Test
    fun `les demandes de plus de 90 jours sont purgées`() = runTest {
        repo.saveDemand(Demand("codeur:old", "codeur", "Ancienne", "https://www.codeur.com/projects/2"))
        repo.saveDemand(Demand("codeur:new", "codeur", "Récente", "https://www.codeur.com/projects/3"))
        dbQuery { Demands.update({ Demands.key eq "codeur:old" }) { it[createdAt] = Instant.now().minus(91, ChronoUnit.DAYS) } }

        assertEquals(1, repo.purgeOldDemands())
        assertEquals(listOf("codeur:new"), repo.openDemands().map { it.key })
    }

    @Test
    fun `un email entrant n'est enregistré qu'une fois`() = runTest {
        assertFalse(repo.replyRecorded("<r1@client.fr>"))
        repo.saveReply(null, "alex@client.fr", "Re: Objet", "Merci", ReplyIntent.NOT_NOW, 0.9, null, "Plus tard", Instant.now(), "<r1@client.fr>")
        assertTrue(repo.replyRecorded("<r1@client.fr>"))
        kotlin.test.assertFails {
            repo.saveReply(null, "alex@client.fr", "Re: Objet", "Merci", ReplyIntent.NOT_NOW, 0.9, null, "Plus tard", Instant.now(), "<r1@client.fr>")
        }
    }

    @Test
    fun `les tokens du jour additionnent entrée et sortie de tous les agents`() = runTest {
        val start = Instant.now().minusSeconds(5)
        assertEquals(0, repo.tokensSince(start))
        repo.logAgentRun("writer", null, "m", 1000, 200, 10, true, null)
        repo.logAgentRun("research-explore", null, "m", 3000, 500, 10, false, "boom")
        assertEquals(4700, repo.tokensSince(start))
    }

    @Test
    fun `la liste noire montre les comptes exclus puis les noms bloqués sans compte`() = runTest {
        val velomoov = repo.upsertAccount(Account(name = "Velomoov"))
        repo.excludeAccount(velomoov, "client actuel")
        repo.blockName("Vélomoov SAS", "doublon du compte")
        repo.blockName("Uber", "hors cible")

        val list = repo.blacklist()

        assertEquals(listOf("Velomoov", "Uber"), list.map { it.name })
        assertEquals(velomoov, list[0].accountId)
        assertEquals("client actuel", list[0].reason)
        assertNull(list[1].accountId)
        assertEquals("hors cible", list[1].reason)
        assertTrue(list.all { it.since != null })
    }

    @Test
    fun `la découverte ne complète que les champs vides d'un compte connu`() = runTest {
        val id = repo.upsertAccount(Account(name = "Velomoov SAS", domain = "velomoov.fr", sector = Sector.OTHER))
        val (merged, isNew) = repo.mergeDiscovered(
            Account(name = "Vélomoov", domain = "velomoov.io", siren = "123456789", sector = Sector.MOBILITY_TRANSPORT, headcount = 20, iosAppId = 42, source = "offres d'emploi"),
        )

        assertEquals(id, merged, "matched on the normalised name")
        assertFalse(isNew)
        val a = repo.account(id)!!
        assertEquals("Velomoov SAS", a.name, "the name is not rewritten")
        assertEquals("velomoov.fr", a.domain, "a known domain is not rewritten")
        assertEquals("123456789", a.siren)
        assertEquals(Sector.MOBILITY_TRANSPORT, a.sector, "OTHER counts as empty")
        assertEquals(20, a.headcount)
        assertEquals(42L, a.iosAppId)
        assertEquals("manual", a.source, "the origin stays the first one")

        val (_, created) = repo.mergeDiscovered(Account(name = "Nouvelle Appli", source = "presse"))
        assertTrue(created)
        assertEquals(2, repo.accountsByScore().size)
    }

    @Test
    fun `exclure un compte arrête ses séquences et rejette ses brouillons en attente`() = runTest {
        repo.ensureSequences(DefaultSequences.all())
        val id = repo.upsertAccount(Account(name = "Velomoov"))
        val contactId = repo.addContact(Contact(accountId = id, fullName = "Alex Martin", role = "CTO", persona = Persona.CTO, email = "alex@velomoov.fr", dataSource = "test"))
        val enrollmentId = repo.enroll(contactId, repo.sequences().first().id)
        fun draft(step: Int, status: DraftStatus) = Draft(enrollmentId = enrollmentId, stepIndex = step, channel = Channel.EMAIL, subject = "Objet", body = "Corps",
            citedFactIds = emptyList(), referenceUsed = null, rationale = "test", status = status)
        val sent = repo.createDraft(draft(0, DraftStatus.SENT))
        val pending = repo.createDraft(draft(1, DraftStatus.PENDING_REVIEW))

        repo.excludeAccount(id, "concurrent")

        assertEquals(EnrollmentStatus.STOPPED_MANUAL, repo.enrollment(enrollmentId)!!.status)
        assertEquals(DraftStatus.REJECTED, repo.draft(pending)!!.status)
        assertEquals(DraftStatus.SENT, repo.draft(sent)!!.status, "history is kept")
        assertTrue(repo.accountsByScore().isEmpty(), "hidden from the lists")
        assertEquals(listOf(id), repo.blacklist().map { it.accountId })
    }
}
