package lab.doppio.bloom.core.discovery

import java.time.Instant
import java.time.OffsetDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

// Shared JSON helpers for the pure parsers of the public sources read by discovery. No I/O here.

private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }

internal fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
internal fun JsonElement?.obj(): JsonObject? = this as? JsonObject

/** Some open data fields hold a JSON document serialized as a string. */
internal fun JsonElement?.embedded(): JsonObject? = when (this) {
    is JsonObject -> this
    is JsonPrimitive -> contentOrNull?.let { runCatching { lenient.parseToJsonElement(it).obj() }.getOrNull() }
    else -> null
}

internal fun instant(value: String?): Instant? = value?.let {
    runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() ?: runCatching { Instant.parse(it) }.getOrNull()
}
