package io.github.derundevu.yaxc.presentation.main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.AltRoute
import androidx.compose.material.icons.automirrored.outlined.ArrowForwardIos
import androidx.compose.material.icons.automirrored.outlined.Subject
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.ToggleOn
import androidx.compose.material.icons.outlined.WifiTethering
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.scale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import io.github.derundevu.yaxc.R
import io.github.derundevu.yaxc.database.Link
import io.github.derundevu.yaxc.helper.AppUpdateUiState
import io.github.derundevu.yaxc.presentation.designsystem.YaxcTheme
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcGlassPanel
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcLiquidDropdownMenu
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcLiquidDropdownMenuItem
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcLiquidSurface
import io.github.derundevu.yaxc.presentation.root.AppUpdatePanel
import kotlinx.coroutines.launch
import kotlin.math.abs

private enum class MainRootTab {
    Connect,
    Routing,
    Settings,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    tabs: List<Link>,
    selectedTabId: Long,
    selectedSourceId: Long,
    isRunning: Boolean,
    selectedSourceName: String,
    selectedProfileName: String,
    selectedServerLabel: String,
    pingState: MainPingState,
    profiles: List<MainProfileItem>,
    selectedProfileId: Long,
    profilesCount: Int,
    activeBatchPingSourceId: Long?,
    appVersion: String,
    xrayVersion: String,
    tun2socksVersion: String,
    appUpdateState: AppUpdateUiState,
    onDownloadAppUpdate: () -> Unit,
    onInstallAppUpdate: () -> Unit,
    onAction: (MainAction) -> Unit,
) {
    val spacing = YaxcTheme.spacing
    val density = LocalDensity.current
    val navigationBarBottom = WindowInsets.navigationBars.getBottom(density)
    val gestureNavigation = navigationBarBottom <= with(density) { 32.dp.roundToPx() }
    val reservedBottomInset = if (gestureNavigation) 0.dp else with(density) { navigationBarBottom.toDp() }
    val connectBottomPadding = 162.dp + reservedBottomInset
    val tabBottomPadding = 112.dp + reservedBottomInset
    val connectListState = rememberLazyListState()
    val backdropBaseColor = MaterialTheme.colorScheme.background
    val backdrop = rememberLayerBackdrop {
        drawRect(backdropBaseColor)
        drawContent()
    }
    val pagerState = rememberPagerState(
        initialPage = MainRootTab.Connect.ordinal,
        pageCount = { MainRootTab.entries.size },
    )
    val rootTab = MainRootTab.entries[pagerState.currentPage]
    val coroutineScope = rememberCoroutineScope()
    val shouldCollapseControls by remember(connectListState, density) {
        derivedStateOf {
            val collapseTriggerPx = with(density) { 124.dp.roundToPx() }
            connectListState.firstVisibleItemIndex > 0 ||
                connectListState.firstVisibleItemScrollOffset >= collapseTriggerPx
        }
    }
    val collapseProgress by animateFloatAsState(
        targetValue = if (shouldCollapseControls) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.9f, stiffness = 520f),
        label = "main_control_bar_collapse",
    )

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing.only(
            WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
        ),
        containerColor = Color.Transparent,
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(YaxcTheme.backgroundBrush),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(YaxcTheme.backgroundBrush)
                        .layerBackdrop(backdrop = backdrop),
                ) {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier
                            .fillMaxSize()
                            .clipToBounds(),
                        beyondViewportPageCount = 1,
                    ) { page ->
                        when (MainRootTab.entries[page]) {
                            MainRootTab.Connect -> {
                                ConnectContent(
                                    tabs = tabs,
                                    selectedTabId = selectedTabId,
                                    selectedSourceId = selectedSourceId,
                                    isRunning = isRunning,
                                    selectedSourceName = selectedSourceName,
                                    selectedProfileName = selectedProfileName,
                                    selectedServerLabel = selectedServerLabel,
                                    pingState = pingState,
                                    profiles = profiles,
                                    selectedProfileId = selectedProfileId,
                                    profilesCount = profilesCount,
                                    activeBatchPingSourceId = activeBatchPingSourceId,
                                    listState = connectListState,
                                    collapseProgress = collapseProgress,
                                    onAction = onAction,
                                    topPadding = 86.dp,
                                    bottomPadding = connectBottomPadding,
                                )
                            }

                            MainRootTab.Routing -> {
                                RoutingContent(
                                    topPadding = 86.dp,
                                    bottomPadding = tabBottomPadding,
                                    onOpenAppsRouting = { onAction(MainAction.OpenAppsRoutingClicked) },
                                    onOpenCoreRouting = { onAction(MainAction.OpenCoreRoutingClicked) },
                                )
                            }

                            MainRootTab.Settings -> {
                                SettingsContent(
                                    appVersion = appVersion,
                                    xrayVersion = xrayVersion,
                                    tun2socksVersion = tun2socksVersion,
                                    appUpdateState = appUpdateState,
                                    onDownloadAppUpdate = onDownloadAppUpdate,
                                    onInstallAppUpdate = onInstallAppUpdate,
                                    topPadding = 86.dp,
                                    bottomPadding = tabBottomPadding,
                                    onOpenAssets = { onAction(MainAction.OpenAssetsClicked) },
                                    onOpenLinks = { onAction(MainAction.OpenLinksClicked) },
                                    onOpenLogs = { onAction(MainAction.OpenLogsClicked) },
                                    onOpenConfigs = { onAction(MainAction.OpenConfigsClicked) },
                                    onOpenSettings = { onAction(MainAction.OpenSettingsClicked) },
                                )
                            }
                        }
                    }
                }

                MainTopChrome(
                    backdrop = backdrop,
                    onNewProfile = { onAction(MainAction.NewProfileClicked) },
                    onScanQr = { onAction(MainAction.ScanQrCodeClicked) },
                    onImportClipboard = { onAction(MainAction.ImportFromClipboardClicked) },
                    isConnectTab = rootTab == MainRootTab.Connect,
                    collapseProgress = collapseProgress,
                    selectedSourceName = selectedSourceName,
                    selectedProfileName = selectedProfileName,
                    pingState = pingState,
                    onPingCurrent = { onAction(MainAction.PingClicked) },
                    onPingAll = { onAction(MainAction.PingAllProfilesClicked) },
                    onRefreshSource = {
                        if (selectedSourceId != 0L) onAction(MainAction.RefreshSourceClicked(selectedSourceId))
                        else onAction(MainAction.RefreshLinksClicked)
                    },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .zIndex(3f)
                        .padding(horizontal = spacing.md, vertical = spacing.md),
                )

                AnimatedVisibility(
                    visible = rootTab == MainRootTab.Connect,
                    enter = fadeIn(animationSpec = tween(220)) +
                        scaleIn(initialScale = 0.82f, animationSpec = tween(220)) +
                        slideInVertically(initialOffsetY = { it / 2 }, animationSpec = tween(220)),
                    exit = fadeOut(animationSpec = tween(180)) +
                        scaleOut(targetScale = 0.82f, animationSpec = tween(180)) +
                        slideOutVertically(targetOffsetY = { it / 2 }, animationSpec = tween(180)),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .zIndex(3f)
                        .padding(
                            end = spacing.lg + 12.dp,
                            bottom = spacing.lg + reservedBottomInset + 94.dp,
                        ),
                ) {
                    FloatingConnectButton(
                        backdrop = backdrop,
                        isRunning = isRunning,
                        onClick = { onAction(MainAction.ToggleVpnClicked) },
                        modifier = Modifier,
                    )
                }

                MainLiquidTabBar(
                    backdrop = backdrop,
                    selectedTab = rootTab,
                    onSelectTab = { tab ->
                        if (pagerState.currentPage == tab.ordinal) return@MainLiquidTabBar
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(tab.ordinal)
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .zIndex(2f)
                        .padding(horizontal = spacing.lg)
                        .padding(bottom = spacing.lg + reservedBottomInset),
                )
            }
        }
    }
}

