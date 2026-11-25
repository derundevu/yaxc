package io.github.derundevu.yaxc.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import io.github.derundevu.yaxc.Settings
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

    private val preferences
        get() = RuntimeEnvironment.getApplication().getSharedPreferences("settings", Context.MODE_PRIVATE)

    @After fun closeModel() = store.clear()

    @Test
    fun missingSelectionsHideHistoryUntilBothFiltersHaveASelection() = runTest(mainDispatcher.dispatcher) {
        val model = model()
        assertTrue(model.uiState.value.selectedLevels.isEmpty())
        assertTrue(model.uiState.value.selectedSources.isEmpty())
        runCurrent()
        input.emit(RuntimeLogEvent.Lines(listOf(line("retained"))))
        runCurrent()
        assertTrue(model.uiState.value.entries.isEmpty())
        assertEquals(1, model.uiState.value.totalCount)
        assertFalse(preferences.contains("logsSelectedLevels"))
        assertFalse(preferences.contains("logsSelectedSources"))

        model.toggleSource(LogSource.Vpn)
        runCurrent()
        assertTrue(model.uiState.value.entries.isEmpty())
        assertFalse(preferences.contains("logsSelectedLevels"))
        model.toggleLevel(LogLevel.Info)
        runCurrent()
        assertEquals("retained", model.uiState.value.entries.single().message)
        model.toggleSource(LogSource.Vpn)
        runCurrent()
        assertTrue(model.uiState.value.entries.isEmpty())
        assertEquals(setOf(LogLevel.Info), model.uiState.value.selectedLevels)
        assertEquals(1, model.uiState.value.totalCount)
    }

    @Test
    fun persistedPartialAndEmptySelectionsAreRestoredBeforeAnyProcessing() = runTest(mainDispatcher.dispatcher) {
        preferences.edit {
            putStringSet("logsSelectedLevels", setOf("info", "future-level"))
            putStringSet("logsSelectedSources", setOf("vpn", "future-source"))
        }
        val model = model()
        assertEquals(setOf(LogLevel.Info), model.uiState.value.selectedLevels)
        assertEquals(setOf(LogSource.Vpn), model.uiState.value.selectedSources)
        assertTrue(model.uiState.value.entries.isEmpty())

        // A touch before any background filtering must still toggle the restored option.
        model.toggleLevel(LogLevel.Info)
        model.toggleSource(LogSource.App)
        model.toggleSource(LogSource.App)
        model.setQuery("not persisted")
        model.togglePause()
        runCurrent()
        assertTrue(model.uiState.value.paused)
        assertEquals(emptySet<String>(), preferences.getStringSet("logsSelectedLevels", null))
        assertEquals(setOf("vpn"), preferences.getStringSet("logsSelectedSources", null))
        store.clear()

        val restored = model().uiState.value
        assertTrue(restored.selectedLevels.isEmpty())
        assertEquals(setOf(LogSource.Vpn), restored.selectedSources)
        assertTrue(restored.entries.isEmpty())
        assertFalse(restored.paused)
        assertEquals("", restored.query)
    }

    @Test
    fun selectingAllPersistsRealOptionsAndIncludesUnknownLevel() = runTest(mainDispatcher.dispatcher) {
        val model = model()
        runCurrent()
        input.emit(RuntimeLogEvent.Lines(listOf("unrecognized line", line("info row"))))
        model.selectAllLevels()
        runCurrent()
        assertEquals(LogLevel.entries.toSet(), model.uiState.value.selectedLevels)
        assertTrue(model.uiState.value.selectedSources.isEmpty())
        assertTrue(model.uiState.value.entries.isEmpty())
        model.selectAllSources()
        runCurrent()
        assertEquals(listOf(LogLevel.Unknown, LogLevel.Info), model.uiState.value.entries.map { it.level })
        assertEquals(
            setOf("verbose", "debug", "info", "warning", "error", "unknown"),
            preferences.getStringSet("logsSelectedLevels", null),
        )
        assertEquals(setOf("xray", "app", "vpn", "helper"), preferences.getStringSet("logsSelectedSources", null))

        model.toggleLevel(LogLevel.Unknown)
        runCurrent()
        assertEquals(listOf(LogLevel.Info), model.uiState.value.entries.map { it.level })
        assertEquals(LogSource.entries.toSet(), model.uiState.value.selectedSources)
        model.selectAllLevels()
        store.clear()
        val restored = model().uiState.value
        assertEquals(LogLevel.entries.toSet(), restored.selectedLevels)
        assertEquals(LogSource.entries.toSet(), restored.selectedSources)
    }

    @Test
    fun pauseFreezesDisplayWhileBoundedHistoryContinuesThenResumeUsesNewestRows() = runTest(mainDispatcher.dispatcher) {
        val model = model()
        model.selectAllLevels()
        model.selectAllSources()
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
        model.toggleLevel(LogLevel.Info)
        model.toggleSource(LogSource.Vpn)
        model.togglePause()
        runCurrent()
        val oldId = model.uiState.value.entries.single().id
        model.clear()
        runCurrent()
        val cleared = model.uiState.value
        assertTrue(cleared.paused)
        assertEquals("needle", cleared.query)
        assertEquals(setOf(LogLevel.Info), cleared.selectedLevels)
        assertEquals(setOf(LogSource.Vpn), cleared.selectedSources)
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
    fun literalCaseInsensitiveSearchIntersectsBothMultiselectSetsIncludingPrefixBoundary() = runTest(mainDispatcher.dispatcher) {
        val model = model()
        runCurrent()
        input.emit(RuntimeLogEvent.Lines(listOf(
            line("Needle VPN", 'E'),
            line("needle warning", 'W'),
            line("needle helper", 'E', "HEV"),
            line("needle info", 'I', "HEV"),
            line("needle app", 'E', "OtherTag"),
            line("different", 'E'),
        )))
        runCurrent()
        model.setQuery("NEEDLE")
        model.toggleLevel(LogLevel.Error)
        model.toggleLevel(LogLevel.Warning)
        model.toggleSource(LogSource.Vpn)
        model.toggleSource(LogSource.Helper)
        runCurrent()
        assertEquals(listOf("Needle VPN", "needle warning", "needle helper"), model.uiState.value.entries.map { it.message })
        assertEquals(6, model.uiState.value.totalCount)
        model.setQuery(": Needle VPN")
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
        model.selectAllLevels()
        model.selectAllSources()
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
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val application: Application = RuntimeEnvironment.getApplication()
            return LogsViewModel(application, Settings(application), input, mainDispatcher.dispatcher) as T
        }
    })[LogsViewModel::class.java]

    private fun line(message: String, level: Char = 'I', tag: String = "TProxyService") =
        "11-06 10:11:12.123   101   102 $level $tag: $message"
}
