package io.github.derundevu.yaxc.presentation.assets

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.derundevu.yaxc.R
import io.github.derundevu.yaxc.presentation.designsystem.YaxcTheme
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcScaffold

@Immutable
data class AssetCardState(
    val title: String,
    val value: String = "",
    val isInstalled: Boolean = false,
    val isLoading: Boolean = false,
    val progress: Int = 0,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssetsScreen(
    geoIpState: AssetCardState,
    geoSiteState: AssetCardState,
    xrayCoreState: AssetCardState,
    onBack: () -> Unit,
    onGeoIpDownload: () -> Unit,
    onGeoIpPick: () -> Unit,
    onGeoIpDelete: () -> Unit,
    onGeoSiteDownload: () -> Unit,
    onGeoSitePick: () -> Unit,
    onGeoSiteDelete: () -> Unit,
    onXrayCorePick: () -> Unit,
    onXrayCoreDelete: () -> Unit,
) {
    val spacing = YaxcTheme.spacing

    YaxcScaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = textResource(R.string.assets)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = null,
                        )
                    }
                },
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = spacing.md)
                .padding(top = spacing.md, bottom = spacing.xl),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Text(
                text = textResource(R.string.assetsScreenLead),
                style = MaterialTheme.typography.bodyLarge,
                color = YaxcTheme.extendedColors.textMuted,
            )

            AssetRow(
                state = geoIpState,
                onPrimaryAction = onGeoIpDownload,
                onSecondaryAction = onGeoIpPick,
                onDelete = onGeoIpDelete,
            )
            AssetRow(
                state = geoSiteState,
                onPrimaryAction = onGeoSiteDownload,
                onSecondaryAction = onGeoSitePick,
                onDelete = onGeoSiteDelete,
            )
            AssetRow(
                state = xrayCoreState,
                onPrimaryAction = onXrayCorePick,
                onSecondaryAction = null,
                onDelete = onXrayCoreDelete,
                primaryIcon = Icons.Outlined.FileOpen,
            )
        }
    }
}

@Composable
private fun AssetRow(
    state: AssetCardState,
    onPrimaryAction: () -> Unit,
    onSecondaryAction: (() -> Unit)?,
    onDelete: () -> Unit,
    primaryIcon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Outlined.Download,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        shape = MaterialTheme.shapes.large,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = BorderStroke(1.dp, YaxcTheme.extendedColors.cardBorder),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = state.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = state.value,
                        style = MaterialTheme.typography.bodyMedium,
                        color = YaxcTheme.extendedColors.textMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                ActionIcon(
                    icon = if (state.isInstalled) Icons.Outlined.Done else primaryIcon,
                    onClick = onPrimaryAction,
                )
                if (!state.isInstalled && onSecondaryAction != null) {
                    ActionIcon(
                        icon = Icons.Outlined.FileOpen,
                        onClick = onSecondaryAction,
                    )
                }
                if (state.isInstalled) {
                    ActionIcon(
                        icon = Icons.Outlined.Delete,
                        onClick = onDelete,
                    )
                }
            }

            if (state.isLoading) {
                LinearProgressIndicator(
                    progress = { state.progress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun ActionIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun textResource(id: Int): String {
    return androidx.compose.ui.res.stringResource(id)
}
