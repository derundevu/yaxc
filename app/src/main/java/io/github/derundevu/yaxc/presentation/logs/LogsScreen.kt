package io.github.derundevu.yaxc.presentation.logs

import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.derundevu.yaxc.R
import io.github.derundevu.yaxc.presentation.designsystem.YaxcTheme
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcScaffold
import kotlinx.coroutines.launch

private data class LogsFilterKey(
    val query: String,
    val level: LogLevel?,
    val source: LogSource?,
    val clearGeneration: Long,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(
    state: LogsUiState,
    onBack: () -> Unit,
    onQueryChange: (String) -> Unit,
    onLevelChange: (LogLevel?) -> Unit,
    onSourceChange: (LogSource?) -> Unit,
    onTogglePause: () -> Unit,
    onClear: () -> Unit,
    onCopyEntry: (LogEntry) -> Unit,
    onCopyVisible: () -> Unit,
    onExportVisible: () -> Unit,
) {
    val spacing = YaxcTheme.spacing
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var following by rememberSaveable { mutableStateOf(true) }
    var initialPositionApplied by rememberSaveable { mutableStateOf(false) }
    var userScrolling by remember { mutableStateOf(false) }
    var automaticScrolling by remember { mutableStateOf(false) }
    var anchorId by rememberSaveable { mutableStateOf<Long?>(null) }
    var anchorOffset by rememberSaveable { mutableIntStateOf(0) }
    var entriesInitialized by remember { mutableStateOf(false) }
    val filterKey = LogsFilterKey(state.query, state.level, state.source, state.clearGeneration)
    var previousFilterKey by remember { mutableStateOf(filterKey) }
    var queryDraft by rememberSaveable { mutableStateOf(state.query) }
    var pendingQuery by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(state.query) {
        // A slower filter result must not overwrite more recently typed text.
        if (pendingQuery == null || pendingQuery == state.query) {
            queryDraft = state.query
            pendingQuery = null
        }
    }
    val updateQuery: (String) -> Unit = { query ->
        queryDraft = query
        pendingQuery = query.takeUnless { it == state.query }
        onQueryChange(query)
    }

    suspend fun scrollToLatest() {
        automaticScrolling = true
        try {
            listState.scrollToLatest(state.entries.lastIndex)
        } finally {
            automaticScrolling = false
        }
    }

    val scrollConnection = remember(listState) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y != 0f) {
                    userScrolling = true
                    following = false
                }
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow {
            Triple(
                listState.layoutInfo.visibleItemsInfo.firstOrNull()?.key as? Long,
                listState.firstVisibleItemScrollOffset,
                listState.isScrollInProgress,
            )
        }.collect { (id, offset, scrolling) ->
            if (scrolling && !automaticScrolling) {
                userScrolling = true
                following = false
            }
            if (userScrolling) {
                anchorId = id
                anchorOffset = offset
                if (!scrolling) {
                    following = !listState.canScrollForward
                    userScrolling = false
                }
            }
        }
    }
    LaunchedEffect(
        filterKey,
        state.entries.firstOrNull()?.id,
        state.entries.lastOrNull()?.id,
        state.entries.size,
    ) {
        if (!initialPositionApplied && state.entries.isNotEmpty()) {
            following = true
            initialPositionApplied = true
            scrollToLatest()
        } else if (!entriesInitialized && !following && anchorId != null && state.entries.isNotEmpty()) {
            // LazyListState saves an index, while the bounded history can evict rows during recreation.
            val index = state.entries.indexOfFirst { it.id >= anchorId!! }.coerceAtLeast(0)
            val offset = if (state.entries[index].id == anchorId) anchorOffset else 0
            listState.scrollToItem(index, offset)
        } else if (entriesInitialized) {
            if (previousFilterKey != filterKey) {
                following = true
                userScrolling = false
                anchorId = null
            }
            if (following && !userScrolling) {
                scrollToLatest()
            } else if (!userScrolling) {
                // Stable keys preserve surviving rows. If the anchor itself was evicted,
                // keep reading at the oldest remaining row instead of the old numeric index.
                val anchor = anchorId
                    ?: (listState.layoutInfo.visibleItemsInfo.firstOrNull()?.key as? Long)
                val first = state.entries.firstOrNull()?.id
                if (anchor != null && first != null && anchor < first) {
                    listState.scrollToItem(0)
                    anchorId = first
                    anchorOffset = 0
                }
            }
        }
        previousFilterKey = filterKey
        entriesInitialized = true
    }

    YaxcScaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.logs)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.logsBack))
                    }
                },
                actions = {
                    IconButton(
                        onClick = onClear,
                        enabled = state.totalCount > 0 || state.pendingCount > 0 || state.droppedCount > 0,
                        modifier = Modifier.testTag("logs_clear"),
                    ) {
                        Icon(Icons.Outlined.Delete, stringResource(R.string.logsClearLocal))
                    }
                    IconButton(
                        onClick = onCopyVisible,
                        enabled = state.entries.isNotEmpty(),
                        modifier = Modifier.testTag("logs_copy_visible"),
                    ) {
                        Icon(Icons.Outlined.ContentCopy, stringResource(R.string.logsCopyVisible))
                    }
                    IconButton(
                        onClick = onExportVisible,
                        enabled = state.entries.isNotEmpty(),
                        modifier = Modifier.testTag("logs_export_visible"),
                    ) {
                        Icon(Icons.Outlined.FileDownload, stringResource(R.string.logsExportVisible))
                    }
                },
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            OutlinedTextField(
                value = queryDraft,
                onValueChange = updateQuery,
                modifier = Modifier.fillMaxWidth().testTag("logs_search"),
                label = { Text(stringResource(R.string.logsSearch)) },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = {
                    if (queryDraft.isNotEmpty()) {
                        IconButton(onClick = { updateQuery("") }) {
                            Icon(Icons.Outlined.Close, stringResource(R.string.logsClearSearch))
                        }
                    }
                },
                singleLine = true,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                LogsFilterMenu(
                    label = stringResource(R.string.logsLevelFilter, levelLabel(state.level)),
                    values = listOf(null) + LogLevel.entries,
                    selected = state.level,
                    valueLabel = { levelLabel(it) },
                    tag = "logs_level",
                    onSelect = onLevelChange,
                )
                LogsFilterMenu(
                    label = stringResource(R.string.logsSourceFilter, sourceLabel(state.source)),
                    values = listOf(null) + LogSource.entries,
                    selected = state.source,
                    valueLabel = { sourceLabel(it) },
                    tag = "logs_source",
                    onSelect = onSourceChange,
                )
                TextButton(onClick = onTogglePause, modifier = Modifier.testTag("logs_pause")) {
                    Icon(
                        if (state.paused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause,
                        contentDescription = null,
                    )
                    Text(
                        stringResource(if (state.paused) R.string.logsResume else R.string.logsPause),
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(
                        when (state.status) {
                            LogStreamStatus.Connecting -> R.string.logsStatusConnecting
                            LogStreamStatus.Live -> R.string.logsStatusLive
                            LogStreamStatus.Waiting -> R.string.logsStatusWaiting
                            LogStreamStatus.Unavailable -> R.string.logsStatusUnavailable
                        },
                    ),
                    modifier = Modifier.testTag("logs_status"),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (state.status == LogStreamStatus.Unavailable) {
                        YaxcTheme.extendedColors.danger
                    } else {
                        YaxcTheme.extendedColors.textMuted
                    },
                )
                if (state.error != null) {
                    SelectionContainer {
                        Text(
                            text = state.error,
                            style = MaterialTheme.typography.bodySmall,
                            color = YaxcTheme.extendedColors.danger,
                            maxLines = 3,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.logsCounts, state.entries.size, state.totalCount),
                    modifier = Modifier.testTag("logs_counts"),
                    style = MaterialTheme.typography.labelMedium,
                    color = YaxcTheme.extendedColors.textMuted,
                )
                if (state.paused) {
                    Text(
                        text = stringResource(R.string.logsPausedPending, state.pendingCount),
                        modifier = Modifier.testTag("logs_pending"),
                        style = MaterialTheme.typography.labelMedium,
                        color = YaxcTheme.extendedColors.warning,
                    )
                }
                if (state.droppedCount > 0) {
                    Text(
                        text = stringResource(R.string.logsDroppedOldest, state.droppedCount),
                        modifier = Modifier.testTag("logs_dropped"),
                        style = MaterialTheme.typography.labelMedium,
                        color = YaxcTheme.extendedColors.textMuted,
                    )
                }
            }
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(bottom = spacing.sm)
                    .border(1.dp, YaxcTheme.extendedColors.cardBorder, MaterialTheme.shapes.medium),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                contentColor = MaterialTheme.colorScheme.onSurface,
                shape = MaterialTheme.shapes.medium,
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
            ) {
                Box(Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .nestedScroll(scrollConnection)
                            .testTag("logs_list"),
                        contentPadding = PaddingValues(bottom = if (following) 8.dp else 80.dp),
                    ) {
                        items(state.entries, key = { it.id }, contentType = { "log_entry" }) { entry ->
                            LogEntryRow(entry = entry, onCopy = { onCopyEntry(entry) })
                        }
                    }
                    if (state.entries.isEmpty()) {
                        Text(
                            text = stringResource(
                                when {
                                    state.query.isNotEmpty() || state.level != null || state.source != null ->
                                        R.string.logsNoMatches
                                    state.paused -> R.string.logsPausedEmpty
                                    state.status == LogStreamStatus.Unavailable -> R.string.logsUnavailableEmpty
                                    else -> R.string.noLogsYet
                                },
                            ),
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(spacing.lg)
                                .testTag("logs_empty"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = YaxcTheme.extendedColors.textMuted,
                        )
                    }
                    if (!following && state.entries.isNotEmpty()) {
                        ExtendedFloatingActionButton(
                            onClick = {
                                following = true
                                userScrolling = false
                                anchorId = null
                                scope.launch { scrollToLatest() }
                            },
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(spacing.md)
                                .testTag("logs_to_latest"),
                            icon = { Icon(Icons.Outlined.ArrowDownward, contentDescription = null) },
                            text = { Text(stringResource(R.string.logsToLatest)) },
                        )
                    }
                }
            }
        }
    }
}

