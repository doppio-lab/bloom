package lab.doppio.bloom.core.model

import kotlinx.serialization.Serializable

/** A request published by a buyer on a marketplace: answered on the platform, never by email. */
@Serializable
data class Demand(
    val key: String,
    val source: String,
    val title: String,
    val url: String,
    val budget: String? = null,
    val categories: List<String> = emptyList(),
    val publishedAt: String? = null,
)
