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
 * Les comptes au-dessus de 45 sans dossier passent en premier, puis les dossiers de plus de 14 jours.
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
        val rows = accountRepo.accountsByScore(500).filter { it.score >= 45 }
        val withoutDossier = rows.filter { factRepo.dossier(it.account.id) == null }
        val staleOnes = rows.filter { it !in withoutDossier && (it.refreshedAt == null || Instant.parse(it.refreshedAt) < stale) }
        (withoutDossier + staleOnes).take(budget).forEach { row ->
            runCatching { research.research(row.account.id) }.onFailure {
                if (it is CancellationException) throw it
                log.warn("Recherche échouée : ${row.account.name}", it)
            }
        }
    }
}
