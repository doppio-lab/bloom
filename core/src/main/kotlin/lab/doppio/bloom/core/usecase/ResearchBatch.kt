package lab.doppio.bloom.core.usecase

import kotlinx.coroutines.CancellationException
import lab.doppio.bloom.core.port.AccountRepository
import lab.doppio.bloom.core.port.FactRepository
import lab.doppio.bloom.core.port.Researcher
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Les comptes au-dessus de 45 sans dossier (ni dossier "aucune offre ne convient") passent en premier, puis les dossiers de plus de 14 jours.
 * L'agent coûte des tokens : on ne l'utilise que là où le score déterministe dit que ça vaut le coup.
 */
class ResearchBatch(
    private val accountRepo: AccountRepository,
    private val factRepo: FactRepository,
    private val research: Researcher,
    private val clock: Clock,
    private val budget: Int = 25,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    suspend operator fun invoke() {
        val stale = clock.instant().minus(14, ChronoUnit.DAYS)
        val dossiers = accountRepo.accountsByScore(500).filter { it.score >= 45 }.associateWith { factRepo.dossier(it.account.id) }
        // A dossier that found no offer to sell is not researched again until a human asks. The verdict is a filter here,
        // never a score term: the score stays computed by code alone
        val rows = dossiers.filterValues { it?.noFit != true }.keys.toList()
        val withoutDossier = rows.filter { dossiers[it] == null }
        val staleOnes = rows.filter { it !in withoutDossier && (it.refreshedAt == null || Instant.parse(it.refreshedAt) < stale) }
        (withoutDossier + staleOnes).take(budget).forEach { row ->
            runCatching { research.research(row.account.id) }.onFailure {
                if (it is CancellationException) throw it
                log.warn("Recherche échouée : ${row.account.name}", it)
            }
        }
    }
}
