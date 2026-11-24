package io.github.derundevu.yaxc.presentation.connectionanalytics

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.derundevu.yaxc.R
import io.github.derundevu.yaxc.presentation.designsystem.YaxcTheme
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcLiquidDropdownMenu
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcScaffold
import io.github.derundevu.yaxc.repository.ConnectionCaptureStatus
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionAnalyticsScreen(
    state: ConnectionAnalyticsState,
    onBack: () -> Unit,
    onQuery: (ConnectionAnalyticsQuery) -> Unit,
    onCaptureEnabled: (Boolean) -> Unit,
    onPersistence: (Boolean) -> Unit,
    onClearHistory: () -> Unit,
) {
    val query = state.query
    val scopeKey = query.scopeKey
    val context = LocalContext.current
    val iconCache = remember(context.applicationContext) { ConnectionIconCache(context) }
    val positions = rememberSaveableStateHolder()
    val focusManager = LocalFocusManager.current
    val slideOffset = with(LocalDensity.current) { 20.dp.roundToPx() } *
        if (LocalLayoutDirection.current == LayoutDirection.Ltr) 1 else -1
    var filtersOpen by rememberSaveable(scopeKey) { mutableStateOf(false) }
    var historyOpen by rememberSaveable { mutableStateOf(false) }
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    var capturedDetail by remember { mutableStateOf<ConnectionFlow?>(null) }
    val detail = detailId?.let { id -> state.flows.firstOrNull { it.record.id == id } ?: capturedDetail?.takeIf { it.record.id == id } }
    val back = {
        when {
            detailId != null -> { detailId = null; capturedDetail = null }
            filtersOpen -> filtersOpen = false
            query.selection != null -> {
                focusManager.clearFocus()
                onQuery(query.copy(selection = null))
            }
            else -> onBack()
        }
    }
    BackHandler { back() }
    YaxcScaffold(
        modifier = Modifier.testTag("analytics:screen"),
        topBar = {
            TopAppBar(
                title = { Text(query.selection?.let { identityTitle(it) } ?: stringResource(R.string.connectionAnalyticsTitle), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = back, modifier = Modifier.testTag("analytics:back")) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.ca_back))
                    }
                },
                actions = { TextButton(onClick = { historyOpen = true }, modifier = Modifier.testTag("analytics:history")) { Text(stringResource(R.string.ca_history)) } },
            )
        },
    ) {
        AnimatedContent(
            targetState = state,
            contentKey = { it.query.scopeKey },
            modifier = Modifier.fillMaxSize().clipToBounds(),
            transitionSpec = {
                val enteringDetails = targetState.query.selection != null
                if (enteringDetails == (initialState.query.selection != null)) {
                    EnterTransition.None togetherWith ExitTransition.None
                } else {
                    val offset = if (enteringDetails) slideOffset else -slideOffset
                    (fadeIn(tween(220)) + slideInHorizontally(tween(220)) { offset }) togetherWith
                        (fadeOut(tween(180)) + slideOutHorizontally(tween(180)) { -offset })
                }.using(null)
            },
            label = "connection analytics scope",
        ) { page ->
            val pageKey = page.query.scopeKey
            val active = pageKey == scopeKey
            positions.SaveableStateProvider(pageKey) {
                AnalyticsList(
                    state = page,
                    active = active,
                    iconCache = iconCache,
                    onQuery = { if (active) onQuery(it) },
                    onShowFilters = {
                        if (active) {
                            focusManager.clearFocus()
                            filtersOpen = true
                        }
                    },
                    onShowFlow = { if (active) { capturedDetail = it; detailId = it.record.id } },
                )
            }
        }
    }
    if (filtersOpen && query.selection != null) {
        DetailFilters(state, onQuery) { filtersOpen = false }
    }
    if (historyOpen) HistoryDialog(state, onCaptureEnabled, onPersistence, onClearHistory) { historyOpen = false }
    if (detail != null) FlowDetail(detail) { detailId = null; capturedDetail = null }
}

private val ConnectionAnalyticsQuery.scopeKey: String
    get() = "${grouping.name}:${selection?.key ?: "root"}"

