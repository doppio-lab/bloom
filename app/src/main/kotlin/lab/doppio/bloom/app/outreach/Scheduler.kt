package lab.doppio.bloom.app.outreach

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import lab.doppio.bloom.core.port.DraftRepository
import lab.doppio.bloom.core.port.InstanceLock
import lab.doppio.bloom.core.usecase.PlanDrafts
import lab.doppio.bloom.core.usecase.ProcessReplies
import lab.doppio.bloom.core.usecase.RunPeriodicJobs
import lab.doppio.bloom.core.usecase.SendNextApproved
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.minutes

/**
 * Boucles de fond. Chaque boucle est idempotente : un redémarrage ne double ni un envoi ni un brouillon.
 * The Scheduler only decides when things run; what they do is in the use cases.
 *
 *  - planification : prépare les brouillons dus ;
 *  - envoi : envoie les emails approuvés ;
 *  - relève : classe les réponses ;
 *  - nuit : tâches périodiques (découverte, recherche, coach, purge).
 */
class Scheduler(
    private val instanceLock: InstanceLock,
    private val draftRepo: DraftRepository,
    private val planDrafts: PlanDrafts,
    private val sendNextApproved: SendNextApproved,
    private val processReplies: ProcessReplies,
    private val runPeriodicJobs: RunPeriodicJobs,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Long jobs (discovery, research, coach) run here so they never hold up each other or the loops. */
    private var jobs: CoroutineScope? = null

    /** True once this instance holds the loops' lock. */
    @Volatile private var leader = false

    fun start(scope: CoroutineScope) {
        jobs = scope
        scope.launch {
            // Une seule instance fait tourner les boucles : sinon chaque email partirait deux fois
            while (!runCatching { instanceLock.tryHold() }.getOrDefault(false)) {
                log.warn("Une autre instance de Bloom tient les boucles de fond : nouvel essai dans 30 s")
                delay(30_000)
            }
            leader = true
            // Avant toute boucle : un brouillon resté SENDING date d'avant le redémarrage
            val interrupted = draftRepo.reconcileInterruptedSends()
            if (interrupted > 0) log.warn("$interrupted email(s) interrompu(s) pendant l'envoi, passés en FAILED : vérifier la boîte d'envoi")
            loop("planification", 10.minutes) { planDrafts() }
            loop("envoi", 2.minutes) { sendNextApproved() }
            loop("relève", 5.minutes) { processReplies() }
            loop("nuit", 30.minutes) { runPeriodicJobs(spawn = { job -> jobs?.launch { job() } ?: job() }) }
        }
    }

    private fun CoroutineScope.loop(name: String, every: kotlin.time.Duration, block: suspend () -> Unit) = launch {
        while (isActive) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("Boucle $name en échec", e)
            }
            delay(every)
        }
    }

    /** Plans right away, in the background: an HTTP request never waits for the writer. */
    fun planSoon() {
        if (!leader) return // another instance runs the loops and will plan within 10 minutes
        jobs?.launch {
            try { planDrafts() } catch (e: CancellationException) { throw e } catch (e: Exception) { log.error("Planification en échec", e) }
        }
    }
}
