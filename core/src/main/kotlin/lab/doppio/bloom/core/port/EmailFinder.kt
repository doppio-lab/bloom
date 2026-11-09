package lab.doppio.bloom.core.port

/** Finds the professional email of a person from a name and a company. Optional and paid. */
interface EmailFinder {
    data class Found(val email: String, val qualification: String)

    suspend fun findEmail(fullName: String, company: String, website: String?): Found?
}