@Composable
private fun AnalyticsList(
    state: ConnectionAnalyticsState,
    active: Boolean,
    iconCache: ConnectionIconCache,
    onQuery: (ConnectionAnalyticsQuery) -> Unit,
    onShowFilters: () -> Unit,
    onShowFlow: (ConnectionFlow) -> Unit,
) {
    val query = state.query
    val isRoot = query.selection == null
    val spacing = YaxcTheme.spacing
    LazyColumn(
        state = rememberLazyListState(),
        modifier = Modifier.fillMaxSize().navigationBarsPadding().then(
            if (active) Modifier.testTag("analytics:list")
            else Modifier.clearAndSetSemantics { hideFromAccessibility() },
        ),
        userScrollEnabled = active,
        contentPadding = PaddingValues(horizontal = spacing.md, vertical = spacing.sm),
    ) {
        item(key = "controls", contentType = "controls") {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                if (isRoot) {
                    ChipRow {
                        ConnectionGrouping.entries.forEach { group ->
                            FilterChip(selected = query.grouping == group,
                                onClick = { onQuery(query.copy(grouping = group)) },
                                label = { Text(stringResource(if (group == ConnectionGrouping.App) R.string.connectionAnalyticsByApp else R.string.connectionAnalyticsByConfig)) },
                                modifier = Modifier.testTag("analytics:group:${group.name}"))
                        }
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(value = query.search, onValueChange = { onQuery(query.copy(search = it)) },
                            label = { Text(stringResource(R.string.ca_search)) }, singleLine = true,
                            modifier = Modifier.weight(1f).testTag("analytics:search"))
                        FilterButton(query, onShowFilters)
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    ConnectionFilter.entries.forEach { filter ->
                        FilterChip(selected = (if (isRoot) query.rootFilter else query.filter) == filter,
                            onClick = { onQuery(if (isRoot) query.copy(rootFilter = filter) else query.copy(filter = filter)) },
                            label = { Text(stringResource(filterLabel(filter))) },
                            modifier = Modifier.testTag("analytics:filter:${filter.name}"))
                    }
                }
                Text(stringResource(R.string.ca_retained), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Totals(state.totals)
                if (isRoot) {
                    Text(stringResource(when (state.status) {
                        ConnectionCaptureStatus.Running -> R.string.ca_running
                        ConnectionCaptureStatus.Stopped -> R.string.ca_stopped
                        ConnectionCaptureStatus.Disabled -> R.string.ca_disabled
                        ConnectionCaptureStatus.Unsupported -> R.string.connectionAnalyticsUnsupported
                        ConnectionCaptureStatus.Error -> R.string.connectionAnalyticsError
                    }), modifier = Modifier.testTag("analytics:status"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (state.counters.nativeRejected > 0 || state.counters.historyRejected > 0 || state.error != null) {
                    Text(stringResource(R.string.ca_incomplete), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (state.summaries.isEmpty() && state.flows.isEmpty()) {
            item(key = "empty", contentType = "empty") {
                Text(stringResource(if (state.captureEnabled) R.string.connectionAnalyticsEmpty else R.string.ca_disabled_empty), modifier = Modifier.padding(vertical = 16.dp).testTag("analytics:empty"))
            }
        }
        items(state.summaries, key = { it.key }, contentType = { "summary" }) { summary ->
            SummaryRow(summary, iconCache) {
                onQuery(ConnectionAnalyticsQuery(
                    grouping = query.grouping,
                    rootFilter = query.rootFilter,
                    selection = summary.identity,
                    filter = query.rootFilter,
                ))
            }
        }
        items(state.flows, key = { it.key }, contentType = { "flow" }) { flow ->
            FlowRow(flow, query.grouping, iconCache) { onShowFlow(flow) }
        }
    }
}

private fun ConnectionAnalyticsQuery.advancedFilterCount(): Int =
    (if (oppositeKey != null) 1 else 0) +
        (if (protocol != ConnectionProtocol.All) 1 else 0) +
        (if (route != ConnectionRoute.All) 1 else 0) +
        (if (terminal != ConnectionTerminal.All) 1 else 0) +
        (if (period != ConnectionPeriod.All) 1 else 0)

@Composable
private fun FilterButton(query: ConnectionAnalyticsQuery, onClick: () -> Unit) {
    val count = query.advancedFilterCount()
    val description = if (count == 0) stringResource(R.string.ca_filters)
        else stringResource(R.string.ca_filters_count, count)
    IconButton(onClick = onClick, modifier = Modifier.testTag("analytics:filters")) {
        BadgedBox(badge = {
            if (count > 0) {
                Badge(
                    modifier = Modifier.clearAndSetSemantics {},
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ) { Text(count.toString()) }
            }
        }) {
            Icon(Icons.Outlined.FilterList, contentDescription = description, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { content() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailFilters(state: ConnectionAnalyticsState, onQuery: (ConnectionAnalyticsQuery) -> Unit, onDismiss: () -> Unit) {
    val query = state.query
    val spacing = YaxcTheme.spacing
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.testTag("analytics:filters-sheet"),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.ca_filters), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    scope.launch {
                        sheetState.hide()
                        onDismiss()
                    }
                }, modifier = Modifier.testTag("analytics:filters-done")) { Text(stringResource(R.string.ca_done)) }
            }
            Column(
                modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                    .padding(bottom = spacing.xl),
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                val oppositeByKey = remember(state.oppositeIdentities) { state.oppositeIdentities.associateBy { it.key } }
                val oppositeKeys = remember(oppositeByKey) { listOf<String?>(null) + oppositeByKey.keys }
                val opposite = oppositeByKey[query.oppositeKey]
                Choice(stringResource(if (query.grouping == ConnectionGrouping.App) R.string.ca_config else R.string.ca_app),
                    opposite?.let { identityTitle(it) } ?: stringResource(if (query.oppositeKey == null) R.string.connectionAnalyticsAll else R.string.ca_unavailable_identity),
                    oppositeKeys, "opposite",
                    { key -> oppositeByKey[key]?.let { identityTitle(it) } ?: stringResource(R.string.connectionAnalyticsAll) },
                    { onQuery(query.copy(oppositeKey = it)) })
                Choice(stringResource(R.string.ca_protocol), protocolTitle(query.protocol), ConnectionProtocol.entries, "protocol", { protocolTitle(it) }, { onQuery(query.copy(protocol = it)) })
                Choice(stringResource(R.string.ca_route), routeTitle(query.route), ConnectionRoute.entries, "route", { routeTitle(it) }, { onQuery(query.copy(route = it)) })
                Choice(stringResource(R.string.ca_terminal), terminalTitle(query.terminal), ConnectionTerminal.entries, "terminal", { terminalTitle(it) }, { onQuery(query.copy(terminal = it)) })
                Choice(stringResource(R.string.ca_period), periodTitle(query.period), ConnectionPeriod.entries, "period", { periodTitle(it) }, { onQuery(query.copy(period = it)) })
                TextButton(
                    onClick = {
                        onQuery(query.copy(oppositeKey = null, protocol = ConnectionProtocol.All,
                            route = ConnectionRoute.All, terminal = ConnectionTerminal.All, period = ConnectionPeriod.All))
                    },
                    enabled = query.advancedFilterCount() > 0,
                    modifier = Modifier.testTag("analytics:reset").align(Alignment.End),
                ) { Text(stringResource(R.string.ca_reset)) }
                HorizontalDivider()
                Choice(stringResource(R.string.ca_sort), sortTitle(query.sort), ConnectionSort.entries, "sort", { sortTitle(it) }, { onQuery(query.copy(sort = it)) })
            }
        }
    }
}

@Composable
private fun <T> Choice(label: String, value: String, options: List<T>, tag: String, title: @Composable (T) -> String, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(
            onClick = { open = true },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("analytics:choice:$tag"),
        ) {
            Text("$label: $value", modifier = Modifier.fillMaxWidth(), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        YaxcLiquidDropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 320.dp)) {
            options.forEachIndexed { index, option ->
                DropdownMenuItem(text = { Text(title(option)) }, onClick = { open = false; onSelect(option) }, modifier = Modifier.testTag("analytics:option:$tag:$index"))
            }
        }
    }
}

@Composable
private fun SummaryRow(summary: ConnectionSummary, cache: ConnectionIconCache, onClick: () -> Unit) {
    Column {
        Row(modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(vertical = 12.dp).testTag(summary.key),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ConnectionAppIcon(summary.identity, cache)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(identityTitle(summary.identity), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val metadata = listOfNotNull(summary.identity.detail.takeIf { it.isNotEmpty() }, if (summary.identity.sharedUid) stringResource(R.string.connectionAnalyticsSharedUid) else null).joinToString(" · ")
                if (metadata.isNotBlank()) Text(metadata, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.ca_summary, summary.activeCount, summary.totals.count - summary.activeCount, summary.totals.upload, summary.totals.download), style = MaterialTheme.typography.labelMedium)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    }
}

@Composable
private fun FlowRow(flow: ConnectionFlow, grouping: ConnectionGrouping, cache: ConnectionIconCache, onClick: () -> Unit) {
    val opposite = if (grouping == ConnectionGrouping.App) flow.config else flow.app
    Row(modifier = Modifier.fillMaxWidth().heightIn(min = 76.dp).clickable(role = Role.Button, onClick = onClick).padding(vertical = 8.dp).testTag(flow.key),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (opposite.kind == ConnectionIdentityKind.App) ConnectionAppIcon(opposite, cache, 24.dp)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(flow.endpoint.ifBlank { stringResource(R.string.connectionAnalyticsUnknownDestination) }, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${stateTitle(flow.record.state)} · ${flow.record.protocol.uppercase()} · ${identityTitle(opposite)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${flow.started} · ↑ ${flow.upload} · ↓ ${flow.download}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun Totals(totals: ConnectionTotals) {
    Text(stringResource(R.string.connectionAnalyticsTotals, totals.count, totals.upload, totals.download), style = MaterialTheme.typography.labelMedium)
    if (!totals.liveBytesExact) Text(stringResource(R.string.connectionAnalyticsTotalsIncomplete), style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun HistoryDialog(state: ConnectionAnalyticsState, onCaptureEnabled: (Boolean) -> Unit, onPersistence: (Boolean) -> Unit, onClear: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.ca_history)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()).testTag("analytics:history-details"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(state.captureEnabled, role = Role.Switch, onValueChange = onCaptureEnabled).testTag("analytics:capture"), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.ca_capture), modifier = Modifier.weight(1f))
                    Switch(checked = state.captureEnabled, onCheckedChange = null)
                }
                Text(stringResource(R.string.ca_capture_hint), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.ca_retention), style = MaterialTheme.typography.bodySmall)
                val counters = state.counters
                Text(stringResource(R.string.ca_native_evicted, counters.nativeEvicted), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.ca_native_uncertainty), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.ca_history_evicted, counters.historyEvicted), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.ca_rejected, counters.nativeRejected, counters.historyRejected), style = MaterialTheme.typography.bodySmall,
                    color = if (counters.nativeRejected > 0 || counters.historyRejected > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                if (counters.unclassified > 0) Text(stringResource(R.string.ca_legacy_events, counters.unclassified), style = MaterialTheme.typography.bodySmall)
                if (state.error != null) Text(stringResource(R.string.connectionAnalyticsError), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                Row(modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(state.persistOnDisk, role = Role.Switch, onValueChange = onPersistence).testTag("analytics:persistence"), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.connectionAnalyticsPersistence), modifier = Modifier.weight(1f))
                    Switch(checked = state.persistOnDisk, onCheckedChange = null)
                }
                Text(stringResource(R.string.connectionAnalyticsPrivacy), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onClear, enabled = state.hasClosedHistory || counters != io.github.derundevu.yaxc.repository.ConnectionHistoryCounters(), modifier = Modifier.testTag("analytics:clear")) { Text(stringResource(R.string.connectionAnalyticsClear)) }
                Text(stringResource(R.string.ca_clear_hint), style = MaterialTheme.typography.bodySmall)
            }
        }, confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ca_done)) } })
}

