package lab.doppio.bloom.core.model

/** Read sizes and truncation lengths shared by several callers. */
object Limits {
    /** "Every account": the largest ranked list any use case asks for. */
    const val ALL_ACCOUNTS = 500

    /** Accounts shown on the overview screen. */
    const val OVERVIEW_ACCOUNTS = 50

    /** Accounts scanned to detect duplicates during a CSV import. */
    const val IMPORT_KNOWN_ACCOUNTS = 5000

    /** Accounts the contact book lists. */
    const val CONTACT_BOOK_ENTRIES = 150

    /** Facts kept per account in the people-to-complete list. */
    const val FACTS_PER_ACCOUNT_TO_COMPLETE = 3

    /** Longest excerpt stored with a fact. */
    const val FACT_EXCERPT_CHARS = 500

    /** Longest error text stored for a job run, an agent run or a send failure. */
    const val ERROR_TEXT_CHARS = 500

    /** Longest agent run error text (stack traces are long). */
    const val AGENT_RUN_ERROR_CHARS = 2000

    /** Length of a fact id (hex characters of the hash). */
    const val FACT_ID_CHARS = 8

    /** Window of the positive-reply rate by signal (overview and coach tool). */
    const val REPLY_RATE_DAYS = 90L

    /** Window of the weekly counters (discoveries, agent costs). */
    const val WEEK_DAYS = 7L
}
