package lab.doppio.bloom.data.discovery

import kotlin.test.Test
import kotlin.test.assertEquals

/** Pins the run notes and the reasons a candidate is dropped, as stored and shown. */
class DiscoveryNotesPinTest {
    @Test
    fun `labels`() {
        assertEquals(listOf("offres d'emploi", "classement App Store", "Codeur"), listOf(DiscoveryNotes.JOBS_LABEL, DiscoveryNotes.CHARTS_LABEL, DiscoveryNotes.DEMANDS_LABEL))
    }

    @Test
    fun `run notes`() {
        assertEquals("offres d'emploi désactivées : FT_CLIENT_ID absent", DiscoveryNotes.JOBS_DISABLED)
        assertEquals("offres d'emploi : HTTP 500", DiscoveryNotes.jobsFailed("HTTP 500"))
        assertEquals("App Store : HTTP 500", DiscoveryNotes.chartsFailed("HTTP 500"))
        assertEquals("BODACC : HTTP 500", DiscoveryNotes.sourceFailed("BODACC", "HTTP 500"))
        assertEquals("Codeur : HTTP 500", DiscoveryNotes.demandsFailed("HTTP 500"))
        assertEquals("pages carrières : HTTP 500", DiscoveryNotes.jobBoardsFailed("HTTP 500"))
        assertEquals("pages carrières : 4 lues, 1 avec une offre mobile", DiscoveryNotes.jobBoardsRead(4, 1))
    }

    @Test
    fun `reasons a candidate is dropped`() {
        assertEquals("éditeur non trouvé dans l'annuaire français", DiscoveryNotes.PUBLISHER_NOT_IN_REGISTRY)
        assertEquals("aucun signal de difficulté", DiscoveryNotes.NO_DIFFICULTY_SIGNAL)
        assertEquals("non trouvé dans l'annuaire français", DiscoveryNotes.NOT_IN_REGISTRY)
        assertEquals("plusieurs entreprises portent ce nom", DiscoveryNotes.AMBIGUOUS_NAME)
        assertEquals("ni secteur couvert ni app sur l'App Store", DiscoveryNotes.NO_SECTOR_NOR_APP)
        assertEquals("acheteur absent de l'annuaire français", DiscoveryNotes.BUYER_NOT_IN_REGISTRY)
        assertEquals("organisation hors France ou sans site", DiscoveryNotes.ORGANISATION_OUT_OF_SCOPE)
        assertEquals("effectif inférieur à 1000", DiscoveryNotes.belowMinHeadcount(1000))
        assertEquals("signaux : RATING_DROP, QUALITY_COMPLAINTS", DiscoveryNotes.signalsFound("RATING_DROP, QUALITY_COMPLAINTS"))
    }
}
