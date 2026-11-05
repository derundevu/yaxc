package io.github.derundevu.yaxc.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.derundevu.yaxc.Settings
import io.github.derundevu.yaxc.helper.RuntimeLogEvent
import io.github.derundevu.yaxc.helper.RuntimeLogReader
import io.github.derundevu.yaxc.presentation.logs.LogEntry
import io.github.derundevu.yaxc.presentation.logs.LogLevel
import io.github.derundevu.yaxc.presentation.logs.LogSource
import io.github.derundevu.yaxc.presentation.logs.LogStreamStatus
import io.github.derundevu.yaxc.presentation.logs.LogsUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCoroutinesApi::class)
class LogsViewModel internal constructor(
    application: Application,
    input: Flow<RuntimeLogEvent>,
    private val processingDispatcher: CoroutineDispatcher,
) : AndroidViewModel(application) {
    constructor(application: Application) : this(
        application,
        RuntimeLogReader(Settings(application)).events(),
        Dispatchers.Default,
    )

    private data class Filters(
        val query: String = "",
        val level: LogLevel? = null,
        val source: LogSource? = null,
    )

    private data class Data(
        val history: LogHistorySnapshot,
        val frozen: LogHistorySnapshot? = null,
        val status: LogStreamStatus = LogStreamStatus.Connecting,
        val error: String? = null,
        val clearGeneration: Long = 0L,
    )

    private val history = LogHistory()
    private val historyLock = Mutex()
    private val data = MutableStateFlow(Data(history.snapshot()))
    private val filters = MutableStateFlow(Filters())
    private val exportResultChannel = Channel<Boolean>(capacity = 1)
    private var pendingExport: List<LogEntry>? = null
    private var exportJob: Job? = null
    private var clearJob: Job? = null
    // Owned only by mapLatest, which joins a cancelled transform before starting the next one.
    private var filteredInput: List<LogEntry>? = null
    private var filteredBy: Filters? = null
    private var filteredEntries: List<LogEntry> = emptyList()

    val exportResults = exportResultChannel.receiveAsFlow()
    val uiState = combine(data, filters) { state, filter -> state to filter }
        .mapLatest { (state, filter) ->
            withContext(processingDispatcher) {
                val visible = state.frozen ?: state.history
                val query = filter.query
                val entries = if (visible.entries === filteredInput && filter == filteredBy) {
                    filteredEntries
                } else if (query.isEmpty() && filter.level == null && filter.source == null) {
                    visible.entries
                } else {
                    buildList {
                        visible.entries.forEachIndexed { index, entry ->
                            if (index % 64 == 0) currentCoroutineContext().ensureActive()
                            if ((filter.level == null || entry.level == filter.level) &&
                                (filter.source == null || entry.source == filter.source) &&
                                entry.containsText(query)
                            ) add(entry)
                        }
                    }
                }
                currentCoroutineContext().ensureActive()
                filteredInput = visible.entries
                filteredBy = filter
                filteredEntries = entries
                LogsUiState(
                    entries = entries,
                    query = query,
                    level = filter.level,
                    source = filter.source,
                    paused = state.frozen != null,
                    pendingCount = state.frozen?.let { state.history.received - it.received } ?: 0L,
                    totalCount = visible.entries.size,
                    droppedCount = visible.dropped,
                    status = state.status,
                    error = state.error,
                    clearGeneration = state.clearGeneration,
                )
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, LogsUiState())

    init {
        // One collector for this screen's ViewModel, including Activity recreation.
        viewModelScope.launch(processingDispatcher) {
            try {
                input.collect { event ->
                    when (event) {
                        is RuntimeLogEvent.Lines -> historyLock.withLock {
                            history.append(event.lines, event.source)
                            val snapshot = history.snapshot()
                            data.update { it.copy(history = snapshot) }
                        }
                        is RuntimeLogEvent.State -> data.update {
                            it.copy(status = event.status, error = event.error)
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                data.update { it.copy(status = LogStreamStatus.Unavailable, error = error.message) }
            }
        }
    }

    fun setQuery(query: String) = filters.update { it.copy(query = query) }
    fun setLevel(level: LogLevel?) = filters.update { it.copy(level = level) }
    fun setSource(source: LogSource?) = filters.update { it.copy(source = source) }

    fun togglePause() = data.update {
        it.copy(frozen = if (it.frozen == null) it.history else null)
    }

    fun clear() {
        if (clearJob?.isActive == true) return
        clearJob = viewModelScope.launch(processingDispatcher) {
            historyLock.withLock {
                history.clear()
                val snapshot = history.snapshot()
                data.update {
                    it.copy(
                        history = snapshot,
                        frozen = if (it.frozen == null) null else snapshot,
                        clearGeneration = it.clearGeneration + 1L,
                    )
                }
            }
        }
    }

    /** Capture exactly the displayed filtered rows, not whatever arrives while the picker is open. */
    fun prepareExport(): Boolean {
        if (pendingExport != null || exportJob?.isActive == true) return false
        val entries = uiState.value.entries
        if (entries.isEmpty()) return false
        pendingExport = entries
        return true
    }

    fun completeExport(uri: Uri?) {
        val entries = pendingExport
        pendingExport = null
        if (uri == null) return
        exportJob = viewModelScope.launch(Dispatchers.IO) {
            val success = try {
                checkNotNull(entries) { "The displayed log snapshot is no longer available" }
                val stream = checkNotNull(getApplication<Application>().contentResolver.openOutputStream(uri, "wt"))
                stream.bufferedWriter(Charsets.UTF_8).use { writer ->
                    for (entry in entries) {
                        currentCoroutineContext().ensureActive()
                        writer.append(entry.prefix).append(entry.message).append('\n')
                    }
                }
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            exportResultChannel.send(success)
        }
    }
}

/** Search original text without allocating a reconstructed raw line for every row and keystroke. */
private fun LogEntry.containsText(query: String): Boolean {
    if (query.isEmpty() || message.contains(query, ignoreCase = true) || prefix.contains(query, ignoreCase = true)) return true
    for (start in maxOf(0, prefix.length - query.length + 1) until prefix.length) {
        val prefixCount = prefix.length - start
        val messageCount = query.length - prefixCount
        if (messageCount <= message.length &&
            prefix.regionMatches(start, query, 0, prefixCount, ignoreCase = true) &&
            message.regionMatches(0, query, prefixCount, messageCount, ignoreCase = true)
        ) return true
    }
    return false
}

/** One bounded Binder payload; oversized selections must use document export, never silent truncation. */
internal fun logsClipboardText(entries: List<LogEntry>, maxChars: Int = 128 * 1024): String? {
    var size = 0
    for (entry in entries) {
        val length = entry.prefix.length + entry.message.length + 1
        if (length > maxChars - size) return null
        size += length
    }
    return buildString(size) {
        for (entry in entries) append(entry.prefix).append(entry.message).append('\n')
    }
}
