package lab.doppio.bloom.core.discovery

import java.net.URLEncoder
import kotlinx.serialization.Serializable
import lab.doppio.bloom.core.model.Offer
import lab.doppio.bloom.core.model.Persona

@Serializable
data class TargetPersona(val roles: List<String>, val why: String)

object Targeting {
    fun personaFor(offer: Offer): TargetPersona = when (offer) {
        Offer.DISCOVERY -> TargetPersona(listOf("Head of Mobile", "Lead mobile", "CTO"), "celui qui subit le problème technique de l'app")
        Offer.REGIE -> TargetPersona(listOf("Engineering Manager", "CTO", "Head of Mobile"), "celui qui porte le recrutement en retard")
        Offer.FORFAIT -> TargetPersona(listOf("CTO", "CPO", "CEO"), "celui qui arbitre le budget du nouveau produit")
        Offer.TRAINING_KMP -> TargetPersona(listOf("Head of Mobile", "Engineering Manager", "Lead Android"), "celui qui fait monter l'équipe en compétence")
        Offer.TRAINING_ANTHROPIC -> TargetPersona(listOf("CTO", "Head of AI", "Engineering Manager"), "celui qui pilote les projets LLM")
    }

    fun personaFromRole(role: String): Persona {
        val r = role.lowercase()
        return when {
            Regex("\\bcto\\b|chief technology|directeur technique|vp eng").containsMatchIn(r) -> Persona.CTO
            Regex("head of mobile|lead mobile|responsable mobile|mobile lead|lead (ios|android)").containsMatchIn(r) -> Persona.HEAD_OF_MOBILE
            Regex("engineering manager|manager .*ing|responsable .*dév").containsMatchIn(r) -> Persona.ENGINEERING_MANAGER
            Regex("\\bceo\\b|fondat|founder|président|directeur général").containsMatchIn(r) -> Persona.CEO_FOUNDER
            Regex("\\bcpo\\b|product|produit").containsMatchIn(r) -> Persona.PRODUCT_LEAD
            else -> Persona.OTHER
        }
    }

    /** Recherche LinkedIn que la personne ouvre elle-même : aucun accès automatisé à LinkedIn. */
    private const val LINKEDIN_PEOPLE_SEARCH_URL = "https://www.linkedin.com/search/results/people/?keywords="

    fun linkedinSearchUrl(role: String, company: String): String =
        LINKEDIN_PEOPLE_SEARCH_URL + URLEncoder.encode("$role $company", Charsets.UTF_8)
}
