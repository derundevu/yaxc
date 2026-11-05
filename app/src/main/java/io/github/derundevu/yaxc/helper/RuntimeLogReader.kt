package io.github.derundevu.yaxc.helper

import android.os.Process as AndroidProcess
import io.github.derundevu.yaxc.Settings
import io.github.derundevu.yaxc.presentation.logs.LogSource
import io.github.derundevu.yaxc.presentation.logs.LogStreamStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.EOFException
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes

sealed interface RuntimeLogEvent {
    data class Lines(val lines: List<String>, val source: LogSource? = null) : RuntimeLogEvent
    data class State(val status: LogStreamStatus, val error: String? = null) : RuntimeLogEvent
}

/** Owns only its logcat child or a read-only handle to the existing core log. */
class RuntimeLogReader internal constructor(
    private val rootLogFile: () -> File?,
    private val startLogcat: () -> Process,
    private val pollIntervalMillis: Long = 500L,
) {
    constructor(settings: Settings) : this(
        rootLogFile = { if (settings.transparentProxy) settings.xrayCoreLogs() else null },
        startLogcat = {
            ProcessBuilder(
                "logcat", "--uid=${AndroidProcess.myUid()}", "-v", "threadtime", "-T", "500", "*:V",
            ).redirectErrorStream(true).start()
        },
    )

    fun events(): Flow<RuntimeLogEvent> = flow {
        emit(RuntimeLogEvent.State(LogStreamStatus.Connecting))
        val file = rootLogFile()
        if (file == null) collectLogcat() else followFile(file)
    }.catch { error ->
        if (error is CancellationException) throw error
        currentCoroutineContext().ensureActive()
        emit(RuntimeLogEvent.State(LogStreamStatus.Unavailable, error.message ?: error.toString()))
    }.flowOn(Dispatchers.IO).buffer(0)

    private suspend fun FlowCollector<RuntimeLogEvent>.collectLogcat(): Unit = coroutineScope {
        val process = startLogcat()
        // A blocked pipe read does not reliably react to thread interruption. This sibling runs
        // immediately on cancellation and kills/reaps the writer before closing its streams.
        val closer = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    process.destroyForcibly()
                    process.waitFor()
                    runCatching { process.inputStream.close() }
                    runCatching { process.errorStream.close() }
                    runCatching { process.outputStream.close() }
                }
            }
        }
        try {
            val chunks = LineChunks(this@collectLogcat, null)
            val framer = LineFramer()
            val buffer = ByteArray(READ_BYTES)
            var diagnostic = ""
            emit(RuntimeLogEvent.State(LogStreamStatus.Live))
            while (true) {
                val count = runInterruptible { process.inputStream.read(buffer) }
                if (count < 0) break
                framer.accept(buffer, count) { line ->
                    diagnostic = line.takeLast(DIAGNOSTIC_CHARS)
                    chunks.add(line)
                }
                chunks.flush()
            }
            framer.finish { line ->
                diagnostic = line.takeLast(DIAGNOSTIC_CHARS)
                chunks.add(line)
            }
            chunks.flush()
            val exitCode = runInterruptible { process.waitFor() }
            throw IOException("logcat exited ($exitCode)${diagnostic.trim().let { if (it.isEmpty()) "" else ": $it" }}")
        } finally {
            withContext(NonCancellable) { closer.cancelAndJoin() }
        }
    }

    private suspend fun FlowCollector<RuntimeLogEvent>.followFile(path: File) {
        var file: RandomAccessFile? = null
        var identity: Any? = null
        var checkpoint = ByteArray(0)
        var framer = LineFramer()
        var waiting = false
        val chunks = LineChunks(this, LogSource.Xray)
        val buffer = ByteArray(READ_BYTES)
        try {
            while (currentCoroutineContext().isActive) {
                val attributes = try {
                    Files.readAttributes(path.toPath(), BasicFileAttributes::class.java)
                } catch (_: NoSuchFileException) {
                    null
                }
                if (attributes == null) {
                    file?.close()
                    file = null
                    checkpoint = ByteArray(0)
                    if (!waiting) emit(RuntimeLogEvent.State(LogStreamStatus.Waiting))
                    waiting = true
                    delay(pollIntervalMillis)
                    continue
                }
                if (!attributes.isRegularFile) throw IOException("Runtime log is not a regular file: $path")
                val opened = file
                val changed = opened != null && (
                    identity != attributes.fileKey() || attributes.size() < opened.filePointer ||
                        !checkpointMatches(opened, checkpoint)
                    )
                if (changed) {
                    opened?.close()
                    file = null
                }
                if (file == null) {
                    file = try {
                        RandomAccessFile(path, "r")
                    } catch (error: FileNotFoundException) {
                        // Rotation can remove the path between stat and open. Permission errors
                        // on an existing path remain genuine failures, not perpetual waiting.
                        if (path.exists()) throw error
                        delay(pollIntervalMillis)
                        continue
                    }
                    identity = attributes.fileKey()
                    framer = LineFramer()
                    try {
                        seekInitialTail(checkNotNull(file), framer)
                    } catch (_: EOFException) {
                        file?.close()
                        file = null
                        delay(pollIntervalMillis)
                        continue
                    }
                    waiting = false
                    emit(RuntimeLogEvent.State(LogStreamStatus.Live))
                }
                val activeFile = checkNotNull(file)
                // Snapshot the end so a continuously busy writer cannot starve cancellation/polling.
                val end = activeFile.length()
                while (activeFile.filePointer < end) {
                    currentCoroutineContext().ensureActive()
                    val count = activeFile.read(buffer, 0, minOf(buffer.size.toLong(), end - activeFile.filePointer).toInt())
                    if (count < 0) break
                    framer.accept(buffer, count, chunks::add)
                    chunks.flush()
                }
                val nextCheckpoint = checkpoint(activeFile)
                if (nextCheckpoint == null) {
                    activeFile.close()
                    file = null
                    checkpoint = ByteArray(0)
                } else {
                    checkpoint = nextCheckpoint
                }
                delay(pollIntervalMillis)
            }
        } finally {
            file?.close()
        }
    }

    private fun seekInitialTail(file: RandomAccessFile, framer: LineFramer) {
        val end = file.length()
        val start = maxOf(0L, end - INITIAL_TAIL_BYTES)
        val tail = ByteArray((end - start).toInt())
        file.seek(start)
        file.readFully(tail)
        var lines = 0
        for (index in tail.lastIndex downTo 0) {
            if (tail[index] == NEWLINE && index != tail.lastIndex && ++lines == INITIAL_TAIL_LINES) {
                file.seek(start + index + 1)
                return
            }
        }
        file.seek(start)
        if (start > 0) {
            file.seek(start - 1)
            if (file.readByte() != NEWLINE) framer.discardCurrentLine()
        }
    }

    private fun checkpoint(file: RandomAccessFile): ByteArray? {
        val position = file.filePointer
        val bytes = ByteArray(minOf(position, 64L).toInt())
        return try {
            file.seek(position - bytes.size)
            file.readFully(bytes)
            bytes
        } catch (_: EOFException) {
            // A concurrent truncate is a stream restart, not a read failure.
            null
        }
    }

    private fun checkpointMatches(file: RandomAccessFile, expected: ByteArray): Boolean {
        val position = file.filePointer
        if (expected.isEmpty()) return true
        val actual = ByteArray(expected.size)
        return try {
            file.seek(position - expected.size)
            file.readFully(actual)
            actual.contentEquals(expected)
        } catch (_: EOFException) {
            false
        } finally {
            file.seek(position)
        }
    }

    private class LineChunks(
        private val collector: FlowCollector<RuntimeLogEvent>,
        private val source: LogSource?,
    ) {
        private val lines = ArrayList<String>(MAX_CHUNK_LINES)
        private var bytes = 0

        suspend fun add(line: String) {
            val size = utf8Size(line) + 1
            if (lines.isNotEmpty() && bytes + size > MAX_CHUNK_BYTES) flush()
            lines.add(line)
            bytes += size
            if (lines.size == MAX_CHUNK_LINES) flush()
        }

        suspend fun flush() {
            if (lines.isEmpty()) return
            collector.emit(RuntimeLogEvent.Lines(lines.toList(), source))
            lines.clear()
            bytes = 0
        }
    }

    /** Byte framing preserves UTF-8 characters split over reads, without buffering huge rows. */
    private class LineFramer {
        private val bytes = ByteArray(MAX_LINE_CHARS * 4)
        private var size = 0
        private var oversized = false
        private var discard = false

        fun discardCurrentLine() { discard = true }

        suspend fun accept(input: ByteArray, count: Int, onLine: suspend (String) -> Unit) {
            for (index in 0 until count) {
                val byte = input[index]
                if (byte == NEWLINE) {
                    if (!discard) onLine(line())
                    size = 0
                    oversized = false
                    discard = false
                } else if (!discard) {
                    if (size < bytes.size) bytes[size++] = byte else oversized = true
                }
            }
        }

        suspend fun finish(onLine: suspend (String) -> Unit) {
            if (!discard && (size > 0 || oversized)) onLine(line())
        }

        private fun line(): String {
            val length = if (size > 0 && bytes[size - 1] == '\r'.code.toByte()) size - 1 else size
            val text = String(bytes, 0, length, Charsets.UTF_8)
            if (!oversized && text.length <= MAX_LINE_CHARS) return text
            var end = minOf(text.length, MAX_LINE_CHARS)
            if (end > 0 && text[end - 1].isHighSurrogate()) end--
            return text.substring(0, end) + TRUNCATION_SUFFIX
        }
    }

    companion object {
        private const val READ_BYTES = 8192
        private const val MAX_LINE_CHARS = 16_384
        private const val MAX_CHUNK_LINES = 64
        private const val MAX_CHUNK_BYTES = 256 * 1024
        private const val INITIAL_TAIL_BYTES = 256L * 1024
        private const val INITIAL_TAIL_LINES = 500
        private const val DIAGNOSTIC_CHARS = 4096
        private const val TRUNCATION_SUFFIX = " … [line truncated]"
        private const val NEWLINE: Byte = 10

        private fun utf8Size(text: String): Int {
            var bytes = 0
            var index = 0
            while (index < text.length) {
                val char = text[index++]
                bytes += when {
                    char.code < 0x80 -> 1
                    char.code < 0x800 -> 2
                    char.isHighSurrogate() && index < text.length && text[index].isLowSurrogate() -> {
                        index++
                        4
                    }
                    char.isSurrogate() -> 1
                    else -> 3
                }
            }
            return bytes
        }
    }
}
