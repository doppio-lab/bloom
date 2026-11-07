package lab.doppio.bloom.data.sources.http

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** The text of a JSON primitive, or null for a missing key, a null or a structured value. */
internal fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.contentOrNull

/** The "label" of an RSS-as-JSON field such as {"im:name": {"label": "..."}}. */
internal fun JsonObject.label(key: String): String? = this[key]?.jsonObject?.get("label").str()
