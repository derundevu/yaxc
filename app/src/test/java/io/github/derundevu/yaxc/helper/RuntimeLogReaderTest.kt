package io.github.derundevu.yaxc.helper

import io.github.derundevu.yaxc.presentation.logs.LogSource
import io.github.derundevu.yaxc.presentation.logs.LogStreamStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class RuntimeLogReaderTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun missingFileWaitsThenFollowsOnlyTheExistingFile() = runBlocking {
        val file = File(temporary.root, "core.log")
        withEvents(fileReader(file)) { events ->
            events.awaitState(LogStreamStatus.Waiting)
            file.writeText("native warning\n")
            val lines = events.nextLines()
            assertEquals(LogSource.Xray, lines.source)
            assertEquals(listOf("native warning"), lines.lines)
            file.appendText("next\n")
            assertEquals(listOf("next"), events.nextLines().lines)
        }
        // Closing the follower neither removes nor clears the runtime's file.
        assertEquals("native warning\nnext\n", file.readText())
    }

    @Test
    fun initialTailIsBoundedAndUnicodeSurvivesReadBoundaries() = runBlocking {
        val file = temporary.newFile()
        file.writeText((0 until 900).joinToString("") { "old-$it\n" })
        withEvents(fileReader(file)) { events ->
            val initial = ArrayList<String>()
            while (initial.size < 500) initial.addAll(events.nextLines().lines)
            assertEquals((400 until 900).map { "old-$it" }, initial)
            // The first byte of the four-byte emoji lands at the end of an 8192-byte read.
            val split = "a".repeat(8191) + "😀"
            file.appendBytes((split + "\r\nlast\n").toByteArray(Charsets.UTF_8))
            val appended = ArrayList<String>()
            while (appended.size < 2) appended.addAll(events.nextLines().lines)
            assertEquals(listOf(split, "last"), appended)
        }
    }

    @Test
    fun initialTailSkipsClippedPhysicalLineRatherThanInventingARow() = runBlocking {
        val file = temporary.newFile()
        file.writeText("x".repeat(300_000) + "\nkept\n")
        withEvents(fileReader(file)) { events ->
            assertEquals(listOf("kept"), events.nextLines().lines)
        }
    }

    @Test
    fun oversizedLinesAreTruncatedOnceAndTheFollowingRowIsRetained() = runBlocking {
        val file = temporary.newFile()
        val prefix = "a".repeat(16_383)
        file.writeText(prefix + "😀" + "z".repeat(100_000) + "\nnext\n")
        withEvents(fileReader(file)) { events ->
            val lines = ArrayList<String>()
            while (lines.size < 2) lines.addAll(events.nextLines().lines)
            assertEquals("next", lines[1])
            assertTrue(lines.first().startsWith(prefix))
            assertTrue(lines.first().length < 16_450)
            assertFalse(lines.first().contains('\uFFFD'))
        }
    }

    @Test
    fun fileTruncateSameLengthRewriteReplacementAndReappearanceRestartTheFollower() = runBlocking {
        val file = temporary.newFile()
        file.writeText("alpha\n")
        withEvents(fileReader(file)) { events ->
            assertEquals(listOf("alpha"), events.nextLines().lines)
            // A same-length rewrite must not be mistaken for an idle append-only file.
            file.writeText("bravo\n")
            assertEquals(listOf("bravo"), events.nextLines().lines)
            file.writeText("c\n")
            assertEquals(listOf("c"), events.nextLines().lines)
            val replacement = temporary.newFile()
            replacement.writeText("replacement\n")
            Files.move(replacement.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            assertEquals(listOf("replacement"), events.nextLines().lines)
            assertTrue(file.delete())
            events.awaitState(LogStreamStatus.Waiting)
            file.writeText("returned\n")
            assertEquals(listOf("returned"), events.nextLines().lines)
        }
    }

    @Test
    fun chunksRespectBothLimitsWithoutDroppingRowsUnderBackpressure() = runBlocking {
        val file = temporary.newFile()
        withEvents(fileReader(file)) { events ->
            events.awaitState(LogStreamStatus.Live)
            val expected = (0 until 1000).map { "row-$it" } + List(20) { "Ж".repeat(16_384) }
            file.appendText(expected.joinToString("\n", postfix = "\n"))
            val actual = ArrayList<String>()
            while (actual.size < expected.size) {
                val chunk = events.nextLines()
                assertTrue(chunk.lines.size <= 64)
                assertTrue(chunk.lines.sumOf { it.toByteArray(Charsets.UTF_8).size + 1 } <= 256 * 1024)
                actual.addAll(chunk.lines)
            }
            assertEquals(expected, actual)
        }
    }

    @Test
    fun unterminatedUtf8IsHeldUntilThePhysicalLineCompletes() = runBlocking {
        val file = temporary.newFile()
        val emoji = "😀".toByteArray(Charsets.UTF_8)
        file.writeBytes("prefix ".toByteArray() + emoji.copyOfRange(0, 2))
        withEvents(fileReader(file)) { events ->
            events.awaitState(LogStreamStatus.Live)
            file.appendBytes(emoji.copyOfRange(2, 4) + " suffix\n".toByteArray())
            assertEquals(listOf("prefix 😀 suffix"), events.nextLines().lines)
        }
    }

    @Test
    fun processRowsFlushAtEofAndUnexpectedExitHasUsefulDiagnostic() = runBlocking {
        val owned = AtomicReference<Process>()
        val reader = processReader(owned, "printf 'first\\n'; printf '\\360\\237'; read gate; printf '\\230\\200\\nlast'; exit 7")
        withEvents(reader) { events ->
            assertEquals(listOf("first"), events.nextLines().lines)
            val child = checkNotNull(owned.get())
            child.outputStream.write('\n'.code)
            child.outputStream.flush()
            val rows = ArrayList<String>()
            var failure: RuntimeLogEvent.State? = null
            while (failure == null) {
                when (val event = events.receive()) {
                    is RuntimeLogEvent.Lines -> {
                        assertEquals(null, event.source)
                        rows.addAll(event.lines)
                    }
                    is RuntimeLogEvent.State -> if (event.status == LogStreamStatus.Unavailable) failure = event
                }
            }
            assertEquals(listOf("😀", "last"), rows)
            assertTrue(checkNotNull(failure.error).contains("last"))
        }
        assertFalse(checkNotNull(owned.get()).isAlive)
    }

    @Test
    fun cancellationUnblocksSilentProcessReadAndReapsOwnedChild() = runBlocking {
        val owned = AtomicReference<Process>()
        val secondRead = CountDownLatch(1)
        val reader = RuntimeLogReader(
            rootLogFile = { null },
            startLogcat = {
                val child = ProcessBuilder("sh", "-c", "printf 'ready\\n'; read gate")
                    .redirectErrorStream(true).start().also(owned::set)
                ReadObservedProcess(child, secondRead)
            },
        )
        withEvents(reader) { events ->
            assertEquals(listOf("ready"), events.nextLines().lines)
            withContext(Dispatchers.IO) { assertTrue(secondRead.await(5L, TimeUnit.SECONDS)) }
            assertTrue(checkNotNull(owned.get()).isAlive)
            // There is no further stdout and stdin remains open. Cancelling must not need EOF.
        }
        val child = checkNotNull(owned.get())
        assertFalse(child.isAlive)
    }

    @Test
    fun mergedErrorFloodCannotDeadlockAndExitDiagnosticIsBounded() = runBlocking {
        val owned = AtomicReference<Process>()
        val script = "i=0; while [ \"\$i\" -lt 3000 ]; do printf 'stderr-%s\\n' \"\$i\" >&2; i=\$((i+1)); done; exit 23"
        var count = 0
        withEvents(processReader(owned, script)) { events ->
            while (true) {
                when (val event = events.receive()) {
                    is RuntimeLogEvent.Lines -> count += event.lines.size
                    is RuntimeLogEvent.State -> if (event.status == LogStreamStatus.Unavailable) {
                        assertTrue(checkNotNull(event.error).contains("stderr-2999"))
                        assertTrue(checkNotNull(event.error).length < 4200)
                        break
                    }
                }
            }
        }
        assertEquals(3000, count)
        assertFalse(checkNotNull(owned.get()).isAlive)
    }

    @Test
    fun launchFailuresAndNonRegularRootFilesAreUnavailableNotWaiting() = runBlocking {
        val failedProcess = RuntimeLogReader(
            rootLogFile = { null },
            startLogcat = { throw IOException("controlled spawn failure") },
        )
        withEvents(failedProcess) { events ->
            val failure = events.awaitState(LogStreamStatus.Unavailable)
            assertEquals("controlled spawn failure", failure.error)
        }
        withEvents(fileReader(temporary.newFolder())) { events ->
            events.awaitState(LogStreamStatus.Unavailable)
        }
    }

    private fun fileReader(file: File) = RuntimeLogReader(
        rootLogFile = { file },
        startLogcat = { throw AssertionError("File mode must not launch any process") },
        pollIntervalMillis = 10L,
    )

    private fun processReader(owned: AtomicReference<Process>, script: String) = RuntimeLogReader(
        rootLogFile = { null },
        startLogcat = {
            ProcessBuilder("sh", "-c", script).redirectErrorStream(true).start().also(owned::set)
        },
    )

    /** Decorates real pipe reads only to synchronize cancellation with the next blocking read. */
    private class ReadObservedProcess(
        private val child: Process,
        private val secondRead: CountDownLatch,
    ) : Process() {
        private val stdout = object : FilterInputStream(child.inputStream) {
            private var reads = 0

            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                if (++reads == 2) secondRead.countDown()
                return super.read(bytes, offset, length)
            }
        }

        override fun getInputStream() = stdout
        override fun getErrorStream() = child.errorStream
        override fun getOutputStream() = child.outputStream
        override fun waitFor() = child.waitFor()
        override fun exitValue() = child.exitValue()
        override fun destroy() = child.destroy()
        override fun destroyForcibly(): Process {
            child.destroyForcibly()
            return this
        }
    }

    private suspend fun withEvents(
        reader: RuntimeLogReader,
        block: suspend (ReceiveChannel<RuntimeLogEvent>) -> Unit,
    ) = coroutineScope {
        val events = Channel<RuntimeLogEvent>(8)
        val collector = launch { reader.events().collect { events.send(it) } }
        try {
            withTimeout(10_000L) { block(events) }
        } finally {
            withTimeout(5_000L) { collector.cancelAndJoin() }
            events.cancel()
        }
    }

    private suspend fun ReceiveChannel<RuntimeLogEvent>.awaitState(status: LogStreamStatus): RuntimeLogEvent.State {
        while (true) {
            val event = receive()
            if (event is RuntimeLogEvent.State) {
                if (event.status == status) return event
                if (event.status == LogStreamStatus.Unavailable) throw AssertionError(event.error)
            }
        }
    }

    private suspend fun ReceiveChannel<RuntimeLogEvent>.nextLines(): RuntimeLogEvent.Lines {
        while (true) {
            when (val event = receive()) {
                is RuntimeLogEvent.Lines -> return event
                is RuntimeLogEvent.State -> if (event.status == LogStreamStatus.Unavailable) throw AssertionError(event.error)
            }
        }
    }
}
