package io.github.derundevu.yaxc.presentation.links

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.derundevu.yaxc.R
import io.github.derundevu.yaxc.database.Link
import io.github.derundevu.yaxc.presentation.designsystem.YaxcTheme
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcActionBubble
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcGlassPanel
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcScaffold
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcTopAppBar
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Composable
fun LinksScreen(
    links: List<Link>,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onNewLink: () -> Unit,
    onEditLink: (Link) -> Unit,
    onDeleteLink: (Link) -> Unit,
) {
    val spacing = YaxcTheme.spacing

    YaxcScaffold(
        topBar = {
            YaxcTopAppBar(
                title = stringResource(R.string.links),
                onBack = onBack,
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = stringResource(R.string.refreshLinks),
                        )
                    }
                    IconButton(onClick = onNewLink) {
                        Icon(
                            imageVector = Icons.Outlined.Add,
                            contentDescription = stringResource(R.string.newSource),
                        )
                    }
                },
            )
        },
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .testTag("links:list"),
            contentPadding = PaddingValues(
                start = spacing.md,
                end = spacing.md,
                top = spacing.sm,
                bottom = spacing.xl,
            ),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            item(key = "lead") {
                Text(
                    text = stringResource(R.string.linksScreenLead),
                    style = MaterialTheme.typography.bodyLarge,
                    color = YaxcTheme.extendedColors.textMuted,
                    modifier = Modifier.padding(bottom = spacing.xs),
                )
            }

            if (links.isEmpty()) {
                item(key = "empty") {
                    EmptyLinksCard()
                }
            } else {
                items(
                    items = links,
                    key = { it.id },
                ) { link ->
                    LinkRow(
                        link = link,
                        onEdit = { onEditLink(link) },
                        onDelete = { onDeleteLink(link) },
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyLinksCard() {
    YaxcGlassPanel(
        shape = MaterialTheme.shapes.large,
        contentPadding = YaxcTheme.paddings.section,
        shadowElevation = 8.dp,
        modifier = Modifier.testTag("links:empty"),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        color = YaxcTheme.extendedColors.accentSoft,
                        shape = MaterialTheme.shapes.small,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Link,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = stringResource(R.string.noLinksYet),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.noLinksYetHint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = YaxcTheme.extendedColors.textMuted,
                )
            }
        }
    }
}

@Composable
private fun LinkRow(
    link: Link,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val hostLabel = remember(link.type, link.address) {
        extractLinkHost(link)
    }
    val subtitle = buildLinkSubtitle(link)

    YaxcGlassPanel(
        modifier = Modifier.testTag("links:item:${link.id}"),
        shape = MaterialTheme.shapes.large,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        accentColor = if (link.isActive) {
            MaterialTheme.colorScheme.primary
        } else {
            YaxcTheme.extendedColors.textMuted
        },
        accentAlpha = if (link.isActive) 0.10f else 0.03f,
        shadowElevation = 8.dp,
        onClick = onEdit,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = link.name.ifBlank { stringResource(R.string.newSource) },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = YaxcTheme.extendedColors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!hostLabel.isNullOrBlank()) {
                    Text(
                        text = hostLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = YaxcTheme.extendedColors.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                YaxcActionBubble(
                    icon = Icons.Outlined.Edit,
                    onClick = onEdit,
                    containerSize = 36.dp,
                    iconSize = 18.dp,
                    contentDescription = stringResource(R.string.editIcon),
                    modifier = Modifier.testTag("links:edit:${link.id}"),
                )
                YaxcActionBubble(
                    icon = Icons.Outlined.DeleteOutline,
                    onClick = onDelete,
                    containerSize = 36.dp,
                    iconSize = 18.dp,
                    contentDescription = stringResource(R.string.deleteIcon),
                    modifier = Modifier.testTag("links:delete:${link.id}"),
                )
            }
        }
    }
}

@Composable
private fun buildLinkSubtitle(link: Link): String {
    val typeLabel = stringResource(link.type.titleRes)
    val intervalLabel = link.refreshIntervalMinutes
        ?.takeIf { it > 0 && link.type == Link.Type.Subscription }
        ?.let { minutes ->
            if (minutes % 60 == 0) {
                stringResource(R.string.linksRefreshHours, minutes / 60)
            } else {
                stringResource(R.string.linksRefreshMinutes, minutes)
            }
        }
    val refreshedLabel = link.lastRefreshedAt
        .takeIf { it > 0L }
        ?.let(::formatLinkRefreshedAt)
    return listOfNotNull(typeLabel, intervalLabel, refreshedLabel).joinToString(" • ")
}

private fun formatLinkRefreshedAt(epochMillis: Long): String {
    return Instant.ofEpochMilli(epochMillis)
        .atZone(ZoneId.systemDefault())
        .format(
            DateTimeFormatter
                .ofLocalizedDateTime(FormatStyle.SHORT)
                .withLocale(Locale.getDefault()),
        )
}

private fun extractLinkHost(link: Link): String? {
    if (link.type != Link.Type.Subscription) return null
    val trimmed = link.address.trim()
    if (trimmed.isEmpty()) return null
    val host = runCatching { URI(trimmed).host }.getOrNull()?.trim()
    return if (!host.isNullOrEmpty()) host else trimmed
}
