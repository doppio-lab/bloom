package lab.doppio.bloom.data.discovery

import lab.doppio.bloom.core.model.Demand
import lab.doppio.bloom.core.port.DiscoveryStateRepository
import lab.doppio.bloom.data.sources.discovery.CodeurSource

internal class CodeurDemandPoller(
    private val codeur: CodeurSource?,
    private val discoveryState: DiscoveryStateRepository,
) {
    /**
     * Reads the Codeur feed and keeps the new mobile projects. The feed only holds the latest projects,
     * so this is also called between two nightly runs. Returns how many were new.
     */
    suspend fun poll(): Int {
        val source = codeur ?: return 0
        return source.mobileProjects().count { p ->
            discoveryState.saveDemand(Demand(SeenKeys.codeur(p.id), DiscoveryNotes.DEMANDS_LABEL, p.title, p.url, p.budget, p.categories, p.publishedAt?.toString()))
        }
    }
}
