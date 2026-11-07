package lab.doppio.bloom.core.model

import kotlinx.serialization.Serializable

/** What one discovery run found. Stored as the payload of the DISCOVERY_RUN event. */
@Serializable
data class DiscoveryReport(
    val newAccounts: Int = 0,
    val refreshedAccounts: Int = 0,
    val excluded: Int = 0,
    val offersRead: Int = 0,
    val appsChecked: Int = 0,
    val notes: List<String> = emptyList(),
    /** New accounts per source label, in the order the sources ran. */
    val bySource: Map<String, Int> = emptyMap(),
) {
    operator fun plus(other: DiscoveryReport) = DiscoveryReport(
        newAccounts + other.newAccounts, refreshedAccounts + other.refreshedAccounts, excluded + other.excluded,
        offersRead + other.offersRead, appsChecked + other.appsChecked, notes + other.notes, bySource + other.bySource,
    )
}
