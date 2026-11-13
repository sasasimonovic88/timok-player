package rs.timok.player.data.remote

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// Lenient accessors for Xtream JSON, where any field may be a string, a number, an array or missing.

internal fun JsonElement?.obj(): JsonObject? = this as? JsonObject
internal fun JsonElement?.arr(): JsonArray? = this as? JsonArray

/** A field as trimmed, non-empty text; arrays yield their first element. */
internal fun JsonObject.str(vararg keys: String): String? {
    for (k in keys) {
        val v = when (val e = this[k]) {
            null, is JsonNull -> null
            is JsonPrimitive -> e.content
            is JsonArray -> (e.firstOrNull() as? JsonPrimitive)?.content
            else -> null
        }?.trim()
        if (!v.isNullOrEmpty() && v != "null") return v
    }
    return null
}

internal fun JsonObject.long(vararg keys: String): Long? = str(*keys)?.toDoubleOrNull()?.toLong()
internal fun JsonObject.int(vararg keys: String): Int? = str(*keys)?.toDoubleOrNull()?.toInt()

/** Image URLs must be absolute http(s); panels often send "" or garbage. */
internal fun JsonObject.image(vararg keys: String): String? = str(*keys)?.takeIf { it.startsWith("http") }
