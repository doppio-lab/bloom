package lab.doppio.bloom.data.discovery

import lab.doppio.bloom.core.model.AccountId
import lab.doppio.bloom.data.sources.SourceResult
import java.time.Instant

/**
 * A company a source believes is worth a look, with the evidence it found.
 * The service decides whether it becomes an account (exclusions, registry, thresholds).
 */
data class Lead(
    val name: String,
    /** Stable key of the evidence ("bodacc:<id>"), so the same notice is not handled twice. */
    val seenKey: String,
    /** Builds the facts and signals once the account id is known. Every fact carries its source URL. */
    val build: (AccountId, Instant) -> SourceResult,
    val siren: String? = null,
    val domain: String? = null,
    /** Drop the lead when the company is not found in the French registry. */
    val requireRegistry: Boolean = true,
    /** Accept the registry match only when a single company carries that name (short or common names). */
    val requireUniqueMatch: Boolean = false,
    val minHeadcount: Int? = null,
    /** Keep only companies in a known sector or with an app on the store: filters out unrelated businesses. */
    val requireSectorOrApp: Boolean = false,
)

/** One public source of leads. Implementations do I/O; parsing lives in core so it can be tested. */
interface DiscoverySource {
    /** Short identifier used in configuration and reports ("bodacc"). */
    val key: String
    /** What is stored on the account and shown in the dashboard ("BODACC"). */
    val label: String
    /** A lead whose key was seen within this delay is skipped. */
    val recheckAfterDays: Long get() = 365
    suspend fun leads(now: Instant): List<Lead>
}
