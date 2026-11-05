package io.github.derundevu.yaxc.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import io.github.derundevu.yaxc.helper.RuntimeLogEvent
import io.github.derundevu.yaxc.presentation.logs.LogEntry
import io.github.derundevu.yaxc.presentation.logs.LogLevel
import io.github.derundevu.yaxc.presentation.logs.LogSource
import io.github.derundevu.yaxc.presentation.logs.LogStreamStatus
import io.github.derundevu.yaxc.testing.MainDispatcherRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class LogsViewModelTest {
    @get:Rule val mainDispatcher = MainDispatcherRule()
    private val store = ViewModelStore()
    private val input = MutableSharedFlow<RuntimeLogEvent>(extraBufferCapacity = 4)

    @After fun closeModel() = store.clear()

    @Test
    fun pauseFreezesDisplayWhileBoundedHistoryContinuesThenResumeUsesNewestRows() = runTest(mainDispatcher.dispatcher) {
        val model = model()
        runCurrent()
        input.emit(RuntimeLogEvent.Lines(listOf(line("before-pause"))))
        runCurrent()
        model.togglePause()
        runCurrent()
        val frozen = model.uiState.value.entries
        input.emit(RuntimeLogEvent.Lines((1..6000).map { line("after-$it") }))
        runCurrent()
        assertTrue(model.uiState.value.paused)
        assertEquals(frozen, model.uiState.value.entries)
        assertEquals(6000L, model.uiState.value.pendingCount)
        model.togglePause()
        runCurrent()
        val resumed = model.uiState.value
        assertFalse(resumed.paused)
        assertEquals(0L, resumed.pendingCount)
        assertEquals(5000, resumed.totalCount)
        assertEquals(1001L, resumed.droppedCount)
        assertEquals("after-1001", resumed.entries.first().message)
        assertEquals("after-6000", resumed.entries.last().message)
        assertTrue(resumed.entries.first().id > frozen.last().id)
    }

    @Test
    fun clearingPausedViewKeepsFiltersAndOnlyNewRecordsAppearAfterResume() = runTest(mainDispatcher.dispatcher) {
        val model = model()
        runCurrent()
        input.emit(RuntimeLogEvent.State(LogStreamStatus.Live))
        input.emit(RuntimeLogEvent.Lines(listOf(line("needle old"))))
        runCurrent()
        model.setQuery("needle")
        model.setSource(LogSource.Vpn)
        model.togglePause()
        runCurrent()
        val oldId = model.uiState.value.entries.single().id
        model.clear()
        runCurrent()
        val cleared = model.uiState.value
        assertTrue(cleared.paused)
        assertEquals("needle", cleared.query)
        assertEquals(LogSource.Vpn, cleared.source)
        assertEquals(LogStreamStatus.Live, cleared.status)
        assertTrue(cleared.entries.isEmpty())
        assertEquals(1L, cleared.clearGeneration)
        input.emit(RuntimeLogEvent.Lines(listOf(line("needle fresh"), line("other"))))
        runCurrent()
        assertTrue(model.uiState.value.entries.isEmpty())
        assertEquals(2L, model.uiState.value.pendingCount)
        model.togglePause()
        runCurrent()
        assertEquals(listOf("needle fresh"), model.uiState.value.entries.map { it.message })
        assertTrue(model.uiState.value.entries.single().id > oldId)
    }

    @Test
    fun literalCaseInsensitiveSearchCombinesWithExactLevelAndSourceIncludingPrefixBoundary() = runTest(mainDispatcher.dispatcher) {
        val model = model()
        runCurrent()
        input.emit(RuntimeLogEvent.Lines(listOf(
            line("Needle VPN", 'E'),
            line("needle warning", 'W'),
            line("needle app", 'E', "OtherTag"),
            line("different", 'E'),
        )))
        runCurrent()
        model.setQuery("NEEDLE")
        model.setLevel(LogLevel.Error)
        model.setSource(LogSource.Vpn)
        runCurrent()
        assertEquals(listOf("Needle VPN"), model.uiState.value.entries.map { it.message })
        assertEquals(4, model.uiState.value.totalCount)
        model.setQuery(": Needle")
        runCurrent()
        assertEquals(listOf("Needle VPN"), model.uiState.value.entries.map { it.message })
        model.setQuery("missing")
        runCurrent()
        assertTrue(model.uiState.value.entries.isEmpty())
    }

    @Test
    fun documentExportKeepsFilteredSnapshotCapturedBeforePickerDespiteNewRecordsAndFilterChanges() = runTest(mainDispatcher.dispatcher) {
        val model = model()
        runCurrent()
        input.emit(RuntimeLogEvent.Lines(listOf(line("wanted original"), line("unselected"))))
        model.setQuery("wanted")
        runCurrent()
        assertTrue(model.prepareExport())
        assertFalse(model.prepareExport())
        model.setQuery("")
        input.emit(RuntimeLogEvent.Lines(listOf(line("wanted later"))))
        runCurrent()
        val target = File.createTempFile("logs-export-", ".txt", RuntimeEnvironment.getApplication().cacheDir)
        try {
            model.completeExport(Uri.fromFile(target))
            val success = withContext(Dispatchers.IO) { withTimeout(5000) { model.exportResults.first() } }
            assertTrue(success)
            assertEquals(line("wanted original") + "\n", target.readText())
        } finally {
            target.delete()
        }
    }

    @Test
    fun clipboardSelectionAtBinderSafetyBoundaryIsCompleteAndLargerSelectionIsRejected() {
        val entry = LogEntry(1L, null, LogLevel.Unknown, LogSource.App, null, "x".repeat(128 * 1024 - 1))
        assertEquals(128 * 1024, logsClipboardText(listOf(entry))!!.length)
        assertNull(logsClipboardText(listOf(entry, entry.copy(id = 2L, message = "more"))))
    }

    private fun model(): LogsViewModel = ViewModelProvider(store, object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = LogsViewModel(
            RuntimeEnvironment.getApplication(), input, mainDispatcher.dispatcher,
        ) as T
    })[LogsViewModel::class.java]

    private fun line(message: String, level: Char = 'I', tag: String = "TProxyService") =
        "11-06 10:11:12.123   101   102 $level $tag: $message"
}
