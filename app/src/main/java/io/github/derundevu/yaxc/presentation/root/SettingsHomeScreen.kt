package io.github.derundevu.yaxc.presentation.root

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
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
import androidx.compose.ui.unit.dp
import io.github.derundevu.yaxc.R
import io.github.derundevu.yaxc.helper.AppUpdateUiState
import io.github.derundevu.yaxc.presentation.designsystem.YaxcTheme

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
    onCheckAppUpdate: () -> Unit,
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
        contentColor = MaterialTheme.colorScheme.onBackground,
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
                settingsHomeEntries(
                    onOpenAssets = onOpenAssets,
                    onOpenLinks = onOpenLinks,
                    onOpenLogs = onOpenLogs,
                    onOpenSettings = onOpenSettings,
                )
                item {
                    AppVersionsPanel(
                        appVersion = appVersion,
                        xrayVersion = xrayVersion,
                        tun2socksVersion = tun2socksVersion,
                        updateState = appUpdateState,
                        onCheckUpdate = onCheckAppUpdate,
                        onDownloadUpdate = onDownloadAppUpdate,
                        onInstallUpdate = onInstallAppUpdate,
                    )
                }
            }

            RootBottomBar(
                selectedDestination = RootDestination.Settings,
                onSelectDestination = onSelectDestination,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = spacing.lg)
                    .padding(bottom = spacing.lg + reservedBottomInset),
            )
        }
    }
}

internal fun LazyListScope.settingsHomeEntries(
    onOpenAssets: () -> Unit,
    onOpenLinks: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenSettings: () -> Unit,
    showChevron: Boolean = false,
) {
    item {
        RootSectionCard(
            icon = Icons.Outlined.FolderOpen,
            title = textResource(R.string.assets),
            description = textResource(R.string.assetsScreenLead),
            onClick = onOpenAssets,
            showChevron = showChevron,
        )
    }
    item {
        RootSectionCard(
            icon = Icons.Outlined.Link,
            title = textResource(R.string.links),
            description = textResource(R.string.linksScreenLead),
            onClick = onOpenLinks,
            showChevron = showChevron,
        )
    }
    item {
        RootSectionCard(
            icon = Icons.Outlined.Settings,
            title = textResource(R.string.preferences),
            description = textResource(R.string.settingsScreenLead),
            onClick = onOpenSettings,
            showChevron = showChevron,
        )
    }
    item {
        RootSectionCard(
            icon = Icons.Outlined.Terminal,
            title = textResource(R.string.logs),
            description = textResource(R.string.logsScreenLead),
            onClick = onOpenLogs,
            showChevron = showChevron,
        )
    }
}

@Composable
private fun textResource(id: Int): String {
    return androidx.compose.ui.res.stringResource(id)
}
