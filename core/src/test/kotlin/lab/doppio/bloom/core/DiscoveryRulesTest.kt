package lab.doppio.bloom.core

import lab.doppio.bloom.core.discovery.CompanyNames
import lab.doppio.bloom.core.discovery.Csv
import lab.doppio.bloom.core.discovery.Exclusions
import lab.doppio.bloom.core.discovery.SectorMapping
import lab.doppio.bloom.core.discovery.Targeting
import lab.doppio.bloom.core.model.Offer
import lab.doppio.bloom.core.model.Sector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiscoveryRulesTest {

    @Test
    fun `les formes juridiques et accents disparaissent`() {
        assertEquals("velomoov", CompanyNames.normalize("Vélomoov SAS"))
        assertEquals("banque-ardoise", CompanyNames.normalize("BANQUE ARDOISE France"))
    }

    @Test
    fun `deux écritures d'une même entreprise sont reconnues`() {
        assertTrue(CompanyNames.sameCompany("Decathlon SE", "DECATHLON"))
        assertTrue(CompanyNames.sameCompany("Qonto", "Qonto SAS (Olinda)"))
        assertFalse(CompanyNames.sameCompany("Netflix, Inc.", "Netatmo"))
        assertFalse(CompanyNames.sameCompany("Air", "Air Liquide"))
    }

    @Test
    fun `le domaine est extrait de l'url du vendeur`() {
        assertEquals("decathlon.fr", CompanyNames.domainOf("https://www.decathlon.fr/app?x=1"))
        assertNull(CompanyNames.domainOf("https://apps.apple.com/fr/app/id1"))
        assertNull(CompanyNames.domainOf(""))
    }

    @Test
    fun `les ESN, cabinets et agences sont écartés`() {
        assertNotNull(Exclusions.reason("ALTEN SA"))
        assertNotNull(Exclusions.reason("Capgemini Technology Services"))
        assertNotNull(Exclusions.reason("Mobile Talent Consulting"))
        assertNotNull(Exclusions.reason("Inconnue", naf = "78.20Z"))
        assertNull(Exclusions.reason("Vélomoov", naf = "49.39B"))
        assertNull(Exclusions.reason("Decathlon SE", naf = "47.64Z"))
    }

    @Test
    fun `la liste d'exclusion est comparée après normalisation du nom`() {
        // "groupe", "de" and "la" are stripped from the account name, so the list must be stripped the same way.
        assertNotNull(Exclusions.reason("Groupe Open"))
        assertNotNull(Exclusions.reason("Crème de la Crème SAS"))
    }

    @Test
    fun `les mots-clés d'exclusion se comparent mot à mot`() {
        assertNull(Exclusions.reason("Fresnel"))
        assertNull(Exclusions.reason("Talentsoft"))
        assertNotNull(Exclusions.reason("Esn Lyon"))
        assertNotNull(Exclusions.reason("Agence Digitale Nantes"))
        // "conseil" as a whole word still matches: a local authority is not a prospect either.
        assertNotNull(Exclusions.reason("Conseil départemental"))
    }

    @Test
    fun `le secteur vient du code NAF ou du genre App Store`() {
        assertEquals(Sector.BANK_INSURANCE, SectorMapping.fromNaf("64.19Z"))
        assertEquals(Sector.RETAIL, SectorMapping.fromNaf("47.64Z"))
        assertEquals(Sector.HEALTH, SectorMapping.fromGenre(6020))
        assertEquals(Sector.OTHER, SectorMapping.fromGenre(null))
    }

    @Test
    fun `la persona dépend de l'offre`() {
        assertEquals("Engineering Manager", Targeting.personaFor(Offer.REGIE).roles.first())
        assertTrue(Targeting.linkedinSearchUrl("CTO", "Vélomoov").contains("keywords=CTO+V%C3%A9lomoov"))
        assertEquals(lab.doppio.bloom.core.model.Persona.HEAD_OF_MOBILE, Targeting.personaFromRole("Lead Mobile iOS"))
        assertEquals(lab.doppio.bloom.core.model.Persona.CTO, Targeting.personaFromRole("CTO & cofondateur"))
        assertEquals(lab.doppio.bloom.core.model.Persona.ENGINEERING_MANAGER, Targeting.personaFromRole("Engineering Manager Apps"))
    }

    @Test
    fun `le CSV Excel français est lu`() {
        val rows = Csv.parse("﻿name;domain;warm_path\r\nVélomoov;velomoov.fr;\"Recommandé par Julie; ex-Decathlon\"\r\nTrackr;trackr.fr;\r\n")
        assertEquals(2, rows.size)
        assertEquals("Recommandé par Julie; ex-Decathlon", rows[0]["warm_path"])
        assertEquals("trackr.fr", rows[1]["domain"])
    }
}
