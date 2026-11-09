package lab.doppio.bloom.data.discovery

import java.time.Instant
import java.time.temporal.ChronoUnit
import lab.doppio.bloom.core.model.DiscoveryReport
import lab.doppio.bloom.core.model.Limits
import lab.doppio.bloom.core.port.AccountRepository
import lab.doppio.bloom.core.port.DiscoveryStateRepository
import lab.doppio.bloom.data.sources.facts.JobBoardSource

internal class JobBoardRefresher(
    private val accountRepo: AccountRepository,
    private val discoveryState: DiscoveryStateRepository,
    private val ingest: AccountIngest,
    private val config: DiscoveryConfig,
) {
    /** Finds the hosted job board of known accounts (once a month) and reads their mobile postings (every run). */
    suspend fun refresh(boards: JobBoardSource, now: Instant): DiscoveryReport {
        val stale = now.minus(JobBoardSource.REFRESH_AFTER_DAYS, ChronoUnit.DAYS)
        var lookups = 0; var read = 0; var withMobile = 0
        for (row in accountRepo.accountsByScore(Limits.ALL_ACCOUNTS)) {
            val domain = row.account.domain ?: continue
            val id = row.account.id
            val known = discoveryState.jobBoard(id)
            val ref = if (known == null || known.second.isBefore(stale)) {
                if (lookups++ >= config.jobBoardLookupsPerRun) continue
                boards.find(domain).also { discoveryState.saveJobBoard(id, it) }
            } else known.first
            if (ref == null) continue
            read++
            val result = boards.result(id, boards.postings(ref), now)
            if (result.facts.isNotEmpty()) { ingest.persist(id, result, now); withMobile++ }
        }
        return DiscoveryReport(refreshedAccounts = withMobile, notes = listOf(DiscoveryNotes.jobBoardsRead(read, withMobile)))
    }
}
