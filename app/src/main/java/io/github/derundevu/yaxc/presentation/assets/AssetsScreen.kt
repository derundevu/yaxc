package io.github.derundevu.yaxc.presentation.assets

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.derundevu.yaxc.R
import io.github.derundevu.yaxc.Settings
import io.github.derundevu.yaxc.presentation.designsystem.YaxcTheme
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcCard
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcScaffold

@Immutable
data class AssetCardState(
    val title: String,
    val value: String = "",
    val details: String? = null,
    val note: String? = null,
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
    selectedGeoProvider: Settings.GeoResourcesProvider,
    customGeoIpUrl: String,
    customGeoSiteUrl: String,
    onBack: () -> Unit,
    onGeoProviderSelected: (Settings.GeoResourcesProvider) -> Unit,
    onCustomGeoIpUrlChange: (String) -> Unit,
    onCustomGeoSiteUrlChange: (String) -> Unit,
    onApplyCustomGeoUrls: () -> Unit,
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.md)
                .padding(top = spacing.md, bottom = spacing.xl),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Text(
                text = textResource(R.string.assetsScreenLead),
                style = MaterialTheme.typography.bodyLarge,
                color = YaxcTheme.extendedColors.textMuted,
            )

            GeoResourcesProviderSelector(
                selectedProvider = selectedGeoProvider,
                customGeoIpUrl = customGeoIpUrl,
                customGeoSiteUrl = customGeoSiteUrl,
                onProviderSelected = onGeoProviderSelected,
                onCustomGeoIpUrlChange = onCustomGeoIpUrlChange,
                onCustomGeoSiteUrlChange = onCustomGeoSiteUrlChange,
                onApplyCustomGeoUrls = onApplyCustomGeoUrls,
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
    primaryIcon: ImageVector = Icons.Outlined.Download,
) {
    val spacing = YaxcTheme.spacing

    AssetsCard {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(spacing.xs),
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
                    )
                    state.details?.takeIf { it.isNotBlank() }?.let { details ->
                        Text(
                            text = details,
                            style = MaterialTheme.typography.bodyMedium,
                            color = YaxcTheme.extendedColors.textMuted,
                        )
                    }
                    state.note?.takeIf { it.isNotBlank() }?.let { note ->
                        Text(
                            text = note,
                            style = MaterialTheme.typography.bodyMedium,
                            color = YaxcTheme.extendedColors.warning,
                        )
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                ) {
                    ActionIcon(
                        icon = primaryIcon,
                        onClick = onPrimaryAction,
                    )
                    onSecondaryAction?.let { secondaryAction ->
                        ActionIcon(
                            icon = Icons.Outlined.FileOpen,
                            onClick = secondaryAction,
                        )
                    }
                    if (state.isInstalled) {
                        ActionIcon(
                            icon = Icons.Outlined.Delete,
                            onClick = onDelete,
                        )
                    }
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
private fun GeoResourcesProviderSelector(
    selectedProvider: Settings.GeoResourcesProvider,
    customGeoIpUrl: String,
    customGeoSiteUrl: String,
    onProviderSelected: (Settings.GeoResourcesProvider) -> Unit,
    onCustomGeoIpUrlChange: (String) -> Unit,
    onCustomGeoSiteUrlChange: (String) -> Unit,
    onApplyCustomGeoUrls: () -> Unit,
) {
    val spacing = YaxcTheme.spacing

    AssetsCard {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Text(
                text = textResource(R.string.assetsGeoProviderTitle),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = textResource(R.string.assetsGeoProviderLead),
                style = MaterialTheme.typography.bodyMedium,
                color = YaxcTheme.extendedColors.textMuted,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    GeoProviderButton(
                        label = textResource(R.string.assetsGeoProviderRunetFreedom),
                        selected = selectedProvider == Settings.GeoResourcesProvider.RunetFreedom,
                        onClick = { onProviderSelected(Settings.GeoResourcesProvider.RunetFreedom) },
                    )
                }
                Box(modifier = Modifier.weight(1f)) {
                    GeoProviderButton(
                        label = textResource(R.string.assetsGeoProviderLoyalSoldier),
                        selected = selectedProvider == Settings.GeoResourcesProvider.LoyalSoldier,
                        onClick = { onProviderSelected(Settings.GeoResourcesProvider.LoyalSoldier) },
                    )
                }
                Box(modifier = Modifier.weight(1f)) {
                    GeoProviderButton(
                        label = textResource(R.string.assetsGeoProviderCustom),
                        selected = selectedProvider == Settings.GeoResourcesProvider.Custom,
                        onClick = { onProviderSelected(Settings.GeoResourcesProvider.Custom) },
                    )
                }
            }

            if (selectedProvider == Settings.GeoResourcesProvider.Custom) {
                CustomGeoUrlsEditor(
                    geoIpUrl = customGeoIpUrl,
                    geoSiteUrl = customGeoSiteUrl,
                    onGeoIpUrlChange = onCustomGeoIpUrlChange,
                    onGeoSiteUrlChange = onCustomGeoSiteUrlChange,
                    onApply = onApplyCustomGeoUrls,
                )
            }
        }
    }
}

@Composable
private fun CustomGeoUrlsEditor(
    geoIpUrl: String,
    geoSiteUrl: String,
    onGeoIpUrlChange: (String) -> Unit,
    onGeoSiteUrlChange: (String) -> Unit,
    onApply: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(YaxcTheme.spacing.sm),
    ) {
        Text(
            text = textResource(R.string.assetsGeoCustomLead),
            style = MaterialTheme.typography.bodyMedium,
            color = YaxcTheme.extendedColors.textMuted,
        )
        OutlinedTextField(
            value = geoIpUrl,
            onValueChange = onGeoIpUrlChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(text = textResource(R.string.assetsGeoIpUrlLabel)) },
            singleLine = true,
        )
        OutlinedTextField(
            value = geoSiteUrl,
            onValueChange = onGeoSiteUrlChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(text = textResource(R.string.assetsGeoSiteUrlLabel)) },
            singleLine = true,
        )
        FilledTonalButton(
            onClick = onApply,
            modifier = Modifier.align(Alignment.End),
        ) {
            Text(text = textResource(R.string.assetsApplyCustomGeoUrls))
        }
    }
}

@Composable
private fun AssetsCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    YaxcCard(
        modifier = modifier,
        contentPadding = YaxcTheme.paddings.section,
        content = content,
    )
}

@Composable
private fun GeoProviderButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val selectedContainer = YaxcTheme.extendedColors.success.copy(alpha = 0.22f)
    val selectedBorder = YaxcTheme.extendedColors.success.copy(alpha = 0.54f)

    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = if (selected) {
                selectedContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
            },
            contentColor = if (selected) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        ),
        border = BorderStroke(
            width = 1.dp,
            color = if (selected) selectedBorder else YaxcTheme.extendedColors.cardBorder,
        ),
    ) {
        Text(
            text = label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ActionIcon(
    icon: ImageVector,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        color = Color.White.copy(alpha = 0.08f),
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
    ) {
        Box(
            modifier = Modifier.size(40.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.76f),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun textResource(id: Int, vararg args: Any): String {
    return androidx.compose.ui.res.stringResource(id, *args)
}
