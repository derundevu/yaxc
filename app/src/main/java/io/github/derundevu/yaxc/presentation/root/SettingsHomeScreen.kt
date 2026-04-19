package io.github.derundevu.yaxc.presentation.root

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.derundevu.yaxc.R
import io.github.derundevu.yaxc.helper.AppUpdateUiState
import io.github.derundevu.yaxc.presentation.designsystem.YaxcTheme
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcGlassPanel

@Composable
fun SettingsHomeScreen(
    appVersion: String,
    xrayVersion: String,
    tun2socksVersion: String,
    appUpdateState: AppUpdateUiState,
    onOpenAssets: () -> Unit,
    onOpenLinks: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenSettings: () -> Unit,
    onDownloadAppUpdate: () -> Unit,
    onInstallAppUpdate: () -> Unit,
    onSelectDestination: (RootDestination) -> Unit,
) {
    val spacing = YaxcTheme.spacing
    val density = LocalDensity.current
    val navigationBarBottom = WindowInsets.navigationBars.getBottom(density)
    val gestureNavigation = navigationBarBottom <= with(density) { 32.dp.roundToPx() }
    val reservedBottomInset = if (gestureNavigation) 0.dp else with(density) { navigationBarBottom.toDp() }

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        contentWindowInsets = WindowInsets.safeDrawing.only(
            WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
        ),
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(YaxcTheme.backgroundBrush),
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = spacing.md)
                    .padding(top = spacing.md),
                verticalArrangement = Arrangement.spacedBy(spacing.md),
                contentPadding = PaddingValues(bottom = spacing.xl + reservedBottomInset + 88.dp),
            ) {
                item {
                    Text(
                        text = textResource(R.string.settings),
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                }
                item {
                    Text(
                        text = textResource(R.string.settingsScreenLead),
                        style = MaterialTheme.typography.bodyLarge,
                        color = YaxcTheme.extendedColors.textMuted,
                    )
                }
                item {
                    RootSectionCard(
                        icon = Icons.Outlined.FolderOpen,
                        title = textResource(R.string.assets),
                        description = textResource(R.string.assetsScreenLead),
                        onClick = onOpenAssets,
                    )
                }
                item {
                    RootSectionCard(
                        icon = Icons.Outlined.Link,
                        title = textResource(R.string.links),
                        description = textResource(R.string.linksScreenLead),
                        onClick = onOpenLinks,
                    )
                }
                item {
                    RootSectionCard(
                        icon = Icons.Outlined.Terminal,
                        title = textResource(R.string.logs),
                        description = textResource(R.string.logsScreenLead),
                        onClick = onOpenLogs,
                    )
                }
                item {
                    RootSectionCard(
                        icon = Icons.Outlined.Settings,
                        title = textResource(R.string.settings),
                        description = textResource(R.string.settingsScreenLead),
                        onClick = onOpenSettings,
                    )
                }
                item {
                    YaxcGlassPanel {
                        androidx.compose.foundation.layout.Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            VersionRow(textResource(R.string.appFullName), appVersion)
                            AppUpdatePanel(
                                state = appUpdateState,
                                onDownload = onDownloadAppUpdate,
                                onInstall = onInstallAppUpdate,
                            )
                            VersionRow(textResource(R.string.xrayLabel), xrayVersion, modifier = Modifier.padding(top = 12.dp))
                            VersionRow(textResource(R.string.tun2socksLabel), tun2socksVersion, modifier = Modifier.padding(top = 10.dp))
                            Text(
                                text = textResource(R.string.madeWithPeople),
                                style = MaterialTheme.typography.bodySmall,
                                color = YaxcTheme.extendedColors.textMuted,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 16.dp),
                            )
                        }
                    }
                }
            }

            RootBottomBar(
                selectedDestination = RootDestination.Settings,
                onSelectDestination = onSelectDestination,
                modifier = Modifier
                    .align(androidx.compose.ui.Alignment.BottomCenter)
                    .padding(horizontal = spacing.lg)
                    .padding(bottom = spacing.lg + reservedBottomInset),
            )
        }
    }
}

@Composable
private fun VersionRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    androidx.compose.foundation.layout.Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = YaxcTheme.extendedColors.textMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun textResource(id: Int): String {
    return androidx.compose.ui.res.stringResource(id)
}
