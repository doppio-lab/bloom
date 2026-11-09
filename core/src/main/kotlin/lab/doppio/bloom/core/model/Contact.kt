package lab.doppio.bloom.core.model

import kotlinx.serialization.Serializable

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
