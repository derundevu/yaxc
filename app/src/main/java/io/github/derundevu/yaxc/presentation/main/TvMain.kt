package io.github.derundevu.yaxc.presentation.main

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.AltRoute
import androidx.compose.material.icons.automirrored.outlined.Subject
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.ToggleOn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.derundevu.yaxc.R
import io.github.derundevu.yaxc.dto.SubscriptionMetadata
import io.github.derundevu.yaxc.helper.AppUpdateUiState
import io.github.derundevu.yaxc.presentation.designsystem.YaxcShapeDefaults
import io.github.derundevu.yaxc.presentation.designsystem.YaxcTheme
import io.github.derundevu.yaxc.presentation.designsystem.YaxcTvTypographyScope
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcGlassPanel
import io.github.derundevu.yaxc.presentation.designsystem.components.yaxcTvFocus
import io.github.derundevu.yaxc.presentation.designsystem.yaxcIsLightTheme
import io.github.derundevu.yaxc.presentation.designsystem.yaxcSoftOnSurface
import io.github.derundevu.yaxc.presentation.root.AppVersionsPanel

@Composable
internal fun TvMainShell(
    tab: MainRootTab,
    onSelectTab: (MainRootTab) -> Unit,
    onAction: (MainAction) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (MainTvFocus) -> Unit,
) {
    val focus = remember { MainTvFocus() }
    val inputModeManager = LocalInputModeManager.current
    var pendingInitialFocus by remember { mutableIntStateOf(1) }

    BackHandler(enabled = tab != MainRootTab.Connect) {
        onSelectTab(MainRootTab.Connect)
        pendingInitialFocus = 1
    }

    LaunchedEffect(pendingInitialFocus, tab) {
        if (pendingInitialFocus > 0) {
            inputModeManager.requestInputMode(InputMode.Keyboard)
            if (tab == MainRootTab.Connect) {
                focus.connect.requestFocus()
            } else {
                focus.rail.requestFocus()
            }
            pendingInitialFocus = 0
        }
    }

    YaxcTvTypographyScope(enabled = true) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .background(YaxcTheme.backgroundBrush)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                // Enforce Android TV 5% overscan-safe margins (~48dp sides, 28dp top); panes own bottom scroll padding.
                .padding(start = 48.dp, end = 48.dp, top = 28.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            TvTopBar(
                tab = tab,
                onSelectTab = onSelectTab,
                focus = focus,
                onAction = onAction,
            )
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                content(focus)
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun TvTopBar(
    tab: MainRootTab,
    onSelectTab: (MainRootTab) -> Unit,
    focus: MainTvFocus,
    onAction: (MainAction) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        MainBrandPill()

        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            MainFloatingSurface(
                shape = YaxcTheme.shapes.pill,
                contentPadding = YaxcTheme.paddings.dense,
                shadowElevation = 14.dp,
            ) {
                val itemShape = YaxcTheme.shapes.pill
                Row(
                    modifier = Modifier
                        .selectableGroup()
                        .focusGroup()
                        .focusRestorer(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MainRootTab.entries.forEach { destination ->
                        val selected = tab == destination
                        val label = stringResource(
                            when (destination) {
                                MainRootTab.Connect -> R.string.rootTabConnect
                                MainRootTab.Routing -> R.string.rootTabRouting
                                MainRootTab.Settings -> R.string.rootTabSettings
                            },
                        )
                        val icon = when (destination) {
                            MainRootTab.Connect -> Icons.Outlined.ToggleOn
                            MainRootTab.Routing -> Icons.AutoMirrored.Outlined.AltRoute
                            MainRootTab.Settings -> Icons.Outlined.Settings
                        }
                        val itemColor = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            yaxcSoftOnSurface(darkAlpha = 0.72f, lightAlpha = 0.70f)
                        }
                        Surface(
                            modifier = Modifier
                                .heightIn(min = 46.dp)
                                .then(if (selected) Modifier.focusRequester(focus.rail) else Modifier)
                                .focusProperties {
                                    down = if (tab == MainRootTab.Connect) focus.connect else focus.content
                                }
                                .testTag("main:rail:${destination.name.lowercase()}")
                                .yaxcTvFocus(itemShape)
                                .clip(itemShape)
                                .selectable(selected = selected, role = Role.Tab) {
                                    onSelectTab(destination)
                                },
                            color = if (selected) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.96f)
                            } else {
                                Color.Transparent
                            },
                            contentColor = itemColor,
                            shape = itemShape,
                            border = if (selected) {
                                BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.22f))
                            } else {
                                null
                            },
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = null,
                                    tint = itemColor,
                                    modifier = Modifier.size(20.dp),
                                )
                                Text(
                                    text = label,
                                    color = itemColor,
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }

        MainAddActionButton(
            isTv = true,
            showDeviceTransfer = true,
            onAction = onAction,
            modifier = Modifier
                .testTag("main:adaptive:add")
                .focusRequester(focus.add)
                .focusProperties {
                    down = if (tab == MainRootTab.Connect) focus.profiles else focus.content
                },
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun TvConnectControlPane(
    isRunning: Boolean,
    isBlocked: Boolean,
    selectedSourceName: String,
    selectedSourceMetadata: SubscriptionMetadata?,
    selectedSourceLastRefreshedAt: Long?,
    selectedProfileName: String,
    pingState: MainPingState,
    focus: MainTvFocus,
    onToggleVpn: () -> Unit,
    onPingCurrent: () -> Unit,
    onOpenConnectionAnalytics: () -> Unit,
    onOpenConnectionInfo: () -> Unit,
    onRefresh: () -> Unit,
    onReconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = when {
        isBlocked -> MaterialTheme.colorScheme.error
        isRunning -> YaxcTheme.extendedColors.success
        else -> MaterialTheme.colorScheme.primary
    }
    val statusText = stringResource(
        when {
            isBlocked -> R.string.killSwitchBlocked
            isRunning -> R.string.mainStatusRunning
            else -> R.string.mainStatusStopped
        },
    )
    val subscriptionSummary = subscriptionCardSummary(
        metadata = selectedSourceMetadata,
        lastRefreshedAt = selectedSourceLastRefreshedAt,
    )
    val ctaShape = YaxcTheme.shapes.large
    val isLight = yaxcIsLightTheme()
    val ctaContainerColor = when {
        isBlocked -> MaterialTheme.colorScheme.errorContainer
        isRunning -> lerp(mainFloatingContainerColor(), YaxcTheme.extendedColors.success, if (isLight) 0.28f else 0.24f)
        else -> MaterialTheme.colorScheme.primaryContainer
    }
    val ctaContentColor = when {
        isBlocked -> MaterialTheme.colorScheme.onErrorContainer
        isRunning -> YaxcTheme.extendedColors.success
        else -> MaterialTheme.colorScheme.onPrimaryContainer
    }
    val ctaBorderColor = when {
        isBlocked -> MaterialTheme.colorScheme.error
        isRunning -> YaxcTheme.extendedColors.success.copy(alpha = 0.56f)
        else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.42f)
    }

    Column(
        modifier = modifier
            .fillMaxHeight()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 36.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        YaxcGlassPanel(
            shadowElevation = 12.dp,
            contentPadding = PaddingValues(20.dp),
            accentColor = accent,
            accentAlpha = if (isRunning || isBlocked) 0.22f else 0.12f,
            borderColor = accent.copy(alpha = if (isRunning || isBlocked) 0.44f else 0.22f),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(if (isRunning || isBlocked) accent else YaxcTheme.extendedColors.textMuted),
                    )
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.headlineSmall,
                        color = if (isBlocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    PingStateBadge(pingState = pingState)
                }

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = selectedSourceName,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = selectedProfileName.ifBlank { stringResource(R.string.mainNoSelectedProfile) },
                        style = MaterialTheme.typography.bodyLarge,
                        color = YaxcTheme.extendedColors.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!subscriptionSummary.isNullOrBlank()) {
                        Text(
                            text = subscriptionSummary,
                            style = MaterialTheme.typography.bodyMedium,
                            color = YaxcTheme.extendedColors.textMuted,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                selectedSourceMetadata?.let { metadata ->
                    SubscriptionTraffic(metadata)
                    metadata.announce?.takeIf { it.isNotBlank() }?.let { announce ->
                        Text(
                            text = announce,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("main:subscription-announce"),
                        )
                    }
                }

                if (isBlocked) {
                    TextButton(
                        onClick = onReconnect,
                        modifier = Modifier.yaxcTvFocus(CircleShape),
                    ) {
                        Text(stringResource(R.string.connectionRetry))
                    }
                }

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .heightIn(min = 58.dp)
                        .testTag("main:adaptive:connect")
                        .focusRequester(focus.connect)
                        .focusProperties {
                            up = focus.rail
                            right = focus.profiles
                        }
                        .yaxcTvFocus(ctaShape)
                        .clip(ctaShape)
                        .clickable(onClick = onToggleVpn),
                    shape = ctaShape,
                    color = ctaContainerColor,
                    contentColor = ctaContentColor,
                    border = BorderStroke(1.dp, ctaBorderColor),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.PowerSettingsNew,
                            contentDescription = null,
                            tint = ctaContentColor,
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(Modifier.size(10.dp))
                        Text(
                            text = stringResource(if (isRunning) R.string.vpnStop else R.string.vpnStart),
                            style = MaterialTheme.typography.titleMedium,
                            color = ctaContentColor,
                            maxLines = 1,
                        )
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .focusGroup()
                .focusRestorer(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TvQuickActionTile(
                    icon = Icons.Outlined.Speed,
                    label = stringResource(R.string.mainQuickActionPing),
                    onClick = onPingCurrent,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("main:connection-ping"),
                )
                TvQuickActionTile(
                    icon = Icons.Outlined.Info,
                    label = stringResource(R.string.mainConnectionInfo),
                    onClick = onOpenConnectionInfo,
                    modifier = Modifier
                        .weight(1f)
                        .focusProperties { right = focus.profiles },
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TvQuickActionTile(
                    icon = Icons.Outlined.Analytics,
                    label = stringResource(R.string.connectionAnalyticsTitle),
                    onClick = onOpenConnectionAnalytics,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("main:connection-analytics"),
                )
                TvQuickActionTile(
                    icon = Icons.Outlined.Refresh,
                    label = stringResource(R.string.refreshLinks),
                    onClick = onRefresh,
                    modifier = Modifier
                        .weight(1f)
                        .focusProperties { right = focus.profiles },
                )
            }
        }
    }
}

@Composable
private fun TvQuickActionTile(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    YaxcGlassPanel(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
        shadowElevation = 8.dp,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private data class TvSectionEntry(
    val key: String,
    val icon: ImageVector,
    val title: String,
    val description: String = "",
    val testTag: String? = null,
    val onClick: (() -> Unit)? = null,
    val detailContent: (@Composable () -> Unit)? = null,
)

@Composable
internal fun TvRoutingPane(
    focus: MainTvFocus,
    onOpenAppsRouting: () -> Unit,
    onOpenCoreRouting: () -> Unit,
    onOpenLiveRouting: () -> Unit,
) {
    val entries = listOf(
        TvSectionEntry(
            key = "live-routing",
            icon = Icons.AutoMirrored.Outlined.AltRoute,
            title = stringResource(R.string.liveRouting),
            description = stringResource(R.string.liveRoutingEntryLead),
            onClick = onOpenLiveRouting,
        ),
        TvSectionEntry(
            key = "apps-routing",
            icon = Icons.AutoMirrored.Outlined.AltRoute,
            title = stringResource(R.string.appsRouting),
            description = stringResource(R.string.appsRoutingLead),
            onClick = onOpenAppsRouting,
        ),
        TvSectionEntry(
            key = "core-routing",
            icon = Icons.AutoMirrored.Outlined.Subject,
            title = stringResource(R.string.coreRouting),
            description = stringResource(R.string.coreRoutingLead),
            onClick = onOpenCoreRouting,
        ),
    )

    TvMasterDetailPane(
        entries = entries,
        focus = focus,
    )
}

@Composable
internal fun TvSettingsPane(
    focus: MainTvFocus,
    appVersion: String,
    xrayVersion: String,
    tun2socksVersion: String,
    appUpdateState: AppUpdateUiState,
    onCheckAppUpdate: () -> Unit,
    onDownloadAppUpdate: () -> Unit,
    onInstallAppUpdate: () -> Unit,
    onOpenDeviceTransfer: () -> Unit,
    onOpenAssets: () -> Unit,
    onOpenLinks: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLogs: () -> Unit,
) {
    val entries = listOf(
        TvSectionEntry(
            key = "device-transfer",
            icon = Icons.Outlined.QrCodeScanner,
            title = stringResource(R.string.deviceTransferTitle),
            description = stringResource(R.string.deviceTransferIntro),
            onClick = onOpenDeviceTransfer,
        ),
        TvSectionEntry(
            key = "assets",
            icon = Icons.Outlined.FolderOpen,
            title = stringResource(R.string.assets),
            description = stringResource(R.string.assetsScreenLead),
            onClick = onOpenAssets,
        ),
        TvSectionEntry(
            key = "links",
            icon = Icons.Outlined.Link,
            title = stringResource(R.string.links),
            description = stringResource(R.string.linksScreenLead),
            onClick = onOpenLinks,
        ),
        TvSectionEntry(
            key = "preferences",
            icon = Icons.Outlined.Settings,
            title = stringResource(R.string.preferences),
            description = stringResource(R.string.settingsScreenLead),
            onClick = onOpenSettings,
        ),
        TvSectionEntry(
            key = "logs",
            icon = Icons.Outlined.Terminal,
            title = stringResource(R.string.logs),
            description = stringResource(R.string.logsScreenLead),
            onClick = onOpenLogs,
        ),
        TvSectionEntry(
            key = "about",
            icon = Icons.Outlined.Info,
            title = stringResource(R.string.tvSettingsAbout),
            testTag = "main:tv:settings:about",
            detailContent = {
                AppVersionsPanel(
                    appVersion = appVersion,
                    xrayVersion = xrayVersion,
                    tun2socksVersion = tun2socksVersion,
                    updateState = appUpdateState,
                    onCheckUpdate = onCheckAppUpdate,
                    onDownloadUpdate = onDownloadAppUpdate,
                    onInstallUpdate = onInstallAppUpdate,
                )
            },
        ),
    )

    TvMasterDetailPane(
        entries = entries,
        focus = focus,
    )
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun TvMasterDetailPane(
    entries: List<TvSectionEntry>,
    focus: MainTvFocus,
) {
    var selectedIndex by rememberSaveable { mutableIntStateOf(0) }
    val activeEntry = entries[selectedIndex.coerceIn(0, entries.lastIndex)]
    val detailFocus = remember { FocusRequester() }

    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        LazyColumn(
            modifier = Modifier
                .weight(0.44f)
                .fillMaxHeight()
                .focusGroup()
                .focusRestorer(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 16.dp, bottom = 28.dp),
        ) {
            itemsIndexed(entries, key = { _, entry -> entry.key }) { index, entry ->
                val isActive = index == selectedIndex
                val handleClick: () -> Unit = {
                    selectedIndex = index
                    entry.onClick?.invoke() ?: detailFocus.requestFocus()
                }
                YaxcGlassPanel(
                    onClick = handleClick,
                    shape = MaterialTheme.shapes.large,
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 13.dp),
                    accentAlpha = if (isActive) 0.18f else 0.06f,
                    borderColor = if (isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.44f) else Color.Unspecified,
                    shadowElevation = if (isActive) 12.dp else 6.dp,
                    modifier = Modifier
                        .heightIn(min = 56.dp)
                        .then(if (entry.testTag != null) Modifier.testTag(entry.testTag) else Modifier)
                        .then(if (index == 0) Modifier.focusRequester(focus.content) else Modifier)
                        .then(if (index == 0) Modifier.focusProperties { up = focus.rail } else Modifier)
                        .focusProperties { right = detailFocus }
                        .onFocusChanged { if (it.isFocused) selectedIndex = index },
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Icon(
                            imageVector = entry.icon,
                            contentDescription = null,
                            tint = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp),
                        )
                        Text(
                            text = entry.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .weight(0.56f)
                .fillMaxHeight()
                .focusGroup()
                .focusRestorer()
                .focusRequester(detailFocus)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val inlineDetail = activeEntry.detailContent
            if (inlineDetail != null) {
                inlineDetail()
            } else {
                YaxcGlassPanel(
                    shadowElevation = 12.dp,
                    contentPadding = PaddingValues(24.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            Icon(
                                imageVector = activeEntry.icon,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(32.dp),
                            )
                            Text(
                                text = activeEntry.title,
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        Text(
                            text = activeEntry.description,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        activeEntry.onClick?.let { openSection ->
                            Button(
                                onClick = openSection,
                                shape = CircleShape,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                ),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.42f)),
                                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .yaxcTvFocus(CircleShape),
                            ) {
                                Text(
                                    text = stringResource(R.string.tvOpenSection),
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
