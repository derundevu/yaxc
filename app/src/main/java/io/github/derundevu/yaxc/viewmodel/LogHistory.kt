package io.github.derundevu.yaxc.viewmodel

import io.github.derundevu.yaxc.presentation.logs.LogEntry
import io.github.derundevu.yaxc.presentation.logs.LogLevel
import io.github.derundevu.yaxc.presentation.logs.LogSource
import java.util.ArrayDeque
import java.util.Collections

data class LogHistorySnapshot(
    val entries: List<LogEntry>,
    val bytes: Int,
    val dropped: Long,
    val received: Long,
)

/** Serialized by its owner; byte accounting includes one UTF-8 newline per physical row. */
class LogHistory(
    private val maxEntries: Int = 5_000,
    private val maxBytes: Int = 4 * 1024 * 1024,
) {
    init {
        require(maxEntries > 0)
        require(maxBytes > 0)
    }

    private data class StoredEntry(val entry: LogEntry, val bytes: Int)
    private data class BoundedLine(val text: String, val bytes: Int)

    private val entries = ArrayDeque<StoredEntry>()
    private var bytes = 0
    private var dropped = 0L
    private var received = 0L

    fun append(lines: List<String>, source: LogSource? = null) {
        for (line in lines) {
            val id = ++received
            val bounded = bound(line)
            if (bounded == null) {
                // A one-byte budget can hold an empty row, but no visible truncation marker.
                dropped++
                continue
            }
            val rowBytes = bounded.bytes + 1
            while (entries.isNotEmpty() &&
                (entries.size >= maxEntries || bytes > maxBytes - rowBytes)
            ) {
                bytes -= entries.removeFirst().bytes
                dropped++
            }
            entries.addLast(StoredEntry(parse(id, bounded.text, source), rowBytes))
            bytes += rowBytes
        }
    }

    fun clear() {
        entries.clear()
        bytes = 0
        dropped = 0L
        // received also supplies stable IDs, and must outlive clear for pause accounting.
    }

    fun snapshot(): LogHistorySnapshot = LogHistorySnapshot(
        entries = Collections.unmodifiableList(entries.map { it.entry }),
        bytes = bytes,
        dropped = dropped,
        received = received,
    )

    private fun bound(line: String): BoundedLine? {
        val budget = maxBytes - 1
        var end = 0
        var size = 0
        while (end < line.length && end < MAX_LINE_CHARS) {
            val width = utf8Width(line, end)
            val chars = if (width == 4) 2 else 1
            if (end + chars > MAX_LINE_CHARS || width > budget - size) break
            size += width
            end += chars
        }
        if (end == line.length) return BoundedLine(line, size)
        if (budget == 0) return null

        // Keep a visible marker even for deliberately tiny history budgets.
        val suffix = if (budget >= TRUNCATION_SUFFIX.length) TRUNCATION_SUFFIX else "~"
        while (size > budget - suffix.length) {
            end--
            if (Character.isLowSurrogate(line[end]) && end > 0 &&
                Character.isHighSurrogate(line[end - 1])
            ) {
                end--
            }
            size -= utf8Width(line, end)
        }
        return BoundedLine(line.substring(0, end) + suffix, size + suffix.length)
    }

    private fun parse(id: Long, line: String, hint: LogSource?): LogEntry {
        val android = THREADTIME.matchAt(line, 0) ?: TIME.matchAt(line, 0)
        var end = android?.range?.last?.plus(1) ?: 0
        var timestamp = android?.groups?.get(1)?.value
        val tag = android?.groups?.get(3)?.value?.trim()
        var level = android?.groups?.get(2)?.value?.let(::androidLevel) ?: LogLevel.Unknown
        var source = hint ?: sourceFor(tag)

        // Native priorities are carried inside Android's GoLog I rows. Only parse a
        // leading native header, never severity-looking words in an arbitrary body.
        if (android == null || source == LogSource.Xray) {
            val nativeTime = NATIVE_TIME.matchAt(line, end)
            val nativeStart = nativeTime?.range?.last?.plus(1) ?: end
            val nativeLevel = NATIVE_LEVEL.matchAt(line, nativeStart)
            if (nativeTime != null || nativeLevel != null) {
                if (timestamp == null) timestamp = nativeTime?.groups?.get(1)?.value
                if (nativeLevel != null) level = namedLevel(nativeLevel.groupValues[1])
                end = nativeLevel?.range?.last?.plus(1) ?: nativeStart
                if (hint == null && android == null) source = LogSource.Xray
            }
        }
        return LogEntry(
            id = id,
            timestamp = timestamp,
            level = level,
            source = source,
            tag = tag,
            message = if (end == 0) line else line.substring(end),
            prefix = if (end == 0) "" else line.substring(0, end),
        )
    }

    private fun sourceFor(tag: String?): LogSource = when {
        tag.equals("GoLog", ignoreCase = true) || tag.equals("Xray", ignoreCase = true) -> LogSource.Xray
        tag.equals("TProxyService", ignoreCase = true) -> LogSource.Vpn
        tag != null && (
            PING_WORKER.matches(tag) || tag.equals("HEV", ignoreCase = true) ||
                tag.equals("hev-socks5-tunnel", ignoreCase = true) ||
                tag.equals("tun2socks", ignoreCase = true) ||
                tag.equals("XrayHelper", ignoreCase = true)
            ) -> LogSource.Helper
        else -> LogSource.App
    }

    private fun androidLevel(priority: String): LogLevel = when (priority) {
        "V" -> LogLevel.Verbose
        "D" -> LogLevel.Debug
        "I" -> LogLevel.Info
        "W" -> LogLevel.Warning
        "E", "F", "A" -> LogLevel.Error
        else -> LogLevel.Unknown
    }

    private fun namedLevel(name: String): LogLevel = when {
        name.equals("Verbose", ignoreCase = true) -> LogLevel.Verbose
        name.equals("Debug", ignoreCase = true) -> LogLevel.Debug
        name.equals("Info", ignoreCase = true) -> LogLevel.Info
        name.equals("Warning", ignoreCase = true) -> LogLevel.Warning
        else -> LogLevel.Error // The header regex only additionally admits Error and Fatal.
    }

    /** Matches the JVM UTF-8 encoder, including its '?' replacement for unpaired surrogates. */
    private fun utf8Width(text: String, index: Int): Int {
        val char = text[index]
        return when {
            char.code <= 0x7f -> 1
            char.code <= 0x7ff -> 2
            Character.isHighSurrogate(char) && index + 1 < text.length &&
                Character.isLowSurrogate(text[index + 1]) -> 4
            Character.isSurrogate(char) -> 1
            else -> 3
        }
    }

    private companion object {
        const val MAX_LINE_CHARS = 16_384
        const val TRUNCATION_SUFFIX = " [truncated]"
        const val MONTH_DAY = "(?:0[1-9]|1[0-2])-(?:0[1-9]|[12]\\d|3[01])"
        const val CLOCK = "(?:[01]\\d|2[0-3]):[0-5]\\d:[0-5]\\d"
        const val ANDROID_TIMESTAMP = "($MONTH_DAY $CLOCK\\.\\d{3,9})"
        val THREADTIME = Regex(
            "$ANDROID_TIMESTAMP[ \\t]+\\d+[ \\t]+\\d+[ \\t]+([VDIWEFAS])[ \\t]+([^:\\s][^:\\r\\n]*?):[ \\t]?",
        )
        val TIME = Regex(
            "$ANDROID_TIMESTAMP[ \\t]+([VDIWEFAS])/([^(:\\s][^(:\\r\\n]*?)\\([ \\t]*\\d+\\):[ \\t]?",
        )
        val NATIVE_TIME = Regex(
            "((?:\\d{4}-$MONTH_DAY|\\d{4}/(?:0[1-9]|1[0-2])/(?:0[1-9]|[12]\\d|3[01]))[ T]$CLOCK(?:\\.\\d{1,9})?(?:Z|[+-](?:[01]\\d|2[0-3]):[0-5]\\d)?)[ \\t]+",
        )
        val NATIVE_LEVEL = Regex("\\[(Verbose|Debug|Info|Warning|Error|Fatal)](?:[ \\t]+|$)", RegexOption.IGNORE_CASE)
        val PING_WORKER = Regex("PingWorker(?:[1-5](?:Service)?)?", RegexOption.IGNORE_CASE)
    }
}
