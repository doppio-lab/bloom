package lab.doppio.bloom.core.model

/** How long stored data is kept before the GDPR purge or the freshness filters let it go. */
object Retention {
    /** Contacts and officers with no activity for this long are purged (CNIL recommendation). */
    const val CONTACT_YEARS = 3L

    private const val DAYS_PER_YEAR = 365L

    /** [CONTACT_YEARS] in days, counting plain 365-day years. */
    const val CONTACT_DAYS = CONTACT_YEARS * DAYS_PER_YEAR

    /** Client demands (tenders, Codeur projects) are dropped after this long. */
    const val DEMAND_DAYS = 90L

    /** Facts older than this are no longer used to write or to explain. */
    const val FACT_DAYS = 120L

    /** Signals older than this no longer count in the score (the slowest half-life is 120 days). */
    const val SIGNAL_DAYS = 180L

    /** The text of a reply is only needed to classify it: after this long only its intent and summary stay. */
    const val REPLY_BODY_DAYS = 365L

    /** Discovery keys checked longer ago than the longest recheck window are never consulted again. */
    const val SEEN_KEY_DAYS = 365L
}
