package lab.doppio.bloom.data.discovery

/** Limits and lists that tune one discovery run. */
data class DiscoveryConfig(
    val jobKeywords: List<String> = listOf(
        "développeur iOS", "développeur Android", "développeur mobile", "Swift iOS", "Kotlin Android", "Kotlin Multiplatform",
    ),
    /** Fenêtre de publication des offres lues (1, 3, 7, 14 ou 31 jours). */
    val jobsPublishedWithinDays: Int = DEFAULT_JOBS_WITHIN_DAYS,
    val chartGenres: List<Int> = listOf(
        6015, 6024, 6023, 6003, 6010, 6013, 6020, 6000, 6007, 6011, 6012, 6004,
        // News, education, entertainment, utilities, social, reference, weather, books, photo
        6009, 6017, 6016, 6002, 6005, 6006, 6001, 6018, 6008,
    ),
    /** Charts read for each genre: the free apps and the apps that earn the most (100 apps each). */
    val chartKinds: List<String> = listOf("topfreeapplications", "topgrossingapplications"),
    /** Apps analysées par nuit (environ 5 secondes chacune avec la limite de débit Apple). */
    val appsPerRun: Int = DEFAULT_APPS_PER_RUN,
    /** Une app déjà examinée n'est revue qu'après ce délai. */
    val recheckAfterDays: Long = DEFAULT_RECHECK_DAYS,
    /** Noms à ne jamais prospecter en plus de la liste intégrée (clients actuels, partenaires, concurrents). */
    val extraExclusions: Set<String> = emptySet(),
    /** Leads handled per added source and per run: each one costs registry and sometimes App Store calls. */
    val leadsPerSource: Int = DEFAULT_LEADS_PER_SOURCE,
    /** Company sites searched for a job board per run. Boards already known are always read. */
    val jobBoardLookupsPerRun: Int = DEFAULT_JOB_BOARD_LOOKUPS,
    /** Accounts whose legal officers are read from the registry per run, for the contact book. */
    val officerLookupsPerRun: Int = DEFAULT_OFFICER_LOOKUPS,
) {
    companion object {
        const val DEFAULT_JOBS_WITHIN_DAYS = 7

        /** The only windows the France Travail API accepts for "publieeDepuis". */
        val ALLOWED_JOBS_WITHIN_DAYS = setOf(1, 3, 7, 14, 31)
        const val DEFAULT_APPS_PER_RUN = 400
        const val DEFAULT_RECHECK_DAYS = 30L
        const val DEFAULT_LEADS_PER_SOURCE = 60
        const val DEFAULT_JOB_BOARD_LOOKUPS = 30
        const val DEFAULT_OFFICER_LOOKUPS = 150
    }
}
