package lab.doppio.bloom.data.discovery

import lab.doppio.bloom.core.port.OfficerRepository
import lab.doppio.bloom.core.port.OfficerRow
import lab.doppio.bloom.data.sources.facts.CompanyRegistrySource

internal class OfficerRefresher(
    private val officerRepo: OfficerRepository,
    private val registry: CompanyRegistrySource,
    private val config: DiscoveryConfig,
) {
    /**
     * Reads the legal officers of the accounts that have a SIREN and were never looked up. They feed the
     * contact book as suggestions: nobody is written to before a person picks them.
     */
    suspend fun refresh(): Int {
        var read = 0
        for ((accountId, siren) in officerRepo.accountsWithoutOfficers(config.officerLookupsPerRun)) {
            val officers = runCatching { registry.officers(siren) }.getOrNull() ?: continue
            officerRepo.saveOfficers(accountId, officers.map { OfficerRow(it.fullName, it.role, registry.pageUrl(siren)) })
            read++
        }
        return read
    }
}