@Composable
private fun ConnectContent(
    tabs: List<Link>,
    selectedTabId: Long,
    selectedSourceId: Long,
    isRunning: Boolean,
    selectedSourceName: String,
    selectedProfileName: String,
    selectedServerLabel: String,
    pingState: MainPingState,
    profiles: List<MainProfileItem>,
    selectedProfileId: Long,
    profilesCount: Int,
    activeBatchPingSourceId: Long?,
    listState: LazyListState,
    collapseProgress: Float,
    onAction: (MainAction) -> Unit,
    topPadding: androidx.compose.ui.unit.Dp,
    bottomPadding: androidx.compose.ui.unit.Dp,
) {
    val spacing = YaxcTheme.spacing
    val density = LocalDensity.current
    val sourceSpacingPx = remember(density, spacing.md) {
        with(density) { spacing.md.toPx() }
    }
    val sourceCenters = remember { mutableStateMapOf<Long, Float>() }
    val sourceHeights = remember { mutableStateMapOf<Long, Float>() }
    var draggingSourceId by remember { mutableStateOf<Long?>(null) }
    var draggingOffsetY by remember { mutableFloatStateOf(0f) }
    var draggingTargetIndex by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(tabs) {
        val knownIds = tabs.mapTo(mutableSetOf()) { it.id }
        sourceCenters.keys.toList().filterNot(knownIds::contains).forEach(sourceCenters::remove)
        sourceHeights.keys.toList().filterNot(knownIds::contains).forEach(sourceHeights::remove)
        if (draggingSourceId != null && draggingSourceId !in knownIds) {
            draggingSourceId = null
            draggingOffsetY = 0f
            draggingTargetIndex = null
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(
            start = spacing.md,
            end = spacing.md,
            top = topPadding,
            bottom = bottomPadding,
        ),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        item {
            ConnectionTopCard(
                isRunning = isRunning,
                selectedSourceName = selectedSourceName,
                selectedProfileName = selectedProfileName,
                selectedServerLabel = selectedServerLabel,
                pingState = pingState,
                activeBatchPingSourceId = activeBatchPingSourceId,
                collapseProgress = collapseProgress,
                onPingCurrent = { onAction(MainAction.PingClicked) },
                onPingAll = { onAction(MainAction.PingAllProfilesClicked) },
                onRefreshSource = {
                    if (selectedSourceId != 0L) onAction(MainAction.RefreshSourceClicked(selectedSourceId))
                    else onAction(MainAction.RefreshLinksClicked)
                },
            )
        }

        itemsIndexed(
            items = tabs,
            key = { _, item -> item.id },
        ) { index, source ->
            val isDragging = draggingSourceId == source.id
            val draggedIndex = draggingSourceId?.let { sourceId ->
                tabs.indexOfFirst { it.id == sourceId }.takeIf { it >= 0 }
            }
            val draggedHeight = draggingSourceId?.let { sourceHeights[it] } ?: 0f
            val displacedOffsetTarget = when {
                isDragging -> draggingOffsetY
                draggedIndex == null || draggingTargetIndex == null -> 0f
                draggedIndex < draggingTargetIndex!! && index in (draggedIndex + 1)..draggingTargetIndex!! ->
                    -(draggedHeight + sourceSpacingPx)
                draggedIndex > draggingTargetIndex!! && index in draggingTargetIndex!! until draggedIndex ->
                    draggedHeight + sourceSpacingPx
                else -> 0f
            }
            val displacedOffset by animateFloatAsState(
                targetValue = displacedOffsetTarget,
                animationSpec = spring(dampingRatio = 0.82f, stiffness = 620f),
                label = "source_group_displacement",
            )
            val dragModifier = Modifier.pointerInput(source.id, tabs) {
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        draggingSourceId = source.id
                        draggingOffsetY = 0f
                        draggingTargetIndex = index
                    },
                    onDragCancel = {
                        draggingSourceId = null
                        draggingOffsetY = 0f
                        draggingTargetIndex = null
                    },
                    onDragEnd = {
                        val toIndex = draggingTargetIndex
                        if (toIndex != null && toIndex != index) {
                            onAction(MainAction.MoveSource(index, toIndex))
                        }
                        draggingSourceId = null
                        draggingOffsetY = 0f
                        draggingTargetIndex = null
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        draggingOffsetY += dragAmount.y
                        val sourceCenter = sourceCenters[source.id] ?: 0f
                        val finalCenter = sourceCenter + draggingOffsetY
                        val targetId = tabs
                            .map { it.id }
                            .minByOrNull { sourceId ->
                                abs((sourceCenters[sourceId] ?: sourceCenter) - finalCenter)
                            }
                        draggingTargetIndex = tabs.indexOfFirst { it.id == targetId }
                            .takeIf { it >= 0 }
                            ?: index
                    },
                )
            }
            SourceGroupCard(
                source = source,
                isExpanded = source.id == selectedTabId,
                isBatchPingRunning = activeBatchPingSourceId == source.id,
                profiles = if (source.id == selectedTabId) profiles else emptyList(),
                selectedProfileId = selectedProfileId,
                onToggleExpanded = { onAction(MainAction.SelectTab(source.id)) },
                onRefresh = { onAction(MainAction.RefreshSourceClicked(source.id)) },
                onPingAll = { onAction(MainAction.PingSourceClicked(source.id)) },
                onRenameSource = { onAction(MainAction.RequestRenameSource(source.id)) },
                onDeleteSource = { onAction(MainAction.RequestDeleteSource(source.id)) },
                onSelectProfile = { onAction(MainAction.SelectProfile(it.profile.id)) },
                onEditProfile = { onAction(MainAction.EditProfile(it.profile)) },
                onDeleteProfile = { onAction(MainAction.RequestDeleteProfile(it.profile)) },
                dragModifier = dragModifier,
                modifier = Modifier
                    .graphicsLayer {
                        translationY = displacedOffset
                        scaleX = if (isDragging) 1.01f else 1f
                        scaleY = if (isDragging) 1.01f else 1f
                    }
                    .zIndex(if (isDragging) 3f else 0f)
                    .shadow(
                        elevation = if (isDragging) 24.dp else 0.dp,
                        shape = RoundedCornerShape(30.dp),
                        clip = false,
                    )
                    .onGloballyPositioned { coordinates ->
                        sourceCenters[source.id] = coordinates.positionInParent().y + coordinates.size.height / 2f
                        sourceHeights[source.id] = coordinates.size.height.toFloat()
                    }
            )
        }

        if (tabs.isEmpty()) {
            item { EmptySourcesCard() }
        }

        item {
            Text(
                text = textResource(R.string.mainProfilesCount, profilesCount),
                style = MaterialTheme.typography.bodySmall,
                color = YaxcTheme.extendedColors.textMuted,
            )
        }
    }
}

