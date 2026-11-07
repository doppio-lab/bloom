package lab.doppio.bloom.data.sources.discovery

import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import lab.doppio.bloom.core.model.FactKind
import lab.doppio.bloom.core.model.SignalType
import lab.doppio.bloom.data.sources.MockHttp
import lab.doppio.bloom.data.sources.MutableClock
import lab.doppio.bloom.data.sources.failure
import lab.doppio.bloom.data.sources.fixture
import lab.doppio.bloom.data.sources.json
import lab.doppio.bloom.data.sources.param
import lab.doppio.bloom.data.sources.parseJson
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** BODACC, BOAMP, TED and DECP against the records recorded on 2026-10-10. */
class OpenDataSourcesTest {
    private val now = Instant.parse("2026-10-10T20:00:00Z")
    private val clock = MutableClock(now)
    private val at = Instant.parse("2026-10-11T08:00:00Z")

    // --- BODACC ---------------------------------------------------------------------------------

    @Test
    fun `BODACC asks for capital increases of the last seven days`() = runTest {
        val http = MockHttp { json(fixture("bodacc.json")) }
        BodaccSource(http.client, clock = clock).leads(now)
        val request = http.requests.single()
        assertEquals("https://bodacc-datadila.opendatasoft.com/api/explore/v2.1/catalog/datasets/annonces-commerciales/records", request.url.toString().substringBefore('?'))
        assertEquals(
            "familleavis=\"modification\" and search(modificationsgenerales,\"augmentation\") and dateparution>=date'2026-10-03'",
            request.param("where"),
        )
        assertEquals(listOf("dateparution desc", "100", "0"), listOf("order_by", "limit", "offset").map { request.param(it) })
    }

    @Test
    fun `BODACC maps a notice to a lead with its funding fact and signal`() = runTest {
        val http = MockHttp { json(fixture("bodacc.json")) }
        val leads = BodaccSource(http.client, minCapitalEur = 5_000, clock = clock).leads(now)
        val lead = leads.first { it.name == "FPB RENOVATION" }
        assertEquals("bodacc:B202601942685", lead.seenKey)
        assertEquals("951120708", lead.siren)
        assertEquals(10, lead.minHeadcount)
        assertTrue(lead.requireSectorOrApp)

        val result = lead.build(7, at)
        val fact = result.facts.single()
        assertEquals(FactKind.LEGAL_NOTICE, fact.kind)
        assertTrue(fact.statement.startsWith("FPB RENOVATION : augmentation de capital publiée au BODACC le 09/10/2026, capital porté à 10"))
        assertTrue(fact.statement.endsWith("000 €"))
        assertTrue(fact.sourceUrl.startsWith("https://www.bodacc.fr/"))
        assertEquals(at.toString(), fact.retrievedAt)
        val signal = result.signals.single()
        assertEquals(SignalType.FUNDING, signal.type)
        assertEquals(0.4, signal.strength)
        assertEquals("2026-10-09T00:00:00Z", signal.observedAt)
        assertEquals(listOf(fact.id), signal.factIds)
    }

    @Test
    fun `BODACC drops the notices under the capital threshold`() = runTest {
        val http = MockHttp { json(fixture("bodacc.json")) }
        assertEquals(emptyList(), BodaccSource(http.client, minCapitalEur = 100_000, clock = clock).leads(now).filter { it.name == "FPB RENOVATION" })
    }

    @Test
    fun `BODACC pages by 100 and stops at maxPages`() = runTest {
        val record = fixture("bodacc.json").let { parseJson(it).jsonObject["results"]!!.jsonArray.first() }
        val full = """{"results":${JsonArray(List(100) { record })}}"""
        val http = MockHttp { json(full) }
        BodaccSource(http.client, maxPages = 3, clock = clock).leads(now)
        assertEquals(listOf("0", "100", "200"), http.requests.map { it.param("offset") })
    }