private suspend fun LazyListState.scrollToLatest(lastIndex: Int) {
    if (lastIndex < 0) return
    scrollToItem(lastIndex)
    // A single wrapped row may be taller than the viewport.
    val last = layoutInfo.visibleItemsInfo.lastOrNull() ?: return
    val remaining = last.offset + last.size - layoutInfo.viewportEndOffset
    if (remaining > 0) scrollBy(remaining.toFloat())
}

@Composable
private fun <T> LogsFilterMenu(
    label: String,
    values: List<T>,
    selected: T,
    valueLabel: @Composable (T) -> String,
    tag: String,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }, modifier = Modifier.testTag("${tag}_filter")) {
            Text(label)
            Icon(Icons.Outlined.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            values.forEach { value ->
                DropdownMenuItem(
                    text = { Text(valueLabel(value)) },
                    onClick = {
                        expanded = false
                        onSelect(value)
                    },
                    modifier = Modifier.testTag("${tag}_${value ?: "All"}"),
                    trailingIcon = {
                        if (value == selected) {
                            Text(stringResource(R.string.logsFilterSelected), style = MaterialTheme.typography.labelSmall)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun LogEntryRow(entry: LogEntry, onCopy: () -> Unit) {
    val color = levelColor(entry.level)
    Column(modifier = Modifier.fillMaxWidth().testTag("logs_entry_${entry.id}")) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SelectionContainer(modifier = Modifier.weight(1f)) {
                Text(
                    text = listOfNotNull(
                        entry.timestamp,
                        levelLabel(entry.level),
                        sourceLabel(entry.source),
                        entry.tag,
                    ).joinToString(" · "),
                    modifier = Modifier.padding(vertical = 6.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = color,
                )
            }
            IconButton(onClick = onCopy, modifier = Modifier.testTag("logs_copy_entry_${entry.id}")) {
                Icon(Icons.Outlined.ContentCopy, stringResource(R.string.logsCopyEntry))
            }
        }
        SelectionContainer {
            Text(
                text = entry.message,
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = color,
            )
        }
        HorizontalDivider(color = YaxcTheme.extendedColors.cardBorder)
    }
}

@Composable
private fun levelColor(level: LogLevel): Color = when (level) {
    LogLevel.Error -> YaxcTheme.extendedColors.danger
    LogLevel.Warning -> YaxcTheme.extendedColors.warning
    LogLevel.Debug -> MaterialTheme.colorScheme.primary
    LogLevel.Verbose, LogLevel.Unknown -> YaxcTheme.extendedColors.textMuted
    LogLevel.Info -> MaterialTheme.colorScheme.onSurface
}

@Composable
private fun levelLabel(level: LogLevel?): String = stringResource(
    when (level) {
        null -> R.string.logsFilterAll
        LogLevel.Verbose -> R.string.logsLevelVerbose
        LogLevel.Debug -> R.string.logsLevelDebug
        LogLevel.Info -> R.string.logsLevelInfo
        LogLevel.Warning -> R.string.logsLevelWarning
        LogLevel.Error -> R.string.logsLevelError
        LogLevel.Unknown -> R.string.logsLevelUnknown
    },
)

@Composable
private fun sourceLabel(source: LogSource?): String = stringResource(
    when (source) {
        null -> R.string.logsFilterAll
        LogSource.Xray -> R.string.logsSourceXray
        LogSource.App -> R.string.logsSourceApp
        LogSource.Vpn -> R.string.logsSourceVpn
        LogSource.Helper -> R.string.logsSourceHelper
    },
)
