package lab.doppio.bloom.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import lab.doppio.bloom.core.discovery.BoardProvider
import lab.doppio.bloom.core.discovery.BoardRef
import lab.doppio.bloom.core.discovery.Boamp
import lab.doppio.bloom.core.discovery.Bodacc
import lab.doppio.bloom.core.discovery.Codeur
import lab.doppio.bloom.core.discovery.CompanyNames
import lab.doppio.bloom.core.discovery.GitHub
import lab.doppio.bloom.core.discovery.HiringRules
import lab.doppio.bloom.core.discovery.JobBoards
import lab.doppio.bloom.core.discovery.Press
import lab.doppio.bloom.core.discovery.Registry
import lab.doppio.bloom.core.discovery.Ted
import lab.doppio.bloom.core.model.SignalType
import lab.doppio.bloom.core.scoring.Scoring
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Parsers run on responses recorded from the live sources on 2026-10-10. */
class SourceParsersTest {
    private fun text(name: String): String = javaClass.getResource("/fixtures/$name")!!.readText()
    private fun json(name: String): JsonElement = Json.parseToJsonElement(text(name))
    private val now = Instant.parse("2026-10-10T20:00:00Z")

    @Test
    fun `une annonce BODACC donne le SIREN, le capital et le lien`() {
        val notices = json("bodacc.json").jsonObject["results"]!!.jsonArray.mapNotNull { Bodacc.parse(it.jsonObject) }
        assertEquals(3, notices.size)
        val first = notices.first()
        assertEquals("951120708", first.siren)
        assertEquals("FPB RENOVATION", first.name)
        assertEquals(10_000, first.capitalEur)
        assertTrue(first.url.startsWith("https://www.bodacc.fr/"))
        assertTrue(first.description.contains("augmentation"))
    }

    @Test
    fun `un petit capital est écarté avant toute recherche`() {
        val notice = Bodacc.parse(json("bodacc.json").jsonObject["results"]!!.jsonArray.first().jsonObject)!!
        assertFalse(Bodacc.isCandidate(notice, minCapitalEur = 100_000))
        assertTrue(Bodacc.isCandidate(notice, minCapitalEur = 5_000))
        assertFalse(Bodacc.isCandidate(notice.copy(legalForm = "Société civile immobilière"), minCapitalEur = 5_000))
    }

    @Test
    fun `seul un appel d'offres ouvert pour une app mobile est gardé`() {
        val tenders = json("boamp.json").jsonObject["results"]!!.jsonArray.mapNotNull { Boamp.parse(it.jsonObject) }
        assertEquals(3, tenders.size)
        val kept = tenders.filter { Boamp.isOpenMobileTender(it, now) }
        // The rectification notice has no deadline and is not a call for tenders
        assertTrue(kept.none { it.nature != "APPEL_OFFRE" })
        assertTrue(kept.any { it.subject.contains("application m") })
        assertTrue(kept.all { it.url.startsWith("https://www.boamp.fr/") })
        // Once the deadline has passed, nothing is left
        assertTrue(tenders.none { Boamp.isOpenMobileTender(it, Instant.parse("2027-01-01T00:00:00Z")) })
    }

    @Test
    fun `un avis TED donne l'acheteur, l'objet en français et la date limite`() {
        val tenders = json("ted.json").jsonObject["notices"]!!.jsonArray.mapNotNull { Ted.parse(it.jsonObject) }
        assertEquals(6, tenders.size)
        val award = tenders.first { it.id == "463484-2026" }
        assertEquals("Vendée Expansion - SEM", award.buyer)
        assertTrue(award.subject.startsWith("Fourniture, hébergement et maintenance d'une application mobile"))
        assertEquals("https://ted.europa.eu/fr/notice/-/detail/463484-2026", award.url)
        // An award notice is never an open tender, whatever its subject
        assertFalse(Boamp.isOpenMobileTender(award, now))
        // Open on 10 October, about mobile apps: kept. The design tender matched the full text only: dropped
        val kept = tenders.filter { Boamp.isOpenMobileTender(it, now) }
        assertEquals(listOf("645274-2026"), kept.map { it.id })
        assertTrue(kept.single().subject.contains("applications mobiles"))
        // The deadline is a day: still open during that day, closed the day after
        assertTrue(Boamp.isOpenMobileTender(kept.single(), Instant.parse("2026-10-22T18:00:00Z")))
        assertFalse(Boamp.isOpenMobileTender(kept.single(), Instant.parse("2026-10-23T00:00:01Z")))
    }