@Composable
private fun RoutingContent(
    topPadding: androidx.compose.ui.unit.Dp,
    bottomPadding: androidx.compose.ui.unit.Dp,
    onOpenAppsRouting: () -> Unit,
    onOpenCoreRouting: () -> Unit,
) {
    val spacing = YaxcTheme.spacing
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = spacing.md,
            end = spacing.md,
            top = topPadding,
            bottom = bottomPadding,
        ),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        item {
            YaxcGlassPanel {
                Text(
                    text = textResource(R.string.appsRouting),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = textResource(R.string.appsRoutingLead),
                    style = MaterialTheme.typography.bodyMedium,
                    color = YaxcTheme.extendedColors.textMuted,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = textResource(R.string.appsRoutingModeTitle),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    ActionBubble(
                        icon = Icons.AutoMirrored.Outlined.ArrowForwardIos,
                        onClick = onOpenAppsRouting,
                    )
                }
            }
        }
        item {
            YaxcGlassPanel {
                Text(
                    text = textResource(R.string.coreRouting),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = textResource(R.string.coreRoutingLead),
                    style = MaterialTheme.typography.bodyMedium,
                    color = YaxcTheme.extendedColors.textMuted,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = textResource(R.string.coreRouting),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    ActionBubble(
                        icon = Icons.AutoMirrored.Outlined.ArrowForwardIos,
                        onClick = onOpenCoreRouting,
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsContent(
    appVersion: String,
    xrayVersion: String,
    tun2socksVersion: String,
    appUpdateState: AppUpdateUiState,
    onDownloadAppUpdate: () -> Unit,
    onInstallAppUpdate: () -> Unit,
    topPadding: androidx.compose.ui.unit.Dp,
    bottomPadding: androidx.compose.ui.unit.Dp,
    onOpenAssets: () -> Unit,
    onOpenLinks: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenConfigs: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val spacing = YaxcTheme.spacing
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = spacing.md,
            end = spacing.md,
            top = topPadding,
            bottom = bottomPadding,
        ),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        item {
            SettingsActionCard(
                icon = Icons.Outlined.FolderOpen,
                title = textResource(R.string.assets),
                description = textResource(R.string.assetsScreenLead),
                onClick = onOpenAssets,
            )
        }
        item {
            SettingsActionCard(
                icon = Icons.Outlined.Link,
                title = textResource(R.string.links),
                description = textResource(R.string.linksScreenLead),
                onClick = onOpenLinks,
            )
        }
        item {
            SettingsActionCard(
                icon = Icons.Outlined.Terminal,
                title = textResource(R.string.logs),
                description = textResource(R.string.logsScreenLead),
                onClick = onOpenLogs,
            )
        }
        item {
            SettingsActionCard(
                icon = Icons.AutoMirrored.Outlined.Subject,
                title = textResource(R.string.configs),
                description = textResource(R.string.configsScreenLead),
                onClick = onOpenConfigs,
            )
        }
        item {
            SettingsActionCard(
                icon = Icons.Outlined.Settings,
                title = textResource(R.string.settings),
                description = textResource(R.string.settingsScreenLead),
                onClick = onOpenSettings,
            )
        }
        item {
            YaxcGlassPanel {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    VersionRow(
                        textResource(R.string.appFullName),
                        appVersion,
                    )
                    AppUpdatePanel(
                        state = appUpdateState,
                        onDownload = onDownloadAppUpdate,
                        onInstall = onInstallAppUpdate,
                    )
                    VersionRow(
                        textResource(R.string.xrayLabel),
                        xrayVersion,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    VersionRow(
                        textResource(R.string.tun2socksLabel),
                        tun2socksVersion,
                        modifier = Modifier.padding(top = 10.dp),
                    )
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
}

@Composable
private fun ConnectionTopCard(
    isRunning: Boolean,
    selectedSourceName: String,
    selectedProfileName: String,
    selectedServerLabel: String,
    pingState: MainPingState,
    activeBatchPingSourceId: Long?,
    collapseProgress: Float,
    onPingCurrent: () -> Unit,
    onPingAll: () -> Unit,
    onRefreshSource: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.992f else 1f,
        animationSpec = spring(dampingRatio = 0.82f, stiffness = 720f),
        label = "main_control_bar_scale",
    )

    YaxcGlassPanel(
        modifier = modifier.graphicsLayer {
            alpha = 1f - collapseProgress * 0.38f
            scaleX = (1f - collapseProgress * 0.04f) * pressScale
            scaleY = (1f - collapseProgress * 0.04f) * pressScale
        }.clickable(
            interactionSource = interactionSource,
            indication = null,
            onClick = onPingCurrent,
        ),
        shape = MaterialTheme.shapes.extraLarge,
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
        accentColor = if (isRunning) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
        },
        borderColor = MaterialTheme.colorScheme.primary.copy(alpha = if (isRunning) 0.24f else 0.16f),
        shadowElevation = 20.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = selectedServerLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = YaxcTheme.extendedColors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Text(
                    text = selectedSourceName,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 10.dp),
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
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
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ActionBubble(
                    icon = Icons.Outlined.WifiTethering,
                    onClick = onPingAll,
                    loading = activeBatchPingSourceId != null,
                )
                ActionBubble(
                    icon = Icons.Outlined.Refresh,
                    onClick = onRefreshSource,
                )
            }
        }
    }
}

@Composable
private fun FloatingConnectButton(
    backdrop: LayerBackdrop,
    isRunning: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = spring(dampingRatio = 0.76f, stiffness = 640f),
        label = "floating_connect_scale",
    )
    val surfaceTint by animateColorAsState(
        targetValue = if (isRunning) {
            YaxcTheme.extendedColors.success.copy(alpha = 0.34f)
        } else {
            Color.White.copy(alpha = 0.18f)
        },
        animationSpec = spring(dampingRatio = 0.88f, stiffness = 420f),
        label = "floating_connect_tint",
    )
    val iconTint by animateColorAsState(
        targetValue = if (isRunning) Color(0xFFD9FFF1) else Color.White.copy(alpha = 0.94f),
        animationSpec = spring(dampingRatio = 0.88f, stiffness = 420f),
        label = "floating_connect_icon",
    )

    Box(
        modifier = modifier
            .shadow(24.dp, CircleShape, clip = false)
            .scale(scale),
    ) {
        YaxcLiquidSurface(
            backdrop = backdrop,
            modifier = Modifier
                .size(64.dp)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                ),
            shape = CircleShape,
            contentPadding = PaddingValues(0.dp),
            surfaceTint = surfaceTint,
            blurRadius = 24.dp,
            lensRadius = 30.dp,
            lensDistortion = 56.dp,
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.PowerSettingsNew,
                    contentDescription = if (isRunning) textResource(R.string.vpnStop) else textResource(R.string.vpnStart),
                    tint = iconTint,
                    modifier = Modifier.size(26.dp),
                )
            }
        }
    }
}

