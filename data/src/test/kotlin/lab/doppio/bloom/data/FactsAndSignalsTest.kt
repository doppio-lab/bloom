package lab.doppio.bloom.data

import kotlinx.coroutines.test.runTest
import lab.doppio.bloom.core.model.Account
import lab.doppio.bloom.core.model.FactKind
import lab.doppio.bloom.data.db.Repository
import lab.doppio.bloom.data.sources.fact
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class FactsAndSignalsTest {
    private val repo = Repository()

    @BeforeTest
    fun clean() = TestDatabase.reset()

    @Test
    fun `un fait déjà enregistré n'est pas écrasé`() = runTest {
        val accountId = repo.upsertAccount(Account(name = "Velomoov"))
        val f = fact(accountId, FactKind.WEB_PAGE, "Page lue : Velomoov", "https://velomoov.fr", excerpt = "first")
        repo.saveFacts(listOf(f))
        repo.saveFacts(listOf(f.copy(excerpt = "second")))
        val saved = repo.factsOf(accountId).single()
        assertEquals(40, saved.id.length)
        assertEquals("first", saved.excerpt)
    }
}
