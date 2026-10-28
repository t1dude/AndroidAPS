package app.aaps.core.nssdk.remotemodel

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive

/**
 * Reads a `Long` the way Gson did: a number written as a whole decimal is accepted too.
 * Writing is not changed.
 *
 * Nightscout runs on JavaScript, where every number is a double. A `Long` that AAPS uploads comes
 * back rounded, and for the biggest values the rounded number no longer fits in a `Long`.
 * The real case: an open-ended running mode is uploaded with `originalDuration = Long.MAX_VALUE`
 * and comes back as `9223372036854776000`. Strict kotlinx throws on that, and on the REST load one
 * such record made the whole page of 500 treatments unreadable.
 *
 * The rule is copied from Gson's `JsonReader.nextLong`: parse as a double, convert to `Long`, and
 * accept it only when converting back gives the same double. So `30.0` reads as 30, and
 * `9223372036854776000` reads as [Long.MAX_VALUE], which is what was meant. A fraction such as
 * `29.5` still throws, as it did with Gson.
 */
internal object TolerantLongSerializer : KSerializer<Long> {

    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("app.aaps.TolerantLong", PrimitiveKind.LONG)

    override fun serialize(encoder: Encoder, value: Long) = encoder.encodeLong(value)

    override fun deserialize(decoder: Decoder): Long {
        if (decoder !is JsonDecoder) return decoder.decodeLong()
        val element = decoder.decodeJsonElement()
        val text = (element as? JsonPrimitive)?.content ?: throw SerializationException("Expected a number, got $element")
        text.toLongOrNull()?.let { return it }
        val asDouble = text.toDoubleOrNull() ?: throw SerializationException("Failed to parse '$text' as a long value")
        val asLong = asDouble.toLong()
        if (asLong.toDouble() != asDouble) throw SerializationException("Failed to parse '$text' as a long value")
        return asLong
    }
}
