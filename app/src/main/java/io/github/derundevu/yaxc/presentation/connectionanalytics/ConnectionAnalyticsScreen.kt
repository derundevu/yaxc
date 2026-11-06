package io.github.derundevu.yaxc.presentation.connectionanalytics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.derundevu.yaxc.R
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcGlassPanel
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcScaffold
import io.github.derundevu.yaxc.repository.ConnectionCaptureStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionAnalyticsScreen(
    state: ConnectionAnalyticsState,
    listState: LazyListState,
    onBack: () -> Unit,
    onGrouping: (ConnectionGrouping) -> Unit,
    onFilter: (ConnectionFilter) -> Unit,
    onPersistence: (Boolean) -> Unit,
    onClearHistory: () -> Unit,
) {
    YaxcScaffold(
        modifier = Modifier.testTag("analytics:screen"),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.connectionAnalyticsTitle)) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("analytics:back")) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.connectionAnalyticsBack))
                    }
                },
            )
        },
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().navigationBarsPadding().testTag("analytics:list"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "controls", contentType = "controls") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ConnectionGrouping.entries.forEach { group ->
                            FilterChip(
                                selected = state.grouping == group,
                                onClick = { onGrouping(group) },
                                label = { Text(stringResource(if (group == ConnectionGrouping.App) R.string.connectionAnalyticsByApp else R.string.connectionAnalyticsByConfig)) },
                                modifier = Modifier.testTag("analytics:group:${group.name}"),
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ConnectionFilter.entries.forEach { filter ->
                            FilterChip(
                                selected = state.filter == filter,
                                onClick = { onFilter(filter) },
                                label = { Text(stringResource(when (filter) {
                                    ConnectionFilter.Active -> R.string.connectionAnalyticsActive
                                    ConnectionFilter.Closed -> R.string.connectionAnalyticsClosed
                                    ConnectionFilter.All -> R.string.connectionAnalyticsAll
                                })) },
                                modifier = Modifier.testTag("analytics:filter:${filter.name}"),
                            )
                        }
                    }
                    Text(stringResource(when (state.status) {
                        ConnectionCaptureStatus.Running -> R.string.connectionAnalyticsRunning
                        ConnectionCaptureStatus.Stopped -> R.string.connectionAnalyticsStopped
                        ConnectionCaptureStatus.Unsupported -> R.string.connectionAnalyticsUnsupported
                        ConnectionCaptureStatus.Error -> R.string.connectionAnalyticsError
                    }), style = MaterialTheme.typography.bodyMedium)
                    if (state.error != null && state.status != ConnectionCaptureStatus.Error) {
                        Text(stringResource(R.string.connectionAnalyticsError), color = MaterialTheme.colorScheme.error)
                    }
                    Text(stringResource(R.string.connectionAnalyticsRetention), style = MaterialTheme.typography.bodySmall)
                    if (state.dropped > 0) {
                        Text(stringResource(R.string.connectionAnalyticsOverflow, state.dropped), color = MaterialTheme.colorScheme.error)
                    }
                    Totals(state.totals)
                }
            }
            item(key = "privacy", contentType = "privacy") {
                YaxcGlassPanel(contentPadding = PaddingValues(16.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .toggleable(value = state.persistOnDisk, role = Role.Switch, onValueChange = onPersistence)
                                .testTag("analytics:persistence"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(stringResource(R.string.connectionAnalyticsPersistence), modifier = Modifier.weight(1f))
                            Switch(
                                checked = state.persistOnDisk,
                                onCheckedChange = null,
                            )
                        }
                        Text(stringResource(R.string.connectionAnalyticsPrivacy), style = MaterialTheme.typography.bodySmall)
                        TextButton(
                            enabled = state.hasClosedHistory,
                            onClick = onClearHistory,
                            modifier = Modifier.testTag("analytics:clear"),
                        ) { Text(stringResource(R.string.connectionAnalyticsClear)) }
                        Text(stringResource(R.string.connectionAnalyticsClearHint), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (state.items.isEmpty()) {
                item(key = "empty", contentType = "empty") {
                    Text(stringResource(R.string.connectionAnalyticsEmpty), modifier = Modifier.testTag("analytics:empty"))
                }
            }
            items(state.items, key = { it.key }, contentType = {
                when (it) {
                    is ConnectionAnalyticsItem.Header -> if (it.secondary) "subgroup" else "group"
                    is ConnectionAnalyticsItem.Flow -> "flow"
                }
            }) { item ->
                when (item) {
                    is ConnectionAnalyticsItem.Header -> Column(
                        modifier = Modifier.fillMaxWidth().padding(start = if (item.secondary) 12.dp else 0.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Identity(item.identity, if (item.secondary) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium)
                        Totals(item.totals)
                    }
                    is ConnectionAnalyticsItem.Flow -> FlowRow(item)
                }
            }
        }
    }
}

@Composable
private fun Identity(identity: ConnectionIdentity, style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyMedium) {
    Text(identity.title ?: stringResource(if (identity.kind == ConnectionIdentityKind.App) R.string.connectionAnalyticsUnknownApp else R.string.connectionAnalyticsUnknownConfig), style = style)
    if (identity.detail.isNotEmpty()) Text(identity.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (identity.sharedUid) {
        Text(stringResource(R.string.connectionAnalyticsSharedUid), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun Totals(totals: ConnectionTotals) {
    Text(stringResource(R.string.connectionAnalyticsTotals, totals.count, totals.upload, totals.download), style = MaterialTheme.typography.labelMedium)
    if (!totals.liveBytesExact) {
        Text(stringResource(R.string.connectionAnalyticsTotalsIncomplete), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun FlowRow(item: ConnectionAnalyticsItem.Flow) {
    val record = item.record
    YaxcGlassPanel(
        modifier = Modifier.fillMaxWidth().testTag(item.key),
        contentPadding = PaddingValues(16.dp),
    ) {
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Identity(item.app)
                Identity(item.config)
                Text(stringResource(R.string.connectionAnalyticsRoute, stringResource(when (record.route) {
                    "proxy" -> R.string.connectionAnalyticsProxy
                    "direct" -> R.string.connectionAnalyticsDirect
                    "block" -> R.string.connectionAnalyticsBlock
                    else -> R.string.connectionAnalyticsUnknownRoute
                })), style = MaterialTheme.typography.labelLarge)
                if (record.outboundTag.isNotBlank()) {
                    Text(stringResource(R.string.connectionAnalyticsOutbound, record.outboundTag), style = MaterialTheme.typography.bodySmall)
                }
                Text(stringResource(R.string.connectionAnalyticsProtocol, record.protocol), style = MaterialTheme.typography.labelMedium)
                if (record.destinationKind == "association" || record.protocol == "udp") {
                    Text(stringResource(R.string.connectionAnalyticsAssociation), style = MaterialTheme.typography.labelMedium)
                }
                Text(item.destinations.ifBlank { stringResource(R.string.connectionAnalyticsUnknownDestination) }, style = MaterialTheme.typography.bodyMedium)
                if (record.domain.isNotBlank()) Text(stringResource(R.string.connectionAnalyticsDomain, record.domain), style = MaterialTheme.typography.bodySmall)
                if (record.destinationsTruncated) Text(stringResource(R.string.connectionAnalyticsDestinationsTruncated), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(when (record.state) {
                    "active" -> R.string.connectionAnalyticsActive
                    "closed" -> R.string.connectionAnalyticsStateClosed
                    "blocked" -> R.string.connectionAnalyticsStateBlocked
                    "interrupted" -> R.string.connectionAnalyticsStateInterrupted
                    "error" -> R.string.connectionAnalyticsStateError
                    else -> R.string.connectionAnalyticsStateUnknown
                }), style = MaterialTheme.typography.labelLarge)
                Text(stringResource(R.string.connectionAnalyticsStarted, item.started), style = MaterialTheme.typography.bodySmall)
                item.ended?.let { Text(stringResource(R.string.connectionAnalyticsEnded, it), style = MaterialTheme.typography.bodySmall) }
                Text(stringResource(R.string.connectionAnalyticsBytes, item.upload, item.download), style = MaterialTheme.typography.bodyMedium)
                if (!record.liveBytesExact) Text(stringResource(R.string.connectionAnalyticsDeferred), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