@Composable
private fun FlowDetail(flow: ConnectionFlow, onDismiss: () -> Unit) {
    val record = flow.record
    val locale = LocalConfiguration.current.locales[0]
    val ended = remember(record.endedAt, locale) { record.endedAt.takeIf { it > 0 }?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM, locale).format(Date(it)) } }
    val destinations = remember(record.destination, record.destinations) { (listOf(record.destination) + record.destinations).filter { it.isNotBlank() }.distinct().joinToString("\n") }
    val details = listOfNotNull(
        stringResource(R.string.ca_record_id, record.id),
        stringResource(R.string.ca_app) + ": " + identityTitle(flow.app),
        flow.app.detail.takeIf { it.isNotBlank() },
        if (flow.app.sharedUid) stringResource(R.string.connectionAnalyticsSharedUid) else null,
        stringResource(R.string.ca_config) + ": " + identityTitle(flow.config),
        flow.config.detail.takeIf { it.isNotBlank() },
        stringResource(R.string.connectionAnalyticsRoute, routeTitle(when (record.route) { "proxy" -> ConnectionRoute.Proxy; "direct" -> ConnectionRoute.Direct; "block" -> ConnectionRoute.Block; else -> ConnectionRoute.Unknown })),
        record.outboundTag.takeIf { it.isNotBlank() }?.let { stringResource(R.string.connectionAnalyticsOutbound, it) },
        stringResource(R.string.connectionAnalyticsProtocol, record.protocol),
        if (record.destinationKind == "association" || record.protocol == "udp") stringResource(R.string.connectionAnalyticsAssociation) else null,
        destinations.ifBlank { stringResource(R.string.connectionAnalyticsUnknownDestination) },
        record.domain.takeIf { it.isNotBlank() }?.let { stringResource(R.string.connectionAnalyticsDomain, it) },
        if (record.destinationsTruncated) stringResource(R.string.connectionAnalyticsDestinationsTruncated) else null,
        stateTitle(record.state), stringResource(R.string.connectionAnalyticsStarted, flow.started),
        ended?.let { stringResource(R.string.connectionAnalyticsEnded, it) },
        stringResource(R.string.connectionAnalyticsBytes, flow.upload, flow.download),
        if (!record.liveBytesExact) stringResource(R.string.connectionAnalyticsDeferred) else null,
    ).joinToString("\n\n")
    val clipboard = LocalClipboardManager.current
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.ca_detail)) },
        text = { SelectionContainer { Text(details, modifier = Modifier.verticalScroll(rememberScrollState()).testTag("analytics:detail"), style = MaterialTheme.typography.bodyMedium) } },
        dismissButton = { TextButton(onClick = { clipboard.setText(AnnotatedString(details)) }, modifier = Modifier.testTag("analytics:copy")) { Text(stringResource(R.string.ca_copy)) } },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ca_done)) } })
}

