package lab.doppio.bloom.core.model

import kotlinx.serialization.Serializable

/** What the researcher concluded about an account: the base the writer builds on. */
@Serializable
data class Dossier(
    val accountId: AccountId,
    val summary: String,
    val hypotheses: List<String>,
    val angle: String,
    /** The closest offer. When [noFit], the researcher found none that fits and this is only a fallback. */
    val offer: Offer,
    val noFit: Boolean = false,
)
