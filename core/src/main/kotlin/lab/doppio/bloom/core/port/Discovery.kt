package lab.doppio.bloom.core.port

import lab.doppio.bloom.core.model.DiscoveryReport

/** Finds new accounts from the public sources. Implemented in data by the discovery service. */
interface Discovery {
    suspend fun run(): DiscoveryReport

    /** Reads the marketplace feed (Codeur) and keeps the new demands. Returns how many were new. */
    suspend fun pollDemands(): Int
}

/** Finds the professional email of a person from a name and a company. Optional and paid. */
interface EmailFinder {
    data class Found(val email: String, val qualification: String)

    suspend fun findEmail(fullName: String, company: String, website: String?): Found?
}