@Composable
private fun identityTitle(identity: ConnectionIdentity): String = identity.title ?: stringResource(if (identity.kind == ConnectionIdentityKind.App) R.string.connectionAnalyticsUnknownApp else R.string.connectionAnalyticsUnknownConfig)
private fun filterLabel(filter: ConnectionFilter): Int = when (filter) { ConnectionFilter.Active -> R.string.connectionAnalyticsActive; ConnectionFilter.Closed -> R.string.connectionAnalyticsClosed; ConnectionFilter.All -> R.string.connectionAnalyticsAll }
@Composable
private fun stateTitle(state: String): String = stringResource(when (state) { "active" -> R.string.connectionAnalyticsActive; "closed" -> R.string.connectionAnalyticsStateClosed; "error" -> R.string.connectionAnalyticsStateError; "blocked" -> R.string.connectionAnalyticsStateBlocked; "interrupted" -> R.string.connectionAnalyticsStateInterrupted; else -> R.string.connectionAnalyticsStateUnknown })
@Composable
private fun protocolTitle(value: ConnectionProtocol): String = if (value == ConnectionProtocol.All) stringResource(R.string.connectionAnalyticsAll) else value.name.uppercase()
@Composable
private fun routeTitle(value: ConnectionRoute): String = stringResource(when (value) { ConnectionRoute.All -> R.string.connectionAnalyticsAll; ConnectionRoute.Proxy -> R.string.connectionAnalyticsProxy; ConnectionRoute.Direct -> R.string.connectionAnalyticsDirect; ConnectionRoute.Block -> R.string.connectionAnalyticsBlock; ConnectionRoute.Unknown -> R.string.connectionAnalyticsUnknownRoute })
@Composable
private fun terminalTitle(value: ConnectionTerminal): String = if (value == ConnectionTerminal.All) stringResource(R.string.connectionAnalyticsAll) else stateTitle(value.name.lowercase())
@Composable
private fun periodTitle(value: ConnectionPeriod): String = stringResource(when (value) { ConnectionPeriod.All -> R.string.connectionAnalyticsAll; ConnectionPeriod.FiveMinutes -> R.string.ca_period_five; ConnectionPeriod.Hour -> R.string.ca_period_hour; ConnectionPeriod.Day -> R.string.ca_period_day })
@Composable
private fun sortTitle(value: ConnectionSort): String = stringResource(when (value) { ConnectionSort.Newest -> R.string.ca_newest; ConnectionSort.Traffic -> R.string.ca_traffic; ConnectionSort.Duration -> R.string.ca_duration })