    @Test
    fun `BODACC fails on an HTTP error or a malformed body`() = runTest {
        val error = assertFailsWith<IllegalStateException> { BodaccSource(MockHttp { failure() }.client, clock = clock).leads(now) }
        assertTrue(error.message!!.startsWith("HTTP 500 sur https://bodacc-datadila.opendatasoft.com/"))
        assertFailsWith<Exception> { BodaccSource(MockHttp { json("pas du json") }.client, clock = clock).leads(now) }
        assertEquals(emptyList(), BodaccSource(MockHttp { json("""{"total_count":0}""") }.client, clock = clock).leads(now))
    }

    // --- BOAMP ----------------------------------------------------------------------------------

    @Test
    fun `BOAMP searches mobile app tenders of the last 45 days`() = runTest {
        val http = MockHttp { json(fixture("boamp.json")) }
        BoampSource(http.client).leads(now)
        val request = http.requests.single()
        assertEquals("https://boamp-datadila.opendatasoft.com/api/explore/v2.1/catalog/datasets/boamp/records", request.url.toString().substringBefore('?'))
        val where = request.param("where")!!
        assertTrue("objet like \"%application mobile%\"" in where && where.endsWith("dateparution>=date'2026-08-26'"))
        assertEquals(listOf("dateparution desc", "100", "0"), listOf("order_by", "limit", "offset").map { request.param(it) })
    }

    @Test
    fun `BOAMP keeps the open tenders and builds a lead on the buyer`() = runTest {
        val leads = BoampSource(MockHttp { json(fixture("boamp.json")) }.client).leads(now)
        // The rectification notice (26-93940) has no deadline and is dropped, its call for tenders (26-92856) is kept
        assertEquals(listOf("boamp:26-97978", "boamp:26-92856"), leads.map { it.seenKey })
        val lead = leads.first { it.name == "MAIRIE DE COUTRAS" }
        assertEquals("boamp:26-97978", lead.seenKey)
        assertEquals(false, lead.requireRegistry)

        val result = lead.build(7, at)
        val fact = result.facts.single()
        assertEquals(FactKind.PUBLIC_TENDER, fact.kind)
        assertEquals("MAIRIE DE COUTRAS : appel d'offres publié le 09/10/2026, réponse avant le 09/11/2026", fact.statement)
        assertEquals("https://www.boamp.fr/pages/avis/?q=idweb:26-97978", fact.sourceUrl)
        assertTrue(fact.excerpt!!.contains("application mobile"))
        // The signal is dated by the observation, not by the publication
        assertEquals(at.toString(), result.signals.single().observedAt)
        assertEquals(SignalType.PUBLIC_TENDER, result.signals.single().type)
    }

    @Test
    fun `BOAMP fails on an HTTP error`() = runTest {
        assertFailsWith<IllegalStateException> { BoampSource(MockHttp { failure(HttpStatusCode.ServiceUnavailable) }.client).leads(now) }
    }

    // --- TED ------------------------------------------------------------------------------------

    @Test
    fun `TED posts a JSON search`() = runTest {
        val http = MockHttp { json(fixture("ted.json")) }
        TedSource(http.client).leads(now)
        val request = http.requests.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("https://api.ted.europa.eu/v3/notices/search", request.url.toString())
        val body = parseJson(String(request.body.toByteArray())).jsonObject
        assertEquals(100, body["limit"]!!.jsonPrimitive.int)
        assertEquals("ACTIVE", body["scope"]!!.jsonPrimitive.content)
        assertEquals("PAGE_NUMBER", body["paginationMode"]!!.jsonPrimitive.content)
        assertEquals(1, body["page"]!!.jsonPrimitive.int)
        assertTrue(body["fields"]!!.jsonArray.isNotEmpty())
        assertTrue(body["query"]!!.jsonPrimitive.content.isNotBlank())
    }

