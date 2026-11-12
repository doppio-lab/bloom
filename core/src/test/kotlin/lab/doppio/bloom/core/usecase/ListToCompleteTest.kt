package lab.doppio.bloom.core.usecase

import kotlinx.coroutines.test.runTest
import lab.doppio.bloom.core.model.Account
import lab.doppio.bloom.core.model.FactKind
import lab.doppio.bloom.core.model.Signal
import lab.doppio.bloom.core.model.SignalType
import lab.doppio.bloom.core.model.fact
import lab.doppio.bloom.core.port.AccountRow
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ListToCompleteTest {
    private val accounts = FakeAccountRepository()
    private val factRepo = FakeFactRepository()
    private val list = ListToComplete(accounts, factRepo)

    private val before = Instant.parse("2026-10-01T02:00:00Z")
    private val now = Instant.parse("2026-11-10T02:00:00Z")
    private val feed = "https://itunes.apple.com/fr/rss/customerreviews/page=1/id=42/sortBy=mostRecent/json"

    private fun row(account: Account) = AccountRow(account, 70, 30, 40, 0, null, emptyList(), null)
    private fun issue(count: Int, theme: String, at: Instant = now) =
        fact(10, FactKind.APP_REVIEW_ISSUE, "Acme : $count avis sur 30 des 90 derniers jours parlent de $theme", feed, at = at)
    private fun quote(day: String, title: String, text: String, at: Instant = now) =
        fact(10, FactKind.APP_REVIEW_QUOTE, "Avis App Store 1/5 du $day, version 2.0 : « $title »", "https://apps.apple.com/fr/app/id42?see-all=reviews", excerpt = text, at = at)

    @Test
    fun `un prospect avec une app porte le lien vers sa page App Store`() = runTest {
        accounts.toComplete = listOf(row(Account(10, "Acme", iosAppId = 42)), row(Account(11, "Sans app")))
        val (withApp, without) = list()
        assertEquals("https://apps.apple.com/fr/app/id42", withApp.row.appStoreUrl)
        assertNull(without.row.appStoreUrl)
    }

    @Test
    fun `les problèmes remontés sont ceux du dernier relevé, le plus cité en premier`() = runTest {
        accounts.toComplete = listOf(row(Account(10, "Acme", iosAppId = 42)))
        factRepo.facts = listOf(issue(3, "lenteurs"), issue(9, "plantages"), issue(5, "la batterie", at = before))
        assertEquals(
            listOf("Acme : 9 avis sur 30 des 90 derniers jours parlent de plantages", "Acme : 3 avis sur 30 des 90 derniers jours parlent de lenteurs"),
            list().single().issues.map { it.statement },
        )
    }

    @Test
    fun `les avis cités suivent les thèmes, problème technique d'abord puis le plus récent`() = runTest {
        accounts.toComplete = listOf(row(Account(10, "Acme", iosAppId = 42)))
        factRepo.facts = listOf(
            quote("02/11/2026", "Trop cher", "L'abonnement a doublé"),
            quote("28/10/2026", "Scan", "Impossible de scanner le QR code"),
            quote("05/11/2026", "Compte", "Je dois me reconnecter à chaque fois"),
            quote("20/09/2026", "Vieux", "L'app plante", at = before),
            issue(2, "problèmes de connexion"),
        )
        assertEquals(
            listOf("problèmes de connexion", "« Compte »", "« Scan »", "« Trop cher »"),
            list().single().issues.map { it.statement.substringAfter("parlent de ").substringAfter(" : ") },
        )
    }

    @Test
    fun `les problèmes ne sont pas répétés dans les faits de la carte`() = runTest {
        accounts.toComplete = listOf(row(Account(10, "Acme", iosAppId = 42)))
        val complaints = fact(10, FactKind.APP_REVIEW, "Acme : 9 avis sur 30 des 90 derniers jours signalent un problème technique", feed, at = now)
        factRepo.facts = listOf(issue(9, "plantages"), quote("05/11/2026", "Nul", "ça plante"), complaints)
        factRepo.signals = listOf(Signal(10, SignalType.QUALITY_COMPLAINTS, 0.6, now.toString(), listOf(complaints.id)))
        assertEquals(listOf(complaints.id), list().single().facts.map { it.id })
    }

    @Test
    fun `sans avis lu, aucun problème n'est affiché`() = runTest {
        accounts.toComplete = listOf(row(Account(11, "Sans app")))
        assertEquals(emptyList(), list().single().issues)
    }
}