    @Test
    fun `un projet Codeur donne son budget et ses catégories`() {
        val projects = Codeur.parseFeed(text("codeur_feed.xml"))
        assertEquals(35, projects.size)
        val first = projects.first()
        assertEquals("491080", first.id)
        assertEquals("10 000 € et plus", first.budget)
        assertTrue("SaaS" in first.categories)
        assertTrue(first.url.startsWith("https://www.codeur.com/projects/"))
        assertEquals(10_000, Codeur.budgetFloor(first.budget))
        assertEquals(0, Codeur.budgetFloor("Moins de 500 €"))
        assertEquals(1_000, Codeur.budgetFloor("1 000 € à 10 000 €"))
    }

    @Test
    fun `seuls les projets mobiles au budget suffisant sont gardés`() {
        val projects = Codeur.parseFeed(text("codeur_feed.xml"))
        assertEquals(4, projects.count { Codeur.isMobileProject(it, minBudgetEur = 0) })
        val kept = projects.filter { Codeur.isMobileProject(it, minBudgetEur = 1_000) }
        assertEquals(1, kept.size)
        assertTrue("Application mobile" in kept.single().categories)
    }

    @Test
    fun `un titre de presse donne l'entreprise et le montant`() {
        val items = Press.parseFeed(text("press_feed.xml"))
        assertEquals(12, items.size)
        assertTrue(items.all { it.link.startsWith("https://") && it.publishedAt != null })
        val rounds = items.mapNotNull { Press.funding(it.title) }.associate { it.company to it.amount }
        assertEquals("5,4 millions d'euros", rounds["VERSO"])
        assertEquals("8 millions d'euros", rounds["CLEAVR"])
        assertEquals("30 millions d'euros", rounds["VATES"])
    }

    @Test
    fun `un titre qui n'annonce pas une levée ne donne rien`() {
        assertNull(Press.funding("Les startups françaises ont levé 217 millions d'euros cette semaine"))
        assertNull(Press.funding("Développer une entreprise rentable : autofinancement, levée de fonds ou acquisitions ?"))
        assertNull(Press.funding("Études consommateurs : ce que l'IA change dans l'économie du métier"))
    }

    @Test
    fun `le flux de presse refuse une déclaration DOCTYPE`() {
        val hostile = "<?xml version=\"1.0\"?><!DOCTYPE r [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><rss><channel><item><title>&x;</title><link>https://a.example</link></item></channel></rss>"
        assertTrue(runCatching { Press.parseFeed(hostile) }.isFailure)
    }

    @Test
    fun `GitHub ne garde que les organisations`() {
        val repos = GitHub.organisationRepos(json("github_repos.json").jsonObject)
        assertTrue(repos.isNotEmpty())
        assertTrue(repos.none { it.first == "yuroyami" })
        assertEquals(3, GitHub.logins(json("github_orgs.json").jsonObject).size)
        val org = assertNotNull(GitHub.parseOrg(json("github_org.json").jsonObject))
        assertEquals("https://insert-koin.io", org.website)
        assertEquals("https://github.com/InsertKoinIO", org.url)
    }

