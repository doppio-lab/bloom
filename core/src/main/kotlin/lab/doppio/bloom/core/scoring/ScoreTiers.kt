package lab.doppio.bloom.core.scoring

/** Action thresholds on the 0..100 score. Below [NURTURE] an account is watched, from [PRIORITY] we write this week. */
object ScoreTiers {
    /** From this score on, the account is worth a message this week. */
    const val PRIORITY = 65

    /** From this score on, the account is followed (research, contact book, people to complete). */
    const val NURTURE = 45
}
