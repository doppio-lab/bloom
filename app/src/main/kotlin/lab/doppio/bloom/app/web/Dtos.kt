package lab.doppio.bloom.app.web

import kotlinx.serialization.Serializable
import lab.doppio.bloom.core.model.Account
import lab.doppio.bloom.core.model.Contact
import lab.doppio.bloom.core.model.Dossier
import lab.doppio.bloom.core.model.Fact
import lab.doppio.bloom.core.model.Offer
import lab.doppio.bloom.core.model.Signal

/** Request and response bodies of the JSON API that no use case owns. The field names are the dashboard's contract. */
@Serializable data class AccountDetail(val account: Account, val facts: List<Fact>, val signals: List<Signal>, val dossier: Dossier?, val contacts: List<Contact>)
@Serializable data class NewAccount(val account: Account, val contacts: List<Contact> = emptyList(), val researchNow: Boolean = true)
@Serializable data class EnrollRequest(val offer: Offer? = null)
@Serializable data class DecisionRequest(val subject: String? = null, val body: String? = null)

@Serializable data class NewContactRequest(
    val fullName: String, val role: String, val email: String? = null, val linkedinUrl: String? = null,
    /** Lancer la séquence tout de suite si un email est connu (ou trouvé). */
    val enroll: Boolean = true,
    /** True when the person was picked among the legal officers published by the company registry. */
    val fromRegistry: Boolean = false,
)
@Serializable data class NewContactResult(val contactId: Long, val enrolled: Boolean, val message: String)
@Serializable data class ExcludeRequest(val reason: String)
@Serializable data class BlockRequest(val name: String, val reason: String? = null)
@Serializable data class DismissDemand(val key: String)

@Serializable
data class LastDiscovery(
    val ranAt: String? = null,
    val running: Boolean = false,
    val bySource: Map<String, Int> = emptyMap(),
    val notes: List<String> = emptyList(),
)
