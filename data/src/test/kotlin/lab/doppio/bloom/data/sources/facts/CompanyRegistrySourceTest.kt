package lab.doppio.bloom.data.sources.facts

import kotlinx.coroutines.test.runTest
import lab.doppio.bloom.core.model.FactKind
import lab.doppio.bloom.data.sources.MockHttp
import lab.doppio.bloom.data.sources.MutableClock
import lab.doppio.bloom.data.sources.failure
import lab.doppio.bloom.data.sources.fixture
import lab.doppio.bloom.data.sources.json
import lab.doppio.bloom.data.sources.param
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CompanyRegistrySourceTest {
    private val now = Instant.parse("2026-11-06T10:00:00Z")
    private fun source(http: MockHttp) = CompanyRegistrySource(http.client, MutableClock(now))
    private val registry get() = MockHttp { json(fixture("registry.json")) }

    @Test
    fun `search sends the query and five results per page`() = runTest {
        val http = registry
        val companies = source(http).search("swile")
        val request = http.requests.single()
        assertEquals("https://recherche-entreprises.api.gouv.fr/search", request.url.toString().substringBefore('?'))
        assertEquals(listOf("swile", "5"), listOf("q", "per_page").map { request.param(it) })
        assertEquals(CompanyRegistrySource.Company("824012173", "SWILE (SWILE)", "41", "66.19B", "2016-11-24"), companies.single())
    }

    @Test
    fun `bySiren looks the SIREN up as a query and checks it`() = runTest {
        val http = registry
        assertEquals("SWILE (SWILE)", source(http).bySiren("824012173")?.name)
        assertEquals("824012173", http.requests.single().param("q"))
        assertNull(source(registry).bySiren("111111111"))
    }

    @Test
    fun `matches retries the name without its legal form`() = runTest {
        val http = MockHttp { request ->
            if (request.param("q") == "Swile SAS") json("""{"results":[]}""") else json(fixture("registry.json"))
        }
        val found = source(http).matches("Swile SAS")
        assertEquals(listOf("824012173"), found.map { it.siren })
        assertEquals(listOf("Swile SAS", "swile"), http.requests.map { it.param("q") })
    }

    @Test
    fun `matches gives nothing when no result carries the name`() = runTest {
        assertEquals(emptyList(), source(registry).matches("Totalement Autre"))
    }

    @Test
    fun `an HTTP error makes search fail but matches swallow it`() = runTest {
        val broken = MockHttp { failure() }
        assertFailsWith<Exception> { source(broken).search("swile") }
        assertEquals(emptyList(), source(MockHttp { failure() }).matches("Swile"))
    }

    @Test
    fun `officers are the natural persons who run the company`() = runTest {
        val officers = source(registry).officers("824012173")
        assertEquals(listOf("Loic Soubeyrand", "Anne-Marie Durand"), officers.map { it.fullName })
        assertEquals(emptyList(), source(registry).officers("111111111"))
        assertEquals("https://annuaire-entreprises.data.gouv.fr/entreprise/824012173", source(registry).pageUrl("824012173"))
    }

    @Test
    fun `facts state the headcount band, the NAF code and the creation date`() = runTest {
        val facts = source(registry).facts(7, "824012173")
        assertEquals(
            listOf(
                "SWILE (SWILE) : tranche d'effectif INSEE 41 (500 à 999)",
                "SWILE (SWILE) : code NAF 66.19B",
                "SWILE (SWILE) : créée le 2016-11-24",
            ),
            facts.map { it.statement },
        )
        assertTrue(facts.all { it.kind == FactKind.COMPANY_REGISTRY && it.accountId == 7L && it.retrievedAt == now.toString() })
        assertTrue(facts.all { it.sourceUrl == "https://annuaire-entreprises.data.gouv.fr/entreprise/824012173" })
        assertEquals(emptyList(), source(registry).facts(7, "111111111"))
    }

    @Test
    fun `headcount codes map to a label and a lower bound`() {
        val source = source(registry)
        assertEquals("100 à 199", source.headcountLabel("22"))
        assertEquals("inconnu", source.headcountLabel("99"))
        assertEquals(500, source.headcountLowerBound("41"))
        assertEquals(10_000, source.headcountLowerBound("53"))
        assertNull(source.headcountLowerBound(null))
        assertNull(source.headcountLowerBound("99"))
    }
}
