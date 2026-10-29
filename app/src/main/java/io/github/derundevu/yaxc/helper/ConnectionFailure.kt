package io.github.derundevu.yaxc.helper

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.StandardOpenOption

enum class GeodataProblem { Missing, Invalid, MissingGroup, Unreadable }

data class GeodataFailure(
    val filename: String,
    val problem: GeodataProblem,
    val group: String? = null,
)

// Identity distinguishes separate failures with the same diagnostic in stale UI callbacks.
class ConnectionFailure(val diagnostic: String, val geodata: GeodataFailure? = null) {
    companion object {
        /** May inspect a local database to disambiguate native EOF; call on IO. */
        fun fromDiagnostic(diagnostic: String, assetsDir: File): ConnectionFailure =
            ConnectionFailure(diagnostic, classifyGeodata(diagnostic, assetsDir))
    }
}

/** Process-local: service teardown and Activity recreation do not dismiss a failure. */
object ConnectionFailureState {
    private val current = MutableStateFlow<ConnectionFailure?>(null)
    val failure: StateFlow<ConnectionFailure?> = current.asStateFlow()

    fun report(failure: ConnectionFailure) {
        current.value = failure
    }

    fun clear() {
        current.value = null
    }

    fun clear(expected: ConnectionFailure): Boolean = current.compareAndSet(expected, null)
}

private val geodataContext = Regex(
    """(?:^|>|[\r\n])\s*common/geodata:\s*([^>\r\n]+)""",
    RegexOption.IGNORE_CASE,
)
private val openContext = Regex("""failed to (?:open|load)\s+(.+)""", RegexOption.IGNORE_CASE)
private val codeContext = Regex(
    """failed to (?:check|load) code\s+(.+?)\s+from\s+(.+)""",
    RegexOption.IGNORE_CASE,
)
private val unmarshalContext = Regex(
    """(?:error unmarshal (?:IP|Site) in|failed to unmarshal(?: (?:IP|Site))?(?: in)?)\s+([^:]+)(?::(.+))?""",
    RegexOption.IGNORE_CASE,
)
private val permissionFailure = Regex(
    """permission denied|operation not permitted|access (?:is )?denied|\b(?:EACCES|EPERM)\b|(?:not|is) a directory|input/output error|i/o error""",
    RegexOption.IGNORE_CASE,
)
private val absentFailure = Regex(
    """no such file or directory|file does not exist|cannot find the file|\bENOENT\b""",
    RegexOption.IGNORE_CASE,
)
private val invalidFailure = Regex(
    """unexpected EOF|invalid body length|varint overflow|\bproto:|invalid (?:wire|field|tag)|invalid UTF-8""",
    RegexOption.IGNORE_CASE,
)

private fun classifyGeodata(diagnostic: String, assetsDir: File): GeodataFailure? {
    for (context in geodataContext.findAll(diagnostic)) {
        val message = context.groupValues[1].trim()
        val code = codeContext.matchEntire(message)
        val unmarshal = unmarshalContext.matchEntire(message)
        val filename = when {
            code != null -> code.groupValues[2]
            unmarshal != null -> unmarshal.groupValues[1]
            else -> openContext.matchEntire(message)?.groupValues?.get(1) ?: continue
        }.unquote()
        // Do not turn an ext database (including a path with this basename) into a standard download.
        if (filename != "geoip.dat" && filename != "geosite.dat") return null
        val group = (code?.groupValues?.get(1) ?: unmarshal?.groupValues?.get(2))
            ?.unquote()?.takeIf { it.isNotEmpty() }
        val cause = diagnostic.substring(context.range.last + 1).substringBefore('\n').trim()
            .removePrefix(">").trim()
        val problem = when {
            permissionFailure.containsMatchIn(cause) -> GeodataProblem.Unreadable
            absentFailure.containsMatchIn(cause) -> GeodataProblem.Missing
            unmarshal != null || invalidFailure.containsMatchIn(cause) -> GeodataProblem.Invalid
            code != null && cause.equals("EOF", ignoreCase = true) -> inspectEntryFraming(File(assetsDir, filename))
            else -> GeodataProblem.Unreadable
        }
        return GeodataFailure(filename, problem, group)
    }
    return null
}

private fun String.unquote(): String {
    val text = trim()
    return if (text.length >= 2 && (text.first() == '"' || text.first() == '\'') &&
        text.last() == text.first()
    ) text.substring(1, text.length - 1) else text
}

/**
 * geodat.proto lists are repeated field-1 length-delimited entries. The native seek loop can
 * return plain EOF both between entries and partway through a tag/length/body. Check only this
 * framing, not nested domains/CIDRs: their decoding errors are explicit unmarshal diagnostics.
 * A fixed header buffer and seeking over bodies avoid loading a database into memory.
 */
private fun inspectEntryFraming(file: File): GeodataProblem = try {
    Files.newByteChannel(file.toPath(), StandardOpenOption.READ).use { channel ->
        val size = channel.size()
        if (size == 0L) return@use GeodataProblem.Invalid
        val header = ByteBuffer.allocate(11) // One tag and at most ten varint bytes.
        var position = 0L
        while (position < size) {
            channel.position(position)
            header.clear()
            header.limit(minOf(header.capacity().toLong(), size - position).toInt())
            while (header.hasRemaining()) {
                when (channel.read(header)) {
                    -1 -> return@use GeodataProblem.Invalid
                    0 -> return@use GeodataProblem.Unreadable
                }
            }
            header.flip()
            if (header.get().toInt() != 0x0a) return@use GeodataProblem.Invalid
            val length = readEntryLength(header) ?: return@use GeodataProblem.Invalid
            val bodyPosition = position + header.position()
            if (length == 0L || length > size - bodyPosition) return@use GeodataProblem.Invalid
            position = bodyPosition + length
        }
        if (channel.size() == size) GeodataProblem.MissingGroup else GeodataProblem.Unreadable
    }
} catch (_: NoSuchFileException) {
    GeodataProblem.Missing
} catch (_: IOException) {
    GeodataProblem.Unreadable
} catch (_: SecurityException) {
    GeodataProblem.Unreadable
}

private fun readEntryLength(header: ByteBuffer): Long? {
    var length = 0L
    for (shift in 0..63 step 7) {
        if (!header.hasRemaining()) return null
        val byte = header.get().toInt() and 0xff
        // A database cannot contain an entry larger than Long.MAX_VALUE, nor an overflowing varint.
        if (shift == 63) return null
        length = length or ((byte and 0x7f).toLong() shl shift)
        if (byte and 0x80 == 0) return length
    }
    return null
}
