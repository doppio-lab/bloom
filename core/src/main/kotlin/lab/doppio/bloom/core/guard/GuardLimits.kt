package lab.doppio.bloom.core.guard

/** What MessageGuard enforces, and what the writer prompt tells the model, from the same values. */
object GuardLimits {
    /** First email of a sequence: a cold message must stay short. */
    const val FIRST_EMAIL_WORDS = 120

    /** Follow-up emails are shorter than the first one. */
    const val FOLLOW_UP_WORDS = 80

    /** A LinkedIn connection note is capped by LinkedIn at 300 characters. */
    const val LINKEDIN_CHARS = 300

    /** Subject target: 2 to 6 words. Above [SUBJECT_WARN_ABOVE] the guard warns. */
    const val SUBJECT_MIN_WORDS = 2
    const val SUBJECT_MAX_WORDS = 6
    const val SUBJECT_WARN_ABOVE = 7

    /** One message, one question: the guard warns above this. */
    const val MAX_QUESTIONS = 2

    /** Numbers always allowed in a message: offer durations and small everyday integers. */
    val ALWAYS_ALLOWED_NUMBERS: Set<String> = setOf("1", "2", "3", "15", "20", "30")
}