@Composable
private fun MainLiquidTabBar(
    backdrop: LayerBackdrop,
    selectedTab: MainRootTab,
    onSelectTab: (MainRootTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedIndex = when (selectedTab) {
        MainRootTab.Connect -> 0
        MainRootTab.Routing -> 1
        MainRootTab.Settings -> 2
    }
    val gap = 8.dp

    YaxcLiquidSurface(
        backdrop = backdrop,
        modifier = modifier.fillMaxWidth(0.94f),
        shape = RoundedCornerShape(36.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
        surfaceTint = Color.White.copy(alpha = 0.16f),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(0.dp),
        ) {
            val tabWidth = (maxWidth - gap * 2) / 3
            val animatedOffset by animateDpAsState(
                targetValue = (tabWidth + gap) * selectedIndex,
                animationSpec = spring(dampingRatio = 0.82f, stiffness = 420f),
                label = "main_tab_indicator_offset",
            )

            Box(
                modifier = Modifier
                    .width(tabWidth)
                    .padding(vertical = 0.dp)
                    .offset(x = animatedOffset),
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(58.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.96f),
                    shape = RoundedCornerShape(28.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)),
                ) {}
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(gap),
            ) {
                MainBottomTabItem(
                    icon = Icons.Outlined.ToggleOn,
                    label = textResource(R.string.rootTabConnect),
                    selected = selectedTab == MainRootTab.Connect,
                    onClick = { onSelectTab(MainRootTab.Connect) },
                    modifier = Modifier.weight(1f),
                )
                MainBottomTabItem(
                    icon = Icons.AutoMirrored.Outlined.AltRoute,
                    label = textResource(R.string.rootTabRouting),
                    selected = selectedTab == MainRootTab.Routing,
                    onClick = { onSelectTab(MainRootTab.Routing) },
                    modifier = Modifier.weight(1f),
                )
                MainBottomTabItem(
                    icon = Icons.Outlined.Settings,
                    label = textResource(R.string.rootTabSettings),
                    selected = selectedTab == MainRootTab.Settings,
                    onClick = { onSelectTab(MainRootTab.Settings) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun MainTopChrome(
    backdrop: LayerBackdrop,
    onNewProfile: () -> Unit,
    onScanQr: () -> Unit,
    onImportClipboard: () -> Unit,
    isConnectTab: Boolean,
    collapseProgress: Float,
    selectedSourceName: String,
    selectedProfileName: String,
    pingState: MainPingState,
    onPingCurrent: () -> Unit,
    onPingAll: () -> Unit,
    onRefreshSource: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var actionsExpanded by remember { mutableStateOf(false) }
    val showMiniBar = isConnectTab && collapseProgress > 0f

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        YaxcLiquidSurface(
            backdrop = backdrop,
            shape = MaterialTheme.shapes.extraLarge,
            contentPadding = PaddingValues(horizontal = 13.dp, vertical = 8.dp),
            surfaceTint = Color.White.copy(alpha = 0.26f),
        ) {
            Text(
                text = textResource(R.string.appName),
                style = MaterialTheme.typography.titleSmall,
                color = Color.White.copy(alpha = 0.96f),
            )
        }

        if (showMiniBar) {
            MiniControlBar(
                backdrop = backdrop,
                progress = collapseProgress,
                selectedSourceName = selectedSourceName,
                selectedProfileName = selectedProfileName,
                pingState = pingState,
                onPingCurrent = onPingCurrent,
                onPingAll = onPingAll,
                onRefreshSource = onRefreshSource,
                modifier = Modifier.weight(1f),
            )
        } else {
            Spacer(modifier = Modifier.weight(1f))
        }

        Box {
            YaxcLiquidSurface(
                backdrop = backdrop,
                modifier = Modifier.clickable(onClick = { actionsExpanded = true }),
                shape = MaterialTheme.shapes.extraLarge,
                contentPadding = PaddingValues(0.dp),
                surfaceTint = Color.White.copy(alpha = 0.26f),
            ) {
                Box(
                    modifier = Modifier.size(40.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = textResource(R.string.newProfile),
                        tint = Color.White.copy(alpha = 0.9f),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            YaxcLiquidDropdownMenu(
                expanded = actionsExpanded,
                onDismissRequest = { actionsExpanded = false },
            ) {
                YaxcLiquidDropdownMenuItem(
                    text = textResource(R.string.newProfile),
                    onClick = {
                        actionsExpanded = false
                        onNewProfile()
                    },
                )
                YaxcLiquidDropdownMenuItem(
                    text = textResource(R.string.scanQrCode),
                    onClick = {
                        actionsExpanded = false
                        onScanQr()
                    },
                )
                YaxcLiquidDropdownMenuItem(
                    text = textResource(R.string.fromClipboard),
                    onClick = {
                        actionsExpanded = false
                        onImportClipboard()
                    },
                )
            }
        }
    }
}

@Composable
private fun MiniControlBar(
    backdrop: LayerBackdrop,
    progress: Float,
    selectedSourceName: String,
    selectedProfileName: String,
    pingState: MainPingState,
    onPingCurrent: () -> Unit,
    onPingAll: () -> Unit,
    onRefreshSource: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.985f else 1f,
        animationSpec = spring(dampingRatio = 0.78f, stiffness = 700f),
        label = "mini_control_bar_scale",
    )
    val surfaceTint by animateColorAsState(
        targetValue = if (pressed) {
            Color.White.copy(alpha = 0.28f)
        } else {
            Color.White.copy(alpha = 0.22f)
        },
        animationSpec = spring(dampingRatio = 0.86f, stiffness = 560f),
        label = "mini_control_bar_tint",
    )

    YaxcLiquidSurface(
        backdrop = backdrop,
        modifier = modifier.graphicsLayer {
            alpha = progress
            scaleX = (0.88f + progress * 0.12f) * pressScale
            scaleY = (0.92f + progress * 0.08f) * pressScale
            translationY = (1f - progress) * -20f
        }.clickable(
            interactionSource = interactionSource,
            indication = null,
            onClick = onPingCurrent,
        ),
        shape = RoundedCornerShape(26.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 7.dp),
        surfaceTint = surfaceTint,
        blurRadius = 22.dp,
        lensRadius = 28.dp,
        lensDistortion = 50.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Text(
                    text = selectedSourceName,
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.92f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = selectedProfileName.ifBlank { textResource(R.string.mainNoSelectedProfile) },
                        style = MaterialTheme.typography.bodySmall,
                        color = YaxcTheme.extendedColors.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    PingStateBadge(pingState = pingState)
                }
            }
            ActionBubble(
                icon = Icons.Outlined.WifiTethering,
                onClick = onPingAll,
                containerSize = 32.dp,
                iconSize = 15.dp,
            )
            ActionBubble(
                icon = Icons.Outlined.Refresh,
                onClick = onRefreshSource,
                containerSize = 32.dp,
                iconSize = 15.dp,
            )
        }
    }
}

@Composable
private fun MainBottomTabItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 700f),
        label = "main_tab_scale",
    )

    Surface(
        modifier = modifier
            .scale(scale)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        color = Color.Transparent,
        shape = RoundedCornerShape(28.dp),
        border = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp)
                .padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    Color.White.copy(alpha = 0.68f)
                },
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    Color.White.copy(alpha = 0.68f)
                },
            )
        }
    }
}

