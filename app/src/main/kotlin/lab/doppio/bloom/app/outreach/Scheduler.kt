package lab.doppio.bloom.app.outreach

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import lab.doppio.bloom.core.port.DraftRepository
import lab.doppio.bloom.core.usecase.PlanDrafts
import lab.doppio.bloom.core.usecase.ProcessReplies
import lab.doppio.bloom.core.usecase.RunPeriodicJobs
import lab.doppio.bloom.core.usecase.SendNextApproved
import org.slf4j.LoggerFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** How often each loop runs. */
data class LoopIntervals(
    val plan: Duration = DEFAULT_PLAN,
    val send: Duration = DEFAULT_SEND,
    val replies: Duration = DEFAULT_REPLIES,
    val periodic: Duration = DEFAULT_PERIODIC,
) {
    companion object {
        val DEFAULT_PLAN: Duration = 10.minutes
        val DEFAULT_SEND: Duration = 2.minutes
        val DEFAULT_REPLIES: Duration = 5.minutes
        val DEFAULT_PERIODIC: Duration = 30.minutes
    }
}

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
    private val gate: LeaderGate,
    private val draftRepo: DraftRepository,
    private val planDrafts: PlanDrafts,
    private val sendNextApproved: SendNextApproved,
    private val processReplies: ProcessReplies,
    private val runPeriodicJobs: RunPeriodicJobs,
    private val intervals: LoopIntervals = LoopIntervals(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private companion object {
        /** A standby instance asks for the loops' lock again after this long. */
        const val LOCK_RETRY_SECONDS = 30L
    }

    /** Long jobs (discovery, research, coach) run here so they never hold up each other or the loops. */
    private var jobs: CoroutineScope? = null

    fun start(scope: CoroutineScope) {
        jobs = scope
        scope.launch {
            // Une seule instance fait tourner les boucles : sinon chaque email partirait deux fois
            while (!gate.becomeLeader()) {
                log.warn("Une autre instance de Bloom tient les boucles de fond : nouvel essai dans $LOCK_RETRY_SECONDS s")
                delay(LOCK_RETRY_SECONDS * 1_000)
            }
            // Avant toute boucle : un brouillon resté SENDING date d'avant le redémarrage
            val interrupted = draftRepo.reconcileInterruptedSends()
            if (interrupted > 0) log.warn("$interrupted email(s) interrompu(s) pendant l'envoi, passés en FAILED : vérifier la boîte d'envoi")
            loop("planification", intervals.plan) { planDrafts() }
            loop("envoi", intervals.send) { sendNextApproved() }
            loop("relève", intervals.replies) { processReplies() }
            loop("nuit", intervals.periodic) { runPeriodicJobs(spawn = { job -> jobs?.launch { job() } ?: job() }) }
        }
    }

    private fun CoroutineScope.loop(name: String, every: Duration, block: suspend () -> Unit) = launch {
        while (isActive) {
            if (!gate.ensureLeader()) {
                delay(every)
                continue
            }
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
        if (!gate.isLeader) return // another instance runs the loops and will plan on its next pass
        jobs?.launch {
            try { planDrafts() } catch (e: CancellationException) { throw e } catch (e: Exception) { log.error("Planification en échec", e) }
        }
    }
}
