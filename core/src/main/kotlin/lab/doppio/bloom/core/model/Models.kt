package lab.doppio.bloom.core.model

import kotlinx.serialization.Serializable

/**
 * Modèle de domaine de Doppio Bloom.
 *
 * Principe directeur : aucune affirmation sur un prospect n'existe sans [Fact] sourcé.
 * Les agents ne créent pas de faits, ils les sélectionnent dans un registre alimenté
 * par des outils déterministes (API App Store, France Travail, annuaire des entreprises...).
 */

typealias AccountId = Long
typealias ContactId = Long
typealias FactId = String

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
enum class Sector {
    BANK_INSURANCE, RETAIL, LOGISTICS, GAMING, MUSIC_MEDIA, DATING_SOCIAL, IOT_HARDWARE,
    PRODUCTIVITY_SAAS, CONSUMER_ASSOCIATION, PRIVACY, MOBILITY_TRANSPORT, HEALTH, OTHER,
}

@Serializable
enum class Market { FR, EU, UK, US, OTHER }

@Serializable
data class Account(
    val id: AccountId = 0,
    val name: String,
    /** Inconnu pour une entreprise découverte via une offre d'emploi sans site ni app identifiés. */
    val domain: String? = null,
    val siren: String? = null,
    val sector: Sector = Sector.OTHER,
    val market: Market = Market.FR,
    val headcount: Int? = null,
    val iosAppId: Long? = null,
    val androidPackage: String? = null,
    /** Lien chaud : ancien client, alumni d'un client, recommandation. Pèse lourd dans le score. */
    val warmPath: String? = null,
    val ownerNote: String? = null,
    /** D'où vient le compte : manual, import, offres d'emploi, classement App Store, BODACC, marché public, presse, GitHub. */
    val source: String = "manual",
)

@Serializable
enum class Persona { CTO, HEAD_OF_MOBILE, PRODUCT_LEAD, CEO_FOUNDER, ENGINEERING_MANAGER, OTHER }

@Serializable
data class Contact(
    val id: ContactId = 0,
    val accountId: AccountId,
    val fullName: String,
    val role: String,
    val persona: Persona,
    val email: String? = null,
    val linkedinUrl: String? = null,
    /** Source de la donnée personnelle (obligation d'information RGPD art. 14). */
    val dataSource: String,
    val language: String = "fr",
)

/** Catégories de signaux d'intention. Chacune a sa demi-vie (voir scoring). */
@Serializable
enum class SignalType {
    /** Note App Store en baisse sur 90 jours. */
    RATING_DROP,
    /** Avis récents qui parlent de crash, lenteur, bug, connexion. */
    QUALITY_COMPLAINTS,
    /** Pas de mise à jour depuis longtemps : app à l'abandon ou équipe partie. */
    RELEASE_STALL,
    /** Offre d'emploi iOS / Android / mobile ouverte. */
    HIRING_MOBILE,
    /** Offre d'emploi qui mentionne Kotlin Multiplatform ou Compose Multiplatform. */
    HIRING_KMP,
    /** Offre d'emploi ouverte depuis plus de 45 jours : le recrutement coince. */
    HIRING_STUCK,
    /** Levée de fonds ou augmentation de capital. */
    FUNDING,
    /** Nouveau dirigeant tech ou produit. */
    NEW_TECH_LEADER,
    /** Annonce d'un nouveau produit mobile ou d'une refonte. */
    PRODUCT_LAUNCH,
    /** L'équipe construit avec des LLM (offres d'emploi, blog). */
    AI_ADOPTION,
    /** Interaction avec le contenu LinkedIn de Doppio (saisie manuelle). */
    CONTENT_ENGAGEMENT,
    /** Recommandation ou lien chaud (saisie manuelle). */
    REFERRAL,
    /** Open public tender that asks for a mobile app (BOAMP). */
    PUBLIC_TENDER,
    /** The organisation maintains Kotlin Multiplatform code in public (GitHub). */
    KMP_ADOPTION,
}

@Serializable
data class Signal(
    val accountId: AccountId,
    val type: SignalType,
    /** 0.0 à 1.0, calculé par la source (ex. ampleur de la baisse de note). */
    val strength: Double,
    val observedAt: String,
    val factIds: List<FactId>,
)

@Serializable
enum class FactKind { APP_METADATA, APP_REVIEW, JOB_POSTING, COMPANY_REGISTRY, WEB_PAGE, MANUAL, LEGAL_NOTICE, PUBLIC_TENDER, PRESS, CODE_REPOSITORY }

/**
 * Un fait vérifiable, toujours accompagné de sa source.
 * L'id est stable (hash de la source + énoncé) pour être cité par les agents sous la forme [F:id].
 */
@Serializable
data class Fact(
    val id: FactId,
    val accountId: AccountId,
    val kind: FactKind,
    val statement: String,
    val sourceUrl: String,
    val retrievedAt: String,
    /** Extrait brut (avis client, ligne d'offre d'emploi) pour relecture humaine. */
    val excerpt: String? = null,
)

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

@Serializable
enum class DraftStatus {
    PENDING_REVIEW, APPROVED,
    /** Claimed by the sender: SMTP is being called. Never picked again, even after a crash. */
    SENDING,
    REJECTED, SENT, TASK_DONE,
    /** Gave up after repeated SMTP errors, or interrupted while sending: a human checks the sent folder. */
    FAILED,
}

@Serializable
data class Draft(
    val id: Long = 0,
    val enrollmentId: Long,
    val stepIndex: Int,
    val channel: Channel,
    val subject: String?,
    val body: String,
    val citedFactIds: List<FactId>,
    val referenceUsed: String?,
    val rationale: String,
    val status: DraftStatus,
    val guardReport: List<String> = emptyList(),
)

@Serializable
enum class ReplyIntent {
    INTERESTED, MEETING_REQUEST, NOT_NOW, WRONG_PERSON, REFERRAL, NOT_INTERESTED, UNSUBSCRIBE, OUT_OF_OFFICE, BOUNCE, OTHER
}

@Serializable
enum class DealStage { QUALIFIED, MEETING, DISCOVERY_SOLD, PROPOSAL, WON, LOST }

@Serializable
data class Deal(
    val id: Long = 0,
    val accountId: AccountId,
    val offer: Offer,
    val stage: DealStage,
    val amountEur: Long,
    val sourceSignal: SignalType?,
    val expectedClose: String?,
)

/** Fenêtre d'envoi et plafonds, configurables. Valeurs par défaut prudentes pour la délivrabilité. */
@Serializable
data class SendingPolicy(
    val timezone: String = "Europe/Paris",
    val days: Set<Int> = setOf(2, 3, 4), // mardi, mercredi, jeudi (ISO : lundi = 1)
    val startHour: Int = 8,
    val startMinute: Int = 30,
    val endHour: Int = 11,
    val endMinute: Int = 30,
    val maxPerMailboxPerDay: Int = 25,
    val minSecondsBetweenSends: Int = 180,
    val holidays: Set<String> = emptySet(),
)
