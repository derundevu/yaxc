package io.github.derundevu.yaxc.presentation.main

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.AltRoute
import androidx.compose.material.icons.automirrored.outlined.Subject
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.ToggleOff
import androidx.compose.material.icons.outlined.ToggleOn
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material.icons.outlined.WifiTethering
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.navigationBars
import io.github.derundevu.yaxc.R
import io.github.derundevu.yaxc.database.Link
import io.github.derundevu.yaxc.presentation.main.MainProfileItem
import io.github.derundevu.yaxc.presentation.designsystem.YaxcTheme
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcCard
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    tabs: List<Link>,
    selectedTabId: Long,
    isRunning: Boolean,
    selectedSourceName: String,
    selectedProfileName: String,
    selectedServerLabel: String,
    pingState: MainPingState,
    profiles: List<MainProfileItem>,
    selectedProfileId: Long,
    profilesCount: Int,
    appVersion: String,
    xrayVersion: String,
    tun2socksVersion: String,
    onAction: (MainAction) -> Unit,
) {
    val spacing = YaxcTheme.spacing
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var actionsExpanded by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val navigationBarBottom = WindowInsets.navigationBars.getBottom(density)
    val gestureNavigation = navigationBarBottom <= with(density) { 32.dp.roundToPx() }
    val reservedBottomInset = if (gestureNavigation) 0.dp else with(density) { navigationBarBottom.toDp() }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.padding(end = spacing.md),
                drawerContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = spacing.md, vertical = spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(spacing.md),
                ) {
                    Text(
                        text = textResource(R.string.appName),
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = textResource(R.string.mainDrawerLead),
                        style = MaterialTheme.typography.bodyMedium,
                        color = YaxcTheme.extendedColors.textMuted,
                    )

                    MainDrawerItem(Icons.Outlined.FolderOpen, textResource(R.string.assets)) {
                        scope.launch { drawerState.close() }
                        onAction(MainAction.OpenAssetsClicked)
                    }
                    MainDrawerItem(Icons.Outlined.Link, textResource(R.string.links)) {
                        scope.launch { drawerState.close() }
                        onAction(MainAction.OpenLinksClicked)
                    }
                    MainDrawerItem(Icons.Outlined.Terminal, textResource(R.string.logs)) {
                        scope.launch { drawerState.close() }
                        onAction(MainAction.OpenLogsClicked)
                    }
                    MainDrawerItem(Icons.AutoMirrored.Outlined.AltRoute, textResource(R.string.appsRouting)) {
                        scope.launch { drawerState.close() }
                        onAction(MainAction.OpenAppsRoutingClicked)
                    }
                    MainDrawerItem(Icons.AutoMirrored.Outlined.Subject, textResource(R.string.configs)) {
                        scope.launch { drawerState.close() }
                        onAction(MainAction.OpenConfigsClicked)
                    }
                    MainDrawerItem(Icons.Outlined.Settings, textResource(R.string.settings)) {
                        scope.launch { drawerState.close() }
                        onAction(MainAction.OpenSettingsClicked)
                    }

                    Box(modifier = Modifier.weight(1f))

                    YaxcCard {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            VersionRow(textResource(R.string.appFullName), appVersion)
                            VersionRow(textResource(R.string.xrayLabel), xrayVersion)
                            VersionRow(textResource(R.string.tun2socksLabel), tun2socksVersion)
                        }
                    }
                }
            }
        },
        ) {
        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing.only(
                WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
            ),
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            topBar = {
                TopAppBar(
                    title = { Text(text = textResource(R.string.appName)) },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(
                                imageVector = Icons.Outlined.Menu,
                                contentDescription = textResource(R.string.drawerOpen),
                            )
                        }
                    },
                    actions = {
                        Box {
                            IconButton(onClick = { actionsExpanded = true }) {
                                Icon(
                                    imageVector = Icons.Outlined.Add,
                                    contentDescription = textResource(R.string.newProfile),
                                )
                            }
                            DropdownMenu(
                                expanded = actionsExpanded,
                                onDismissRequest = { actionsExpanded = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text(text = textResource(R.string.newProfile)) },
                                    onClick = {
                                        actionsExpanded = false
                                        onAction(MainAction.NewProfileClicked)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(text = textResource(R.string.scanQrCode)) },
                                    onClick = {
                                        actionsExpanded = false
                                        onAction(MainAction.ScanQrCodeClicked)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(text = textResource(R.string.fromClipboard)) },
                                    onClick = {
                                        actionsExpanded = false
                                        onAction(MainAction.ImportFromClipboardClicked)
                                    },
                                )
                            }
                        }
                    },
                )
            },
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(YaxcTheme.backgroundBrush),
                    contentPadding = PaddingValues(
                        start = spacing.md,
                        end = spacing.md,
                        top = spacing.md,
                        bottom = 220.dp + reservedBottomInset,
                    ),
                    verticalArrangement = Arrangement.spacedBy(spacing.md),
                ) {
                    items(
                        items = tabs,
                        key = { it.id },
                    ) { source ->
                        SourceGroupCard(
                            source = source,
                            isExpanded = source.id == selectedTabId,
                            profiles = if (source.id == selectedTabId) profiles else emptyList(),
                            selectedProfileId = selectedProfileId,
                            onToggleExpanded = { onAction(MainAction.SelectTab(source.id)) },
                            onRefresh = { onAction(MainAction.RefreshSourceClicked(source.id)) },
                            onPingAll = { onAction(MainAction.PingSourceClicked(source.id)) },
                            onSelectProfile = { onAction(MainAction.SelectProfile(it.profile.id)) },
                            onEditProfile = { onAction(MainAction.EditProfile(it.profile)) },
                            onDeleteProfile = { onAction(MainAction.RequestDeleteProfile(it.profile)) },
                        )
                    }

                    if (tabs.isEmpty()) {
                        item {
                            EmptySourcesCard()
                        }
                    }

                    item {
                        Text(
                            text = textResource(R.string.mainProfilesCount, profilesCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = YaxcTheme.extendedColors.textMuted,
                        )
                    }
                }

                BottomControlBar(
                    isRunning = isRunning,
                    selectedSourceName = selectedSourceName,
                    selectedProfileName = selectedProfileName,
                    selectedServerLabel = selectedServerLabel,
                    pingState = pingState,
                    onToggleVpn = { onAction(MainAction.ToggleVpnClicked) },
                    onPingCurrent = { onAction(MainAction.PingClicked) },
                    onPingAll = { onAction(MainAction.PingAllProfilesClicked) },
                    onRefreshSource = {
                        if (selectedTabId != 0L) onAction(MainAction.RefreshSourceClicked(selectedTabId))
                        else onAction(MainAction.RefreshLinksClicked)
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = reservedBottomInset),
                )
            }
        }
    }
}

