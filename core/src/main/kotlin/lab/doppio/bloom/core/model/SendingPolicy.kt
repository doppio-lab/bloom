package lab.doppio.bloom.core.model

import kotlinx.serialization.Serializable

/** Fenêtre d'envoi et plafonds, configurables. Valeurs par défaut prudentes pour la délivrabilité. */
@Serializable
data class SendingPolicy(
    val timezone: String = BloomTime.PARIS_ID,
    val days: Set<Int> = setOf(2, 3, 4), // mardi, mercredi, jeudi (ISO : lundi = 1)
    val startHour: Int = 8,
    val startMinute: Int = 30,
    val endHour: Int = 11,
    val endMinute: Int = 30,
    val maxPerMailboxPerDay: Int = DEFAULT_MAX_PER_MAILBOX_PER_DAY,
    val minSecondsBetweenSends: Int = 180,
    val holidays: Set<String> = emptySet(),
) {
    companion object {
        const val DEFAULT_MAX_PER_MAILBOX_PER_DAY = 25
    }
}
