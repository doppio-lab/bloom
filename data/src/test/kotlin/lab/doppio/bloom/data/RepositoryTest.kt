package lab.doppio.bloom.data

import kotlinx.coroutines.test.runTest
import lab.doppio.bloom.core.model.Account
import lab.doppio.bloom.core.model.ReplyIntent
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
}
