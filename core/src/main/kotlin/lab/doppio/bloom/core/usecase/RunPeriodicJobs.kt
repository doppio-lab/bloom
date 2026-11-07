package lab.doppio.bloom.core.usecase

import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonPrimitive
import lab.doppio.bloom.core.model.EventType
import lab.doppio.bloom.core.model.GdprPurgePayload
import lab.doppio.bloom.core.model.SendingPolicy
import lab.doppio.bloom.core.port.Coach
import lab.doppio.bloom.core.port.ContactRepository
import lab.doppio.bloom.core.port.Discovery
import lab.doppio.bloom.core.port.DiscoveryStateRepository
import lab.doppio.bloom.core.port.EventRepository
import lab.doppio.bloom.core.port.JobRunRepository
import lab.doppio.bloom.core.port.OfficerRepository
import org.slf4j.LoggerFactory

/** The periodic jobs. [key] is what the job_runs table stores, so it never changes. */
enum class JobName(val key: String) {
    DISCOVERY("discovery"),
    RESEARCH("research"),
    COACH("coach"),
    GDPR_PURGE("gdpr-purge"),
}

/**
 * Tâches périodiques, réservées en base pour leur période : un redémarrage ne les relance pas et ne les
 * oublie pas. Chacune tourne à part, une découverte longue ne fait plus sauter la recherche ni la purge.
 *  - découverte : chaque nuit entre 2h et 5h, suivie de la recherche ;
 *  - recherche (agent IA) : une fois par jour, à partir de 5h si la découverte ne l'a pas lancée ;
 *  - note du coach : une fois par semaine, à partir du lundi 7h ;
 *  - purge RGPD : une fois par mois, à partir du 1er à 4h.
 */
class RunPeriodicJobs(
    private val jobRunRepo: JobRunRepository,
    private val discovery: Discovery,
    private val runDiscovery: RunDiscovery,
    private val researchBatch: ResearchBatch,
    private val coach: Coach,
    private val eventRepo: EventRepository,
    private val contactRepo: ContactRepository,
    private val officerRepo: OfficerRepository,
    private val discoveryState: DiscoveryStateRepository,
    policy: SendingPolicy,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val zone = ZoneId.of(policy.timezone)

    /**
     * Claims the jobs due at [now] and hands each one to [spawn], so a long job does not hold up the caller.
     * The default runs them one after the other, in place.
     */
    suspend operator fun invoke(
        now: ZonedDateTime = ZonedDateTime.now(clock.withZone(zone)),
        spawn: suspend (suspend () -> Unit) -> Unit = { it() },
    ) {
        val today = now.toLocalDate()
        // Every tick: the Codeur feed only keeps the latest projects, a nightly read would miss most of them
        runCatching { discovery.pollDemands() }.onFailure {
            if (it is CancellationException) throw it
            log.warn("Lecture de Codeur en échec", it)
        }

        if (now.hour in 2..4) job(JobName.DISCOVERY, today, spawn) {
            runDiscovery()
            if (jobRunRepo.claimJob(JobName.RESEARCH.key, today)) run(JobName.RESEARCH) { researchBatch() }
        }
        if (now.hour >= 5) job(JobName.RESEARCH, today, spawn) { researchBatch() }

        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        if (today != monday || now.hour >= 7) job(JobName.COACH, monday, spawn) {
            val memo = coach.weeklyMemo()
            eventRepo.logEvent(EventType.WEEKLY_MEMO, payload = JsonPrimitive(memo).toString())
        }

        val firstOfMonth = today.withDayOfMonth(1)
        if (today != firstOfMonth || now.hour >= 4) job(JobName.GDPR_PURGE, firstOfMonth, spawn) {
            val purged = contactRepo.purgeStaleContacts()
            val officers = officerRepo.purgeStaleOfficers()
            val demands = discoveryState.purgeOldDemands()
            eventRepo.logEvent(EventType.GDPR_PURGE, payload = GdprPurgePayload(purged, officers, demands).toJson())
        }
    }

    private suspend fun job(name: JobName, period: LocalDate, spawn: suspend (suspend () -> Unit) -> Unit, block: suspend () -> Unit) {
        if (!jobRunRepo.claimJob(name.key, period)) return
        spawn { run(name, block) }
    }

    private suspend fun run(name: JobName, block: suspend () -> Unit) {
        try {
            block()
            jobRunRepo.finishJob(name.key)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("Tâche ${name.key} en échec", e)
            jobRunRepo.finishJob(name.key, e.message ?: e.javaClass.simpleName)
        }
    }
}
