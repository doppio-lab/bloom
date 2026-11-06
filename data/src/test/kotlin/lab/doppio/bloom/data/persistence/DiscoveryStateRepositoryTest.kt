package lab.doppio.bloom.data.persistence

import kotlinx.coroutines.test.runTest
import lab.doppio.bloom.core.model.Demand
import lab.doppio.bloom.data.TestDatabase
import lab.doppio.bloom.data.persistence.discovery.DemandsTable
import lab.doppio.bloom.data.persistence.discovery.ExposedDiscoveryStateRepository
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.update
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiscoveryStateRepositoryTest {
    private val discovery = ExposedDiscoveryStateRepository()

    @BeforeTest
    fun clean() = TestDatabase.reset()

    @Test
    fun `écarter une demande inconnue est signalé`() = runTest {
        discovery.saveDemand(Demand("codeur:1", "codeur", "Appli iOS", "https://www.codeur.com/projects/1"))
        assertTrue(discovery.dismissDemand("codeur:1"))
        assertFalse(discovery.dismissDemand("codeur:404"))
        assertTrue(discovery.openDemands().isEmpty())
    }

    @Test
    fun `les demandes de plus de 90 jours sont purgées`() = runTest {
        discovery.saveDemand(Demand("codeur:old", "codeur", "Ancienne", "https://www.codeur.com/projects/2"))
        discovery.saveDemand(Demand("codeur:new", "codeur", "Récente", "https://www.codeur.com/projects/3"))
        dbQuery { DemandsTable.update({ DemandsTable.key eq "codeur:old" }) { it[createdAt] = Instant.now().minus(91, ChronoUnit.DAYS) } }

        assertEquals(1, discovery.purgeOldDemands())
        assertEquals(listOf("codeur:new"), discovery.openDemands().map { it.key })
    }

    @Test
    fun `une clé examinée est mémorisée pour la durée demandée`() = runTest {
        assertFalse(discovery.wasSeen("ft:1"))
        discovery.markSeen("ft:1", kept = false, reason = "ESN")
        assertTrue(discovery.wasSeen("ft:1"))
    }
}
