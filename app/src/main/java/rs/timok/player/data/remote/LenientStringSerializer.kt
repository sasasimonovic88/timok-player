package rs.timok.player.data.remote

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * Xtream panels are inconsistent: the same field can arrive as a number, a string,
 * a boolean or null depending on the panel version. This reads any primitive as a String.
 */
object LenientStringSerializer : KSerializer<String?> {
    override val descriptor = PrimitiveSerialDescriptor("LenientString", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String? {
        val json = decoder as? JsonDecoder ?: return decoder.decodeString()
        return when (val e = json.decodeJsonElement()) {
            is JsonNull -> null
            is JsonPrimitive -> e.content
            else -> null
        }
    }

    override fun serialize(encoder: Encoder, value: String?) {
        if (value == null) encoder.encodeString("") else encoder.encodeString(value)
    }
}
