package lab.doppio.bloom.agents

import lab.doppio.bloom.core.model.Offer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DossierSynthesisTest {
    @Test
    fun `fact identifiers are accepted with or without the citation marker`() {
        assertEquals("abc", DossierSynthesis.normalizeFactId("abc"))
        assertEquals("abc", DossierSynthesis.normalizeFactId("F:abc"))
        assertEquals("abc", DossierSynthesis.normalizeFactId("[F:abc]"))
        assertEquals("abc", DossierSynthesis.normalizeFactId(" [F:abc] "))
        assertEquals("a1-b2_c3", DossierSynthesis.normalizeFactId("[F:a1-b2_c3]"))
    }

    @Test
    fun `offers are matched on their name or label, whatever the case`() {
        assertEquals(Offer.DISCOVERY, DossierSynthesis.parseOffer("DISCOVERY"))
        assertEquals(Offer.DISCOVERY, DossierSynthesis.parseOffer("discovery"))
        assertEquals(Offer.DISCOVERY, DossierSynthesis.parseOffer("Audit Discovery 2 jours"))
        assertEquals(Offer.TRAINING_KMP, DossierSynthesis.parseOffer("formation kotlin multiplatform 3 jours"))
    }

    @Test
    fun `a partial wording still finds the offer when it is unambiguous`() {
        assertEquals(Offer.DISCOVERY, DossierSynthesis.parseOffer("AUDIT"))
        assertEquals(Offer.DISCOVERY, DossierSynthesis.parseOffer("Discovery 2 jours"))
        assertEquals(Offer.REGIE, DossierSynthesis.parseOffer("régie"))
        assertEquals(Offer.TRAINING_ANTHROPIC, DossierSynthesis.parseOffer("formation Anthropic"))
    }

    @Test
    fun `NONE, an empty answer and an ambiguous wording mean no fit`() {
        assertNull(DossierSynthesis.parseOffer("NONE"))
        assertNull(DossierSynthesis.parseOffer(""))
        assertNull(DossierSynthesis.parseOffer("aucune"))
        assertNull(DossierSynthesis.parseOffer("formation"), "two trainings match: do not pick one at random")
    }
}