    @Test
    fun `les trois tableaux d'offres donnent titre, lien et date`() {
        val greenhouse = JobBoards.parse(BoardProvider.GREENHOUSE, json("greenhouse.json"))
        assertEquals(3, greenhouse.size)
        assertTrue(greenhouse.first().dateIsFirstPublication)
        assertEquals(1, greenhouse.count { HiringRules.isMobile(it.title) })

        val lever = JobBoards.parse(BoardProvider.LEVER, json("lever.json"))
        assertEquals(2, lever.size)
        assertTrue(lever.all { it.publishedAt != null && it.url.startsWith("https://jobs.lever.co/") })

        val ashby = JobBoards.parse(BoardProvider.ASHBY, json("ashby.json"))
        assertEquals(2, ashby.size)
        // Ashby resets the date on republish: it must never feed the "stuck hire" signal
        assertTrue(ashby.none { it.dateIsFirstPublication })
    }

    @Test
    fun `SmartRecruiters donne titre, lien public et lieu`() {
        val postings = JobBoards.parse(BoardProvider.SMARTRECRUITERS, json("smartrecruiters.json"))
        assertEquals(3, postings.size)
        assertEquals("https://jobs.smartrecruiters.com/Ubisoft2/744000154736845", postings.first().url)
        assertEquals("Montreal, QC, Canada", postings.first().location)
        // The release date moves when a posting is published again: never an age
        assertTrue(postings.none { it.dateIsFirstPublication })
        assertEquals(BoardRef(BoardProvider.SMARTRECRUITERS, "Ubisoft2"), JobBoards.detect("<a href=\"https://careers.smartrecruiters.com/Ubisoft2\">"))
    }

    @Test
    fun `l'annuaire donne ceux qui dirigent, sans administrateurs ni commissaires aux comptes`() {
        // The record is the real one of a company, plus three invented lines for the cases it does not hold
        val company = json("registry.json").jsonObject["results"]!!.jsonArray.first().jsonObject
        val officers = Registry.officers(company)
        assertEquals(listOf("Loic Soubeyrand" to "Président de SAS", "Anne-Marie Durand" to "Directeur général délégué"), officers.map { it.fullName to it.role })
    }

    @Test
    fun `le nom d'un éditeur est essayé sous plusieurs formes dans l'annuaire`() {
        assertEquals(listOf("Finary SAS", "finary"), CompanyNames.registryQueries("Finary SAS"))
        assertEquals(listOf("Bankin' SAS", "bankin"), CompanyNames.registryQueries("Bankin' SAS"))
        assertTrue("Infomil" in CompanyNames.registryQueries("E.Leclerc - Infomil"))
        assertEquals(listOf("Swile"), CompanyNames.registryQueries("Swile").filter { it == "Swile" })
    }

    @Test
    fun `le lien vers un tableau d'offres est reconnu dans une page`() {
        assertEquals(BoardRef(BoardProvider.GREENHOUSE, "doctolib"), JobBoards.detect("<a href=\"https://job-boards.greenhouse.io/doctolib/jobs/1\">"))
        assertEquals(BoardRef(BoardProvider.LEVER, "swile"), JobBoards.detect("voir https://jobs.lever.co/swile pour postuler"))
        assertEquals(BoardRef(BoardProvider.ASHBY, "qonto"), JobBoards.detect("<a href='https://jobs.ashbyhq.com/qonto'>"))
        assertNull(JobBoards.detect("<a href=\"https://exemple.fr/carrieres\">"))
        assertEquals("https://api.lever.co/v0/postings/swile?mode=json", JobBoards.apiUrl(BoardRef(BoardProvider.LEVER, "swile")))
    }

    @Test
    fun `une offre ouverte depuis plus de 45 jours signale un recrutement bloqué`() {
        assertNull(HiringRules.stuckStrength(null))
        assertNull(HiringRules.stuckStrength(45))
        assertEquals(0.3, HiringRules.stuckStrength(46))
        assertEquals(1.0, HiringRules.stuckStrength(200))
        assertTrue(HiringRules.mentionsKmp("Senior Android, Kotlin Multiplatform"))
    }

    @Test
    fun `chaque type de signal a une règle de score et une offre`() {
        SignalType.entries.forEach { type ->
            assertNotNull(Scoring.rules[type], "pas de règle pour $type")
            assertNotNull(Scoring.offerBySignal[type], "pas d'offre pour $type")
        }
    }
}
