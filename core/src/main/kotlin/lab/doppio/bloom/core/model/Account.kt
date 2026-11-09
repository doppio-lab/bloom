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
    /** D'où vient le compte : manual, import, offres d'emploi, classement App Store, BODACC, marché public, marché attribué, presse, GitHub. */
    val source: String = "manual",
)

/** Page of the account's iOS app on the French App Store, the storefront Bloom reads. Null without a known app. */
val Account.appStoreUrl: String? get() = iosAppId?.let { "https://apps.apple.com/fr/app/id$it" }
