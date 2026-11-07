package lab.doppio.bloom.data.persistence

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

internal val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
private val stringList = ListSerializer(String.serializer())

/** Lists of strings are stored as JSON text columns. */
internal fun List<String>.encode() = json.encodeToString(stringList, this)
internal fun String.decodeList(): List<String> = json.decodeFromString(stringList, this)
