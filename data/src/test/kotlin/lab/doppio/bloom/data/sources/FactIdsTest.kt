package lab.doppio.bloom.data.sources

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import lab.doppio.bloom.core.discovery.Posting
import lab.doppio.bloom.core.model.FactKind
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class FactIdsTest {
    @Test
    fun `l'identifiant d'un fait est le SHA-1 complet de sa source et de son énoncé`() {
        val a = FactIds.of("https://example.fr/a", "Offre A")
        val b = FactIds.of("https://example.fr/b", "Offre B")
        assertEquals(40, a.length)
        assertEquals(40, b.length)
        assertTrue(a.all { it in "0123456789abcdef" })
        assertNotEquals(a, b)
        assertEquals(a, FactIds.of("https://example.fr/a", "Offre A"), "deterministic")
    }

    @Test
    fun `une offre lue deux nuits de suite donne le même fait`() {
        val posting = Posting("Développeur iOS", "https://jobs.example.fr/1", Instant.parse("2026-10-01T00:00:00Z"), "Paris", dateIsFirstPublication = true)
        val source = JobBoardSource(HttpClient(MockEngine { respond("") }))
        val first = source.result(1, listOf(posting), now = Instant.parse("2026-10-10T02:00:00Z"))
        val second = source.result(1, listOf(posting), now = Instant.parse("2026-10-11T02:00:00Z"))
        assertEquals(first.facts.map { it.id }, second.facts.map { it.id })
        assertTrue(first.facts.single().statement.contains("01/10/2026"), "the statement keeps the publication date: ${first.facts.single().statement}")
    }

    @Test
    fun `un fait est daté de l'instant passé par la source`() {
        val now = Instant.parse("2026-10-10T02:00:00Z")
        assertEquals(now.toString(), fact(1, FactKind.WEB_PAGE, "Page lue", "https://example.fr", now = now).retrievedAt)

        val posting = Posting("Développeur Android", "https://jobs.example.fr/2", null, null, dateIsFirstPublication = false)
        val result = JobBoardSource(HttpClient(MockEngine { respond("") })).result(1, listOf(posting), now = now)
        assertEquals(now.toString(), result.facts.single().retrievedAt)
    }
}
