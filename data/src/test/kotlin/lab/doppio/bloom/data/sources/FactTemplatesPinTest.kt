package lab.doppio.bloom.data.sources

import kotlin.test.Test
import kotlin.test.assertEquals

/** Pins every fact sentence, including the optional parts the source tests do not reach. */
class FactTemplatesPinTest {
    @Test
    fun `App Store sentences`() {
        assertEquals("Mon App : note moyenne App Store 4,6 sur 1200 notes (fr)", FactTemplates.appRating("Mon App", 4.55, 1200, "fr"))
        assertEquals("Mon App : dernière version 3.2 publiée il y a 309 jours", FactTemplates.appLastRelease("Mon App", "3.2", 309))
        assertEquals(
            "Mon App : moyenne des 10 avis des 90 derniers jours 2,6, contre 4,6 en moyenne globale",
            FactTemplates.appRecentRating("Mon App", 10, 90, 2.6, 4.6),
        )
        assertEquals(
            "Mon App : 6 avis sur 10 des 90 derniers jours signalent un problème technique (version la plus citée : 3.2)",
            FactTemplates.appComplaints("Mon App", 6, 10, 90, "3.2"),
        )
        assertEquals(
            "Mon App : 6 avis sur 10 des 90 derniers jours signalent un problème technique",
            FactTemplates.appComplaints("Mon App", 6, 10, 90, null),
        )
    }

    @Test
    fun `registry sentences`() {
        assertEquals("ACME : tranche d'effectif INSEE 41 (500 à 999)", FactTemplates.registryHeadcount("ACME", "41", "500 à 999"))
        assertEquals("ACME : code NAF 66.19B", FactTemplates.registryNaf("ACME", "66.19B"))
        assertEquals("ACME : créée le 2016-11-24", FactTemplates.registryCreated("ACME", "2016-11-24"))
    }

    @Test
    fun `job offers share one template, with and without an age`() {
        assertEquals("Offre d'emploi « Développeur iOS » publiée il y a 66 jours", FactTemplates.jobOffer("Développeur iOS", 66))
        assertEquals("Offre d'emploi « Développeur iOS »", FactTemplates.jobOffer("Développeur iOS", null))
        assertEquals(
            "L'offre « Développeur Android » mentionne une stack cross-platform (Flutter ou React Native)",
            FactTemplates.crossPlatformStack("Développeur Android"),
        )
    }

    @Test
    fun `web page, tender, contract, capital, repository and funding sentences`() {
        assertEquals("Page lue : Carrières Acme", FactTemplates.webPage("Carrières Acme"))
        assertEquals(
            "ACME : augmentation de capital publiée au BODACC le 09/10/2026, capital porté à 10 000 €",
            FactTemplates.capitalIncrease("ACME", "09/10/2026", "10 000 €"),
        )
        assertEquals(
            "MAIRIE : appel d'offres publié le 09/10/2026, réponse avant le 09/11/2026",
            FactTemplates.publicTender("MAIRIE", "09/10/2026", "09/11/2026"),
        )
        assertEquals("MAIRIE : appel d'offres publié le 09/10/2026", FactTemplates.publicTender("MAIRIE", "09/10/2026", null))
        assertEquals(
            "Dept : marché d'application mobile notifié le 03/08/2026 pour 12 mois, échéance le 03/08/2027, montant 210 000 €",
            FactTemplates.publicContract("Dept", "03/08/2026", 12, "03/08/2027", "210 000 €"),
        )
        assertEquals(
            "Dept : marché d'application mobile notifié le 03/08/2026 pour 12 mois, échéance le 03/08/2027",
            FactTemplates.publicContract("Dept", "03/08/2026", 12, "03/08/2027", null),
        )
        assertEquals(
            "insert-koin.io : dépôt public Kotlin Multiplatform mis à jour ces 60 derniers jours",
            FactTemplates.githubRepository("insert-koin.io", 60),
        )
        assertEquals(
            "VERSO : levée de 5,4 millions d'euros annoncée le 09/10/2026",
            FactTemplates.fundingRound("VERSO", "5,4 millions d'euros", "09/10/2026"),
        )
    }
}