@Composable
private fun SettingsActionCard(
    icon: ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    YaxcGlassPanel(
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Surface(
                color = Color.White.copy(alpha = 0.10f),
                shape = MaterialTheme.shapes.large,
            ) {
                Box(
                    modifier = Modifier.size(44.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.86f),
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = YaxcTheme.extendedColors.textMuted,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ArrowForwardIos,
                contentDescription = null,
                tint = YaxcTheme.extendedColors.textMuted,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun SourceGroupCard(
    source: Link,
    isExpanded: Boolean,
    isBatchPingRunning: Boolean,
    profiles: List<MainProfileItem>,
    selectedProfileId: Long,
    onToggleExpanded: () -> Unit,
    onRefresh: () -> Unit,
    onPingAll: () -> Unit,
    onRenameSource: () -> Unit,
    onDeleteSource: () -> Unit,
    onSelectProfile: (MainProfileItem) -> Unit,
    onEditProfile: (MainProfileItem) -> Unit,
    onDeleteProfile: (MainProfileItem) -> Unit,
    dragModifier: Modifier = Modifier,
    modifier: Modifier = Modifier,
) {
    var actionsExpanded by remember { mutableStateOf(false) }

    YaxcGlassPanel(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .then(dragModifier)
                    .clickable(onClick = onToggleExpanded)
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = source.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }

            ActionBubble(
                icon = Icons.Outlined.WifiTethering,
                onClick = onPingAll,
                loading = isBatchPingRunning,
            )
            ActionBubble(icon = Icons.Outlined.Refresh, onClick = onRefresh)
            Box {
                ActionBubble(
                    icon = Icons.Outlined.MoreVert,
                    onClick = { actionsExpanded = true },
                )
                YaxcLiquidDropdownMenu(
                    expanded = actionsExpanded,
                    onDismissRequest = { actionsExpanded = false },
                ) {
                    YaxcLiquidDropdownMenuItem(
                        text = textResource(R.string.renameSource),
                        onClick = {
                            actionsExpanded = false
                            onRenameSource()
                        },
                    )
                    YaxcLiquidDropdownMenuItem(
                        text = textResource(R.string.deleteSource),
                        onClick = {
                            actionsExpanded = false
                            onDeleteSource()
                        },
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically(
                expandFrom = Alignment.Top,
                animationSpec = spring(dampingRatio = 0.92f, stiffness = 560f),
            ) + fadeIn(animationSpec = tween(durationMillis = 120, delayMillis = 40)),
            exit = shrinkVertically(
                shrinkTowards = Alignment.Top,
                animationSpec = spring(dampingRatio = 0.96f, stiffness = 620f),
            ) + fadeOut(animationSpec = tween(durationMillis = 90)),
        ) {
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
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.988f else 1f,
        animationSpec = spring(dampingRatio = 0.84f, stiffness = 760f),
        label = "profile_card_scale",
    )
    val accentColor by animateColorAsState(
        targetValue = if (isSelected) {
            YaxcTheme.extendedColors.success
        } else {
            MaterialTheme.colorScheme.primary
        },
        animationSpec = spring(dampingRatio = 0.9f, stiffness = 420f),
        label = "profile_card_accent",
    )
    val borderColor by animateColorAsState(
        targetValue = if (isSelected) {
            YaxcTheme.extendedColors.success.copy(alpha = 0.46f)
        } else {
            Color.White.copy(alpha = 0.12f)
        },
        animationSpec = spring(dampingRatio = 0.9f, stiffness = 420f),
        label = "profile_card_border",
    )
    val accentAlpha by animateFloatAsState(
        targetValue = if (isSelected) 0.22f else 0.08f,
        animationSpec = spring(dampingRatio = 0.9f, stiffness = 420f),
        label = "profile_card_accent_alpha",
    )
    val shadowElevation by animateDpAsState(
        targetValue = if (isSelected) 20.dp else 10.dp,
        animationSpec = spring(dampingRatio = 0.9f, stiffness = 420f),
        label = "profile_card_shadow",
    )

    YaxcGlassPanel(
        modifier = Modifier
            .scale(pressScale)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onSelect,
            ),
        shape = MaterialTheme.shapes.large,
        contentPadding = PaddingValues(horizontal = 13.dp, vertical = 10.dp),
        accentColor = accentColor,
        accentAlpha = accentAlpha,
        borderColor = borderColor,
        shadowElevation = shadowElevation,
    ) {
        var actionsExpanded by remember { mutableStateOf(false) }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = profile.profile.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = profile.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = YaxcTheme.extendedColors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            PingStateBadge(
                pingState = profile.pingState,
                modifier = Modifier.padding(start = 4.dp, end = 2.dp),
            )

            Box {
                ActionBubble(
                    icon = Icons.Outlined.MoreVert,
                    onClick = { actionsExpanded = true },
                    containerSize = 34.dp,
                    iconSize = 16.dp,
                )
                YaxcLiquidDropdownMenu(
                    expanded = actionsExpanded,
                    onDismissRequest = { actionsExpanded = false },
                ) {
                    YaxcLiquidDropdownMenuItem(
                        text = textResource(R.string.editProfile),
                        onClick = {
                            actionsExpanded = false
                            onEdit()
                        },
                    )
                    YaxcLiquidDropdownMenuItem(
                        text = textResource(R.string.deleteProfile),
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
private fun PingStateBadge(
    pingState: MainPingState,
    modifier: Modifier = Modifier,
) {
    when (pingState) {
        MainPingState.Idle -> {
            Text(
                text = textResource(R.string.noValue),
                style = MaterialTheme.typography.bodySmall,
                color = YaxcTheme.extendedColors.textMuted,
                modifier = modifier,
            )
        }

        MainPingState.Loading -> {
            CircularProgressIndicator(
                modifier = modifier.size(14.dp),
                strokeWidth = 2.dp,
            )
        }

        is MainPingState.Success -> {
            Text(
                text = pingState.label,
                style = MaterialTheme.typography.bodySmall,
                color = YaxcTheme.extendedColors.success,
                modifier = modifier,
            )
        }

        is MainPingState.Error -> {
            Text(
                text = pingState.label,
                style = MaterialTheme.typography.bodySmall,
                color = YaxcTheme.extendedColors.danger,
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun EmptySourcesCard() {
    YaxcGlassPanel {
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
    containerSize: androidx.compose.ui.unit.Dp = 40.dp,
    iconSize: androidx.compose.ui.unit.Dp = 20.dp,
    loading: Boolean = false,
) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        color = Color.White.copy(alpha = 0.08f),
        shape = MaterialTheme.shapes.large,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
    ) {
        Box(
            modifier = Modifier.size(containerSize),
            contentAlignment = Alignment.Center,
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(iconSize),
                    strokeWidth = 2.dp,
                    color = Color.White.copy(alpha = 0.82f),
                )
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.76f),
                    modifier = Modifier.size(iconSize),
                )
            }
        }
    }
}

@Composable
private fun VersionRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(
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
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun textResource(id: Int): String = stringResource(id)

@Composable
private fun textResource(id: Int, arg0: Int): String = stringResource(id, arg0)