    @Test
    fun `TED keeps the open mobile tenders and builds a lead on the buyer`() = runTest {
        val leads = TedSource(MockHttp { json(fixture("ted.json")) }.client).leads(now)
        val lead = leads.single()
        assertEquals("ted:645274-2026", lead.seenKey)
        val fact = lead.build(7, at).facts.single()
        assertEquals(FactKind.PUBLIC_TENDER, fact.kind)
        assertEquals("https://ted.europa.eu/fr/notice/-/detail/645274-2026", fact.sourceUrl)
        assertTrue(fact.statement.startsWith("${lead.name} : appel d'offres publié le "))
    }

    @Test
    fun `TED fails on an HTTP error and on a malformed body`() = runTest {
        val error = assertFailsWith<IllegalStateException> { TedSource(MockHttp { failure() }.client).leads(now) }
        assertEquals("TED HTTP 500", error.message)
        assertFailsWith<Exception> { TedSource(MockHttp { json("{") }.client).leads(now) }
        assertEquals(emptyList(), TedSource(MockHttp { json("{}") }.client).leads(now))
    }

    // --- DECP -----------------------------------------------------------------------------------

    private fun decp(http: MockHttp, names: Map<String, String> = mapOf("222100018" to "Département de la Côte-d'Or", "217806462" to "Ville de Versailles"), seen: MutableList<Triple<String, Boolean, String>> = mutableListOf()) =
        DecpSource(
            http.client,
            buyerName = { names[it] },
            wasSeen = { false },
            markSeen = { key, kept, reason -> seen += Triple(key, kept, reason) },
            clock = clock,
        )

    @Test
    fun `DECP reads both datasets with a six year lookback`() = runTest {
        val http = MockHttp { json(fixture("decp.json")) }
        decp(http).leads(now)
        assertEquals(
            listOf("decp-2022-marches-valides", "decp-v3-marches-valides"),
            http.requests.map { it.url.encodedPath.substringAfter("datasets/").substringBefore("/records") },
        )
        val request = http.requests.first()
        assertEquals("datenotification desc", request.param("order_by"))
        assertTrue(request.param("where")!!.endsWith("datenotification>=date'2020-10-10'"))
    }

    @Test
    fun `DECP builds one lead per buyer and contract that ends within a year`() = runTest {
        val leads = decp(MockHttp { json(fixture("decp.json")) }).leads(now)
        assertEquals(setOf("decp:22210001800019:2026ALS0152534", "decp:21780646200016:202323ABV00400"), leads.map { it.seenKey }.toSet())
        assertEquals(2, leads.size, "the same contracts published in two datasets are counted once")

        val lead = leads.first { it.siren == "222100018" }
        assertEquals("Département de la Côte-d'Or", lead.name)
        assertEquals(false, lead.requireRegistry)
        val result = lead.build(7, at)
        val fact = result.facts.single()
        assertEquals(FactKind.PUBLIC_CONTRACT, fact.kind)
        assertTrue(fact.statement.startsWith("Département de la Côte-d'Or : marché d'application mobile notifié le 03/08/2026 pour 12 mois, échéance le 03/08/2027, montant 210"))
        assertEquals("https://data.economie.gouv.fr/explore/dataset/decp-2022-marches-valides/table/?q=2026ALS0152534", fact.sourceUrl)
        assertEquals(SignalType.CONTRACT_RENEWAL, result.signals.single().type)
        assertEquals(at.toString(), result.signals.single().observedAt)
    }

    @Test
    fun `DECP remembers the buyers that the registry does not know`() = runTest {
        val seen = mutableListOf<Triple<String, Boolean, String>>()
        val leads = decp(MockHttp { json(fixture("decp.json")) }, names = mapOf("217806462" to "Ville de Versailles"), seen = seen).leads(now)
        assertEquals(listOf("Ville de Versailles"), leads.map { it.name })
        assertEquals(Triple("decp:22210001800019:2026ALS0152534", false, "acheteur absent de l'annuaire français"), seen.single())
    }

    @Test
    fun `DECP fails on an HTTP error`() = runTest {
        assertFailsWith<IllegalStateException> { decp(MockHttp { failure() }).leads(now) }
        assertNull(runCatching { decp(MockHttp { json(fixture("decp.json")) }).leads(now) }.exceptionOrNull())
    }
}
