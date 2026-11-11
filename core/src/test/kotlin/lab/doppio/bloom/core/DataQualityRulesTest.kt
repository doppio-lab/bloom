package lab.doppio.bloom.core

import lab.doppio.bloom.core.discovery.HiringRules
import lab.doppio.bloom.core.discovery.ReviewRules
import lab.doppio.bloom.core.model.Fact
import lab.doppio.bloom.core.model.FactKind
import lab.doppio.bloom.core.model.SignalType
import lab.doppio.bloom.core.model.isState
import lab.doppio.bloom.core.model.latestPerTopic
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DataQualityRulesTest {
    @Test
    fun `native mobile titles count, with or without a developer word`() {
        listOf("Développeur iOS", "Android Engineer", "Ingénieur Kotlin", "Lead mobile", "Mobile Developer", "Développeur Swift", "iOS").forEach {
            assertTrue(HiringRules.isMobile(it), it)
        }
    }

    @Test
    fun `non developer roles that only share a word do not count`() {
        listOf(
            "Chargé de clientèle mobile", "Business developer offres mobile", "Grutier grue mobile", "Chef de projet mobile",
            "Analyste SWIFT paiements", "Operations SWIFT",
        ).forEach { assertFalse(HiringRules.isMobile(it), it) }
    }

    @Test
    fun `a cross platform only opening is not a need Doppio can serve`() {
        assertFalse(HiringRules.isMobile("Développeur Flutter"))
        assertFalse(HiringRules.isMobile("React Native Engineer"))
        assertTrue(HiringRules.isMobile("Développeur iOS (migration depuis Flutter)"))
    }

    @Test
    fun `a posting is dated from its publication day, never from the future`() {
        val now = Instant.parse("2026-11-10T08:00:00Z")
        assertEquals(Instant.parse("2026-10-01T00:00:00Z"), HiringRules.observedAt(Instant.parse("2026-10-01T00:00:00Z"), now))
        assertEquals(now, HiringRules.observedAt(null, now))
        assertEquals(now, HiringRules.observedAt(Instant.parse("2026-12-01T00:00:00Z"), now))
    }

    @Test
    fun `a technical complaint is a low rating that names the problem as a whole word`() {
        assertTrue(ReviewRules.isTechnicalComplaint(1, "Plante tout le temps", ""))
        assertTrue(ReviewRules.isTechnicalComplaint(2, "Déçu", "Depuis la mise à jour, écran blanc au lancement"))
        assertTrue(ReviewRules.isTechnicalComplaint(1, "", "L'app est lente"))
        // "lent" inside "excellent" or "talent", "bug" inside "debug": not a complaint
        assertFalse(ReviewRules.isTechnicalComplaint(2, "Prix excellent mais pas pour moi", "Beaucoup de talent"))
        assertFalse(ReviewRules.isTechnicalComplaint(1, "Mode debug inutile", ""))
        // A high rating is not a complaint even when the word appears
        assertFalse(ReviewRules.isTechnicalComplaint(5, "Plus aucun bug", "crash réglé"))
    }

    private fun fact(id: String, statement: String, at: String, url: String = "https://x/offre/1") =
        Fact(id, 1, FactKind.JOB_POSTING, statement, url, at)

    @Test
    fun `the same posting read on two days keeps its latest reading only`() {
        val facts = listOf(
            fact("a", "Offre d'emploi « Dev iOS » publiée il y a 12 jours", "2026-11-01T00:00:00Z"),
            fact("b", "Offre d'emploi « Dev iOS » publiée il y a 20 jours", "2026-11-09T00:00:00Z"),
            fact("c", "Offre d'emploi « Dev Android » publiée il y a 3 jours", "2026-11-09T00:00:00Z", url = "https://x/offre/2"),
        )
        assertEquals(setOf("b", "c"), facts.latestPerTopic().map { it.id }.toSet())
        assertEquals(2, facts.latestPerTopic().size)
    }

    @Test
    fun `state signals replace earlier readings and event signals accumulate`() {
        assertTrue(SignalType.HIRING_MOBILE.isState)
        assertTrue(SignalType.QUALITY_COMPLAINTS.isState)
        assertFalse(SignalType.PUBLIC_TENDER.isState)
        assertFalse(SignalType.REFERRAL.isState)
    }
}
