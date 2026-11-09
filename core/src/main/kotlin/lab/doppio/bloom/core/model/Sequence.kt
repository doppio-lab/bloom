package lab.doppio.bloom.core.model

import kotlinx.serialization.Serializable

/** Les offres de Doppio, telles que vendues sur doppio-lab.com. */
@Serializable
enum class Offer(val label: String) {
    DISCOVERY("Audit Discovery 2 jours"),
    FORFAIT("Projet au forfait"),
    REGIE("Senior en régie"),
    TRAINING_KMP("Formation Kotlin Multiplatform 3 jours"),
    TRAINING_ANTHROPIC("Formation écosystème Anthropic 2 jours"),
}

@Serializable
enum class Channel { EMAIL, LINKEDIN, PHONE }

@Serializable
data class SequenceStep(
    val index: Int,
    val channel: Channel,
    /** Délai en jours ouvrés après l'étape précédente. */
    val delayBusinessDays: Int,
    /** Consigne donnée à l'agent rédacteur pour cette étape. */
    val intent: String,
    /** Les étapes LinkedIn et téléphone sont toujours des tâches humaines. */
    val requiresApproval: Boolean = true,
)

@Serializable
data class Sequence(
    val id: Long = 0,
    val name: String,
    val offer: Offer,
    val steps: List<SequenceStep>,
)

@Serializable
enum class EnrollmentStatus { ACTIVE, PAUSED, REPLIED, COMPLETED, STOPPED_BOUNCE, STOPPED_UNSUBSCRIBE, STOPPED_MANUAL }

@Serializable
data class Enrollment(
    val id: Long = 0,
    val contactId: ContactId,
    val sequenceId: Long,
    val status: EnrollmentStatus,
    /** Index de la prochaine étape à exécuter. */
    val nextStep: Int,
    val lastActionAt: String?,
    val enrolledAt: String,
)
