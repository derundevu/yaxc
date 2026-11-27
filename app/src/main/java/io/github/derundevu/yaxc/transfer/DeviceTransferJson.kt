package io.github.derundevu.yaxc.transfer

import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.StringReader
import java.math.BigDecimal

internal object DeviceTransferJson {
    const val MAX_PAYLOAD_BYTES = DeviceTransferTransport.MAX_PAYLOAD_BYTES
    const val MAX_GROUPS = 256
    const val MAX_PROFILES = 8_192
    const val MAX_ROUTES = 4_096
    const val MAX_NAME = 1_024
    const val MAX_TEXT = 8_192
    const val MAX_CONFIG = 512 * 1024

    fun checkSize(raw: String, limit: Int = MAX_PAYLOAD_BYTES) {
        require(raw.length <= limit) { "Transfer data exceeds the size limit." }
        var bytes = 0
        var i = 0
        while (i < raw.length) {
            val c = raw[i++]
            bytes += when {
                c < '\u0080' -> 1
                c < '\u0800' -> 2
                c.isHighSurrogate() -> {
                    require(i < raw.length && raw[i++].isLowSurrogate()) { "Invalid Unicode in transfer data." }
                    4
                }
                c.isLowSurrogate() -> throw IllegalArgumentException("Invalid Unicode in transfer data.")
                else -> 3
            }
            require(bytes <= limit) { "Transfer data exceeds the size limit." }
        }
    }

    fun parse(raw: String, limit: Int = MAX_PAYLOAD_BYTES): Any {
        checkSize(raw, limit)
        return try {
            JsonReader(StringReader(raw)).use { reader ->
                reader.isLenient = false
                val value = readValue(reader, 0)
                require(reader.peek() == JsonToken.END_DOCUMENT) { "Extra content after transfer JSON." }
                value
            }
        } catch (_: IOException) {
            throw IllegalArgumentException("Invalid transfer JSON.")
        }
    }

    private fun readValue(reader: JsonReader, depth: Int): Any {
        require(depth <= 64) { "Transfer JSON nesting is too deep." }
        return when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> JSONObject().apply {
                reader.beginObject()
                var fields = 0
                while (reader.hasNext()) {
                    require(++fields <= MAX_PROFILES) { "Too many JSON fields." }
                    val key = reader.nextName()
                    require(!has(key)) { "Duplicate JSON field." }
                    put(key, readValue(reader, depth + 1))
                }
                reader.endObject()
            }
            JsonToken.BEGIN_ARRAY -> JSONArray().apply {
                reader.beginArray()
                while (reader.hasNext()) {
                    require(length() < MAX_PROFILES) { "Too many JSON entries." }
                    put(readValue(reader, depth + 1))
                }
                reader.endArray()
            }
            JsonToken.STRING -> reader.nextString()
            JsonToken.BOOLEAN -> reader.nextBoolean()
            JsonToken.NULL -> { reader.nextNull(); JSONObject.NULL }
            JsonToken.NUMBER -> reader.nextString().let { raw ->
                require(raw.length <= 128) { "Transfer JSON number is too long." }
                raw.toLongOrNull() ?: BigDecimal(raw).also {
                    require(it.scale() in -1_000..1_000 && it.toDouble().isFinite()) { "JSON number is out of range." }
                }
            }
            else -> throw IllegalArgumentException("Expected a JSON value.")
        }
    }

    /** Object key order and numeric spelling are not profile identity. */
    fun canonical(value: Any): String = buildString { appendCanonical(value) }

    private fun StringBuilder.appendCanonical(value: Any) {
        when (value) {
            is JSONObject -> {
                append('{')
                value.keys().asSequence().sorted().forEachIndexed { index, key ->
                    if (index > 0) append(',')
                    append(JSONObject.quote(key)).append(':')
                    appendCanonical(value.get(key))
                }
                append('}')
            }
            is JSONArray -> {
                append('[')
                repeat(value.length()) { index ->
                    if (index > 0) append(',')
                    appendCanonical(value.get(index))
                }
                append(']')
            }
            is BigDecimal -> append(value.stripTrailingZeros())
            is Long -> append(BigDecimal.valueOf(value).stripTrailingZeros())
            is String -> append(JSONObject.quote(value))
            else -> append(value)
        }
    }
}

internal fun Any.transferObject(label: String): JSONObject = this as? JSONObject
    ?: throw IllegalArgumentException("$label must be an object.")

internal fun Any.transferArray(label: String, max: Int): JSONArray = (this as? JSONArray)
    ?.takeIf { it.length() <= max } ?: throw IllegalArgumentException("$label must be an array with at most $max entries.")

internal fun Any.transferString(label: String, max: Int): String = (this as? String)
    ?.also { DeviceTransferJson.checkSize(it, max) } ?: throw IllegalArgumentException("$label must be text.")

internal fun Any.transferInt(label: String, range: IntRange): Int {
    val value = when (this) {
        is Long -> this
        is Int -> toLong()
        else -> null
    }
    require(value != null && value in range.first.toLong()..range.last.toLong()) { "$label is not an integer in range." }
    return value.toInt()
}

internal fun JSONObject.transferValue(key: String): Any = opt(key)
    ?: throw IllegalArgumentException("Missing transfer field '$key'.")

internal fun JSONObject.transferText(key: String, max: Int = DeviceTransferJson.MAX_TEXT): String =
    transferValue(key).transferString(key, max)

internal fun JSONObject.transferBoolean(key: String): Boolean = transferValue(key) as? Boolean
    ?: throw IllegalArgumentException("$key must be a boolean.")

internal fun JSONObject.transferKeys(vararg required: String, optional: Set<String> = emptySet()) {
    require(required.all(::has) && keys().asSequence().all { it in required || it in optional }) {
        "Missing or unknown fields in transfer data."
    }
}

internal inline fun <T> JSONArray.mapTransfer(transform: (Any) -> T): List<T> = List(length()) { transform(get(it)) }
