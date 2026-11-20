package io.github.derundevu.yaxc.presentation.links

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import io.github.derundevu.yaxc.R
import io.github.derundevu.yaxc.helper.SubscriptionRefreshHelper
import io.github.derundevu.yaxc.presentation.designsystem.YaxcTheme

@Composable
fun SubscriptionRefreshDialog(
    result: SubscriptionRefreshHelper.RefreshResult,
    onClose: () -> Unit,
    titleRes: Int = R.string.subscriptionRefreshResultTitle,
    warningsOnly: Boolean = false,
) {
    val changedSources = remember(result) {
        result.changes.filter { it.added.isNotEmpty() || it.removed.isNotEmpty() || it.modified.isNotEmpty() }
    }
    val summary = when {
        warningsOnly -> null
        result.attempted == 0 -> stringResource(R.string.feedbackRefreshEmpty)
        titleRes == R.string.profileImportTitle && result.failed > 0 -> stringResource(R.string.profileImportFailed)
        result.refreshed == 0 -> pluralStringResource(
            R.plurals.subscriptionRefreshAllFailed, result.failed, result.failed,
        )
        result.failed > 0 -> pluralStringResource(
            R.plurals.feedbackRefreshPartial, result.attempted,
            result.refreshed, result.attempted, result.failed,
        )
        result.warnings.isNotEmpty() -> stringResource(R.string.subscriptionRefreshWithSkipped)
        changedSources.isNotEmpty() && result.attempted > 1 -> pluralStringResource(
            R.plurals.feedbackRefreshSummary, result.attempted, result.refreshed, result.attempted,
        )
        else -> null
    }
    val added = stringResource(R.string.subscriptionRefreshAdded)
    val removed = stringResource(R.string.subscriptionRefreshRemoved)
    val modified = stringResource(R.string.subscriptionRefreshModified)

    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(titleRes)) },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp),
                verticalArrangement = Arrangement.spacedBy(YaxcTheme.spacing.sm),
            ) {
                if (summary != null) {
                    item(key = "summary") {
                        Text(
                            text = summary,
                            color = if (result.failed > 0) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (!warningsOnly && changedSources.isEmpty() && result.refreshed > 0 && result.warnings.isEmpty()) {
                    item(key = "unchanged") {
                        Box(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = stringResource(
                                    if (result.failed == 0) R.string.subscriptionRefreshNothingNew
                                    else R.string.subscriptionRefreshUnchangedPartial,
                                ),
                                style = MaterialTheme.typography.titleMedium,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
                if (result.warnings.isNotEmpty()) {
                    item(key = "skipped-explanation") {
                        Text(
                            text = stringResource(R.string.subscriptionSkippedExplanation),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    result.warnings.forEach { source ->
                        item(key = "warning-source:${source.sourceId}") {
                            Text(
                                text = source.sourceName.ifBlank { stringResource(R.string.newSource) },
                                modifier = Modifier.padding(top = YaxcTheme.spacing.sm),
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                        items(
                            count = source.skippedProfiles.size,
                            key = { "warning:${source.sourceId}:$it" },
                        ) { index ->
                            val profile = source.skippedProfiles[index]
                            Text(
                                text = stringResource(R.string.subscriptionSkippedEntry, profile.name, profile.protocol),
                                modifier = Modifier.padding(start = YaxcTheme.spacing.sm),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
                changedSources.forEach { source ->
                    item(key = "source:${source.sourceId}") {
                        Text(
                            text = source.sourceName,
                            modifier = Modifier.padding(top = YaxcTheme.spacing.sm),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    changeSection(source.sourceId, "added", added, source.added)
                    changeSection(source.sourceId, "removed", removed, source.removed)
                    changeSection(source.sourceId, "modified", modified, source.modified)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onClose) {
                Text(stringResource(R.string.close))
            }
        },
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    )
}

private fun LazyListScope.changeSection(sourceId: Long, category: String, title: String, names: List<String>) {
    if (names.isEmpty()) return
    item(key = "$sourceId:$category") {
        Text(
            text = "$title · ${names.size}",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    items(count = names.size, key = { "$sourceId:$category:$it" }) { index ->
        Text(
            text = names[index],
            modifier = Modifier.padding(start = YaxcTheme.spacing.sm),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