@Composable
private fun SourceGroupCard(
    source: Link,
    isExpanded: Boolean,
    profiles: List<MainProfileItem>,
    selectedProfileId: Long,
    onToggleExpanded: () -> Unit,
    onRefresh: () -> Unit,
    onPingAll: () -> Unit,
    onSelectProfile: (MainProfileItem) -> Unit,
    onEditProfile: (MainProfileItem) -> Unit,
    onDeleteProfile: (MainProfileItem) -> Unit,
) {
    YaxcCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onToggleExpanded)
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = source.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = Icons.Outlined.UnfoldMore,
                    contentDescription = null,
                    tint = YaxcTheme.extendedColors.textMuted,
                )
            }

            ActionBubble(
                icon = Icons.Outlined.WifiTethering,
                onClick = onPingAll,
            )
            ActionBubble(
                icon = Icons.Outlined.Refresh,
                onClick = onRefresh,
            )
        }

        if (isExpanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                profiles.forEach { profile ->
                    ProfileCard(
                        profile = profile,
                        isSelected = profile.profile.id == selectedProfileId,
                        onSelect = { onSelectProfile(profile) },
                        onEdit = { onEditProfile(profile) },
                        onDelete = { onDeleteProfile(profile) },
                    )
                }
                if (profiles.isEmpty()) {
                    Text(
                        text = textResource(R.string.mainNoProfilesInSource),
                        style = MaterialTheme.typography.bodyMedium,
                        color = YaxcTheme.extendedColors.textMuted,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileCard(
    profile: MainProfileItem,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect),
        color = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f)
        } else {
            MaterialTheme.colorScheme.surface.copy(alpha = 0.78f)
        },
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, YaxcTheme.extendedColors.cardBorder),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        var actionsExpanded by remember { mutableStateOf(false) }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .padding(top = 2.dp),
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = if (isSelected) {
                        YaxcTheme.extendedColors.success
                    } else {
                        YaxcTheme.extendedColors.textMuted
                    },
                    shape = MaterialTheme.shapes.small,
                ) {}
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = profile.profile.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = profile.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f)
                    } else {
                        YaxcTheme.extendedColors.textMuted
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Box(modifier = Modifier.padding(start = 4.dp)) {
                ActionBubble(
                    icon = Icons.Outlined.MoreVert,
                    onClick = { actionsExpanded = true },
                )
                DropdownMenu(
                    expanded = actionsExpanded,
                    onDismissRequest = { actionsExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(text = textResource(R.string.editProfile)) },
                        onClick = {
                            actionsExpanded = false
                            onEdit()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(text = textResource(R.string.deleteProfile)) },
                        onClick = {
                            actionsExpanded = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun BottomControlBar(
    isRunning: Boolean,
    selectedSourceName: String,
    selectedProfileName: String,
    selectedServerLabel: String,
    pingState: MainPingState,
    onToggleVpn: () -> Unit,
    onPingCurrent: () -> Unit,
    onPingAll: () -> Unit,
    onRefreshSource: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = YaxcTheme.spacing

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.lg)
            .padding(top = spacing.sm, bottom = spacing.lg),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.96f),
            shape = MaterialTheme.shapes.extraLarge,
            border = BorderStroke(1.dp, YaxcTheme.extendedColors.cardBorder),
            tonalElevation = 0.dp,
            shadowElevation = 16.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = textResource(R.string.mainTrafficPlaceholder),
                        style = MaterialTheme.typography.bodySmall,
                        color = YaxcTheme.extendedColors.textMuted,
                        modifier = Modifier.weight(1f),
                    )

                    Surface(
                        modifier = Modifier.clickable(onClick = onToggleVpn),
                        color = if (isRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.extraLarge,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = if (isRunning) Icons.Outlined.ToggleOn else Icons.Outlined.ToggleOff,
                                contentDescription = null,
                                tint = if (isRunning) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = if (isRunning) textResource(R.string.vpnStop) else textResource(R.string.vpnStart),
                                style = MaterialTheme.typography.labelLarge,
                                color = if (isRunning) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    Text(
                        text = selectedServerLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = YaxcTheme.extendedColors.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(start = 12.dp),
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clickable(onClick = onPingCurrent)
                            .padding(vertical = 2.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = selectedSourceName,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = selectedProfileName.ifBlank { textResource(R.string.mainNoSelectedProfile) },
                                style = MaterialTheme.typography.bodyMedium,
                                color = YaxcTheme.extendedColors.textMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            PingStateBadge(pingState = pingState)
                        }
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ActionBubble(Icons.Outlined.WifiTethering, onPingAll)
                        ActionBubble(Icons.Outlined.Refresh, onRefreshSource)
                    }
                }
            }
        }
    }
}

