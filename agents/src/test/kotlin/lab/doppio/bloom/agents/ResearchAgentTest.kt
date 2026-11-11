package lab.doppio.bloom.agents

import kotlinx.coroutines.test.runTest
import lab.doppio.bloom.core.model.SignalType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SiteResearchToolsTest {
    private val page = "Notre équipe mobile recrute.   Nous construisons notre app avec Kotlin Multiplatform\net l'IA générative."
    private val web = FakeWebPages(
        mapOf(
            "https://acme.fr/" to page,
            "https://careers.acme.fr/jobs" to "Offres",
            "https://acme.fr/a" to "A", "https://acme.fr/b" to "B", "https://acme.fr/c" to "C",
            "https://acme.fr/d" to "D", "https://acme.fr/e" to "E", "https://acme.fr/f" to "F", "https://acme.fr/g" to "G",
        ),
    )
    private val tools = SiteResearchTools(accountId = 1, domain = "acme.fr", web = web, clock = fixedClock)

    @Test
    fun `sameSite accepts the domain and its subdomains only`() {
        val ok = listOf("https://acme.fr", "https://acme.fr/", "http://www.acme.fr/x?y=1", "https://careers.acme.fr/jobs", "HTTPS://ACME.FR/Path")
        val ko = listOf("https://acme.fr.evil.com/", "https://evilacme.fr/", "https://example.com/acme.fr", "ftp://acme.fr/", "https://acme.frx/", "//acme.fr/")
        ok.forEach { assertTrue(tools.sameSite.matches(it), it) }
        ko.forEach { assertFalse(tools.sameSite.matches(it), it) }
    }

    @Test
    fun `readPage refuses other domains without fetching`() = runTest {
        val answer = tools.readPage("https://evil.com/")
        assertTrue(answer.startsWith("Refusé"))
        assertTrue(web.reads.isEmpty())
    }

    @Test
    fun `readPage stops at six distinct pages but allows rereading one`() = runTest {
        listOf("a", "b", "c", "d", "e", "f").forEach { assertEquals(it.uppercase(), tools.readPage("https://acme.fr/$it")) }
        assertTrue(tools.readPage("https://acme.fr/g").startsWith("Limite de 6 pages"))
        assertEquals("A", tools.readPage("https://acme.fr/a"))
    }

    @Test
    fun `readPage reports an unreadable page`() = runTest {
        assertTrue(tools.readPage("https://acme.fr/missing").startsWith("Page illisible"))
    }

    @Test
    fun `recordQuote needs the page to be read first`() = runTest {
        assertTrue(tools.recordQuote("https://acme.fr/", "Notre équipe mobile recrute", "Recrutement mobile", "HIRING").startsWith("Refusé : lis d'abord"))
        assertTrue(tools.recorded.isEmpty())
    }

    @Test
    fun `recordQuote keeps only quotes found word for word`() = runTest {
        tools.readPage("https://acme.fr/")
        val invented = tools.recordQuote("https://acme.fr/", "Notre équipe mobile compte douze ingénieurs", "Taille", "STACK")
        assertTrue(invented.startsWith("Refusé : citation introuvable"))
        assertTrue(tools.recorded.isEmpty())

        // Whitespace and case differences are tolerated, the words are not
        val ok = tools.recordQuote("https://acme.fr/", "  notre équipe   MOBILE recrute ", "L'équipe mobile recrute", "HIRING")
        assertTrue(ok.startsWith("Enregistré sous l'identifiant"))
        val fact = tools.recorded.single()
        assertEquals("notre équipe MOBILE recrute", fact.excerpt)
        assertEquals("https://acme.fr/", fact.sourceUrl)
        assertEquals(fixedClock.instant().toString(), fact.retrievedAt)
        assertTrue(tools.signals.isEmpty(), "HIRING adds a fact but no signal")
    }

    @Test
    fun `recordQuote enforces the length bounds`() = runTest {
        tools.readPage("https://acme.fr/")
        assertTrue(tools.recordQuote("https://acme.fr/", "Notre éq", "x", "OTHER").contains("entre 12 et 300"))
        assertTrue(tools.recordQuote("https://acme.fr/", "x".repeat(301), "x", "OTHER").contains("entre 12 et 300"))
    }

    @Test
    fun `quotes spanning a line break match and the category sets the signal`() = runTest {
        tools.readPage("https://acme.fr/")
        tools.recordQuote("https://acme.fr/", "Kotlin Multiplatform\net l'IA générative", "Stack et IA", "ai_usage")
        tools.recordQuote("https://acme.fr/", "Notre équipe mobile recrute", "Projet mobile", "MOBILE_PROJECT")
        assertEquals(listOf(SignalType.AI_ADOPTION, SignalType.PRODUCT_LAUNCH), tools.signals.map { it.type })
        assertEquals(tools.recorded.map { it.id }, tools.signals.flatMap { it.factIds })
    }
}

class QuoteQualityTest {
    private val web = FakeWebPages(mapOf("https://acme.fr/" to "L\u2019équipe\u00A0mobile passe à Kotlin Multiplatform cette année."))
    private val tools = SiteResearchTools(accountId = 1, domain = "acme.fr", web = web, clock = fixedClock)

    @Test
    fun `curly apostrophes and non breaking spaces on the page do not reject a faithful quote`() = runTest {
        tools.readPage("https://acme.fr/")
        val answer = tools.recordQuote("https://acme.fr/", "L'équipe mobile passe à Kotlin", "Migration vers KMP", "STACK")
        assertTrue(answer.startsWith("Enregistré"), answer)
    }

    @Test
    fun `a quote outside the known categories is refused instead of becoming noise`() = runTest {
        tools.readPage("https://acme.fr/")
        val answer = tools.recordQuote("https://acme.fr/", "passe à Kotlin Multiplatform", "Phrase", "OTHER")
        assertTrue(answer.startsWith("Refusé : catégorie inconnue"), answer)
        assertTrue(tools.recorded.isEmpty())
    }
}

class UngroundedNumbersTest {
    private val grounding = "Acme : note moyenne App Store 4,3 sur 1234 notes. Offre « Dev iOS » publiée il y a 12 jours"

    @Test
    fun `numbers stated by a fact are grounded, others are not`() {
        assertEquals(emptySet(), ungroundedNumbers("Note de 4,3 sur 1234 avis", grounding))
        assertEquals(setOf("40"), ungroundedNumbers("Une équipe de 40 personnes", grounding))
    }

    @Test
    fun `a summary keeps its grounded sentences and loses the invented ones`() {
        val summary = "L'app est notée 4,3. L'équipe compte 40 ingénieurs. Un poste iOS est ouvert depuis 12 jours."
        assertEquals("L'app est notée 4,3. Un poste iOS est ouvert depuis 12 jours.", withoutUngroundedSentences(summary, grounding))
    }
}

class ProvenHypothesesTest {
    private fun h(statement: String, vararg ids: String) = ResearchAgent.Hypothesis(statement, ids.toList())

    @Test
    fun `hypotheses are kept only when every cited fact exists`() {
        val known = setOf("a1", "a2")
        val kept = provenHypotheses(
            listOf(
                h("Crash au paiement", "a1", "a2"),
                h("Invention", "zz"),
                h("Moitié prouvée", "a1", "zz"),
                h("Sans preuve"),
            ),
            known,
        )
        assertEquals(listOf("Crash au paiement [F:a1] [F:a2]"), kept)
    }
}