@Composable
private fun PingStateBadge(
    pingState: MainPingState,
) {
    when (pingState) {
        MainPingState.Idle -> {
            Text(
                text = textResource(R.string.noValue),
                style = MaterialTheme.typography.bodySmall,
                color = YaxcTheme.extendedColors.textMuted,
            )
        }

        MainPingState.Loading -> {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
            )
        }

        is MainPingState.Success -> {
            Text(
                text = pingState.label,
                style = MaterialTheme.typography.bodySmall,
                color = YaxcTheme.extendedColors.success,
            )
        }
    }
}

@Composable
private fun EmptySourcesCard() {
    YaxcCard {
        Text(
            text = textResource(R.string.mainNoSources),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = textResource(R.string.mainNoSourcesHint),
            style = MaterialTheme.typography.bodyMedium,
            color = YaxcTheme.extendedColors.textMuted,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun ActionBubble(
    icon: ImageVector,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.large,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Box(
            modifier = Modifier.size(40.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MainDrawerItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    NavigationDrawerItem(
        label = { Text(text = label) },
        selected = false,
        onClick = onClick,
        icon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
            )
        },
        colors = NavigationDrawerItemDefaults.colors(
            unselectedContainerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.6f),
        ),
    )
}

@Composable
private fun VersionRow(
    label: String,
    value: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = YaxcTheme.extendedColors.textMuted,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun textResource(id: Int): String = stringResource(id)

@Composable
private fun textResource(id: Int, arg0: Int): String = stringResource(id, arg0)
