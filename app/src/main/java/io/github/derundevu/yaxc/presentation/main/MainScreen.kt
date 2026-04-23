package io.github.derundevu.yaxc.presentation.main

import XrayCore.XrayCore
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
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
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.AltRoute
import androidx.compose.material.icons.automirrored.outlined.ArrowForwardIos
import androidx.compose.material.icons.automirrored.outlined.Subject
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.ToggleOn
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.WifiTethering
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
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
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import io.github.derundevu.yaxc.R
import io.github.derundevu.yaxc.database.Link
import io.github.derundevu.yaxc.dto.SubscriptionMetadata
import io.github.derundevu.yaxc.helper.AppUpdateUiState
import io.github.derundevu.yaxc.helper.HttpHelper
import io.github.derundevu.yaxc.presentation.designsystem.YaxcTheme
import io.github.derundevu.yaxc.presentation.designsystem.yaxcIsLightTheme
import io.github.derundevu.yaxc.presentation.designsystem.yaxcSoftFill
import io.github.derundevu.yaxc.presentation.designsystem.yaxcSoftOnSurface
import io.github.derundevu.yaxc.presentation.designsystem.yaxcSoftStroke
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcGlassPanel
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcLiquidDropdownMenu
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcLiquidDropdownMenuItem
import io.github.derundevu.yaxc.presentation.designsystem.components.yaxcClickable
import io.github.derundevu.yaxc.presentation.root.AppUpdatePanel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

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
    selectedSourceMetadata: SubscriptionMetadata?,
    selectedProfileName: String,
    selectedServerLabel: String,
    socksAddress: String,
    socksPort: String,
    socksUsername: String,
    socksPassword: String,
    pingAddress: String,
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
                                    selectedSourceMetadata = selectedSourceMetadata,
                                    selectedProfileName = selectedProfileName,
                                    selectedServerLabel = selectedServerLabel,
                                    socksAddress = socksAddress,
                                    socksPort = socksPort,
                                    socksUsername = socksUsername,
                                    socksPassword = socksPassword,
                                    pingAddress = pingAddress,
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
                                    onOpenSettings = { onAction(MainAction.OpenSettingsClicked) },
                                )
                            }
                        }
                    }
                }

                MainTopChrome(
                    onNewProfile = { onAction(MainAction.NewProfileClicked) },
                    onNewSource = { onAction(MainAction.NewSourceClicked) },
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
                        isRunning = isRunning,
                        onClick = { onAction(MainAction.ToggleVpnClicked) },
                        modifier = Modifier,
                    )
                }

                MainLiquidTabBar(
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

private fun reorderedSourceIds(
    startOrder: List<Long>,
    draggedSourceId: Long,
    targetIndex: Int,
): List<Long> {
    if (startOrder.size < 2 || draggedSourceId !in startOrder) return startOrder
    val remainingIds = startOrder.filter { it != draggedSourceId }
    return remainingIds.toMutableList().apply {
        add(targetIndex.coerceIn(0, remainingIds.size), draggedSourceId)
    }
}

private fun draggedTargetIndex(
    startOrder: List<Long>,
    draggedSourceId: Long,
    currentTargetIndex: Int,
    draggedCenterY: Float,
    sourceCenters: Map<Long, Float>,
    hysteresisPx: Float,
): Int {
    val remainingIds = startOrder.filter { it != draggedSourceId }
    if (remainingIds.isEmpty()) return 0
    var targetIndex = currentTargetIndex.coerceIn(0, remainingIds.size)

    while (targetIndex > 0) {
        val previousCenter = sourceCenters[remainingIds[targetIndex - 1]] ?: break
        if (draggedCenterY >= previousCenter - hysteresisPx) break
        targetIndex -= 1
    }
    while (targetIndex < remainingIds.size) {
        val nextCenter = sourceCenters[remainingIds[targetIndex]] ?: break
        if (draggedCenterY <= nextCenter + hysteresisPx) break
        targetIndex += 1
    }

    return targetIndex
}

private fun draggedSlotCenterY(
    startOrder: List<Long>,
    draggedSourceId: Long,
    targetIndex: Int,
    sourceCenters: Map<Long, Float>,
    sourceHeights: Map<Long, Float>,
    sourceSpacingPx: Float,
): Float? {
    val firstId = startOrder.firstOrNull() ?: return sourceCenters[draggedSourceId]
    val firstCenter = sourceCenters[firstId] ?: return sourceCenters[draggedSourceId]
    val firstHeight = sourceHeights[firstId] ?: return sourceCenters[draggedSourceId]
    val finalOrder = reorderedSourceIds(
        startOrder = startOrder,
        draggedSourceId = draggedSourceId,
        targetIndex = targetIndex,
    )
    var slotTop = firstCenter - firstHeight / 2f
    finalOrder.forEach { sourceId ->
        val sourceHeight = sourceHeights[sourceId] ?: return sourceCenters[draggedSourceId]
        if (sourceId == draggedSourceId) {
            return slotTop + sourceHeight / 2f
        }
        slotTop += sourceHeight + sourceSpacingPx
    }
    return sourceCenters[draggedSourceId]
}

@Composable
private fun ConnectContent(
    tabs: List<Link>,
    selectedTabId: Long,
    selectedSourceId: Long,
    isRunning: Boolean,
    selectedSourceName: String,
    selectedSourceMetadata: SubscriptionMetadata?,
    selectedProfileName: String,
    selectedServerLabel: String,
    socksAddress: String,
    socksPort: String,
    socksUsername: String,
    socksPassword: String,
    pingAddress: String,
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
    val dragHysteresisPx = remember(density) {
        with(density) { 16.dp.toPx() }
    }
    val sourceCenters = remember { mutableStateMapOf<Long, Float>() }
    val sourceHeights = remember { mutableStateMapOf<Long, Float>() }
    val tabIds = remember(tabs) { tabs.map { it.id } }
    val tabsById = remember(tabs) { tabs.associateBy { it.id } }
    var localSourceOrder by remember { mutableStateOf<List<Long>?>(null) }
    var draggingSourceId by remember { mutableStateOf<Long?>(null) }
    var draggedCenterY by remember { mutableFloatStateOf(0f) }
    var draggedSlotCenterY by remember { mutableFloatStateOf(0f) }
    var draggingTargetIndex by remember { mutableIntStateOf(-1) }
    var dragStartOrder by remember { mutableStateOf(emptyList<Long>()) }
    var dragStartCenters by remember { mutableStateOf(emptyMap<Long, Float>()) }
    var dragStartHeights by remember { mutableStateOf(emptyMap<Long, Float>()) }
    val orderedTabs = remember(tabs, tabsById, localSourceOrder) {
        val sourceOrder = localSourceOrder ?: return@remember tabs
        val usedIds = mutableSetOf<Long>()
        buildList {
            sourceOrder.forEach { sourceId ->
                val tab = tabsById[sourceId]
                if (tab != null && usedIds.add(sourceId)) add(tab)
            }
            tabs.forEach { tab ->
                if (usedIds.add(tab.id)) add(tab)
            }
        }
    }
    val orderedTabIds = remember(orderedTabs) { orderedTabs.map { it.id } }
    val currentTabIds = rememberUpdatedState(tabIds)
    val currentOrderedTabIds = rememberUpdatedState(orderedTabIds)

    LaunchedEffect(tabIds) {
        val knownIds = tabIds.toSet()
        sourceCenters.keys.toList().filterNot(knownIds::contains).forEach(sourceCenters::remove)
        sourceHeights.keys.toList().filterNot(knownIds::contains).forEach(sourceHeights::remove)
        if (draggingSourceId != null && draggingSourceId !in knownIds) {
            draggingSourceId = null
            draggedCenterY = 0f
            draggedSlotCenterY = 0f
            draggingTargetIndex = -1
            dragStartOrder = emptyList()
            dragStartCenters = emptyMap()
            dragStartHeights = emptyMap()
            localSourceOrder = null
        } else {
            localSourceOrder = localSourceOrder?.let { sourceOrder ->
                val normalizedOrder = sourceOrder.filter(knownIds::contains) +
                    tabIds.filterNot(sourceOrder::contains)
                when {
                    normalizedOrder.isEmpty() -> null
                    draggingSourceId == null && normalizedOrder == tabIds -> null
                    else -> normalizedOrder
                }
            }
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
                selectedSourceMetadata = selectedSourceMetadata,
                selectedProfileName = selectedProfileName,
                selectedServerLabel = selectedServerLabel,
                socksAddress = socksAddress,
                socksPort = socksPort,
                socksUsername = socksUsername,
                socksPassword = socksPassword,
                pingAddress = pingAddress,
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

        items(
            items = orderedTabs,
            key = { item -> item.id },
        ) { source ->
            val isDragging = draggingSourceId == source.id
            val isExpanded = source.id == selectedTabId
            val dragOffsetTarget = if (isDragging) {
                draggedCenterY - draggedSlotCenterY
            } else {
                0f
            }
            val dragOffset by animateFloatAsState(
                targetValue = dragOffsetTarget,
                animationSpec = if (isDragging) {
                    snap()
                } else {
                    spring(dampingRatio = 0.86f, stiffness = 520f)
                },
                label = "main_source_drag_offset",
            )
            val placementModifier = if (isDragging) {
                Modifier
            } else {
                Modifier.animateItem(
                    placementSpec = spring(dampingRatio = 0.86f, stiffness = 520f),
                )
            }
            val dragModifier = Modifier.pointerInput(source.id) {
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        val sourceOrder = currentOrderedTabIds.value
                        val centerSnapshot = sourceCenters.toMap()
                        val heightSnapshot = sourceHeights.toMap()
                        val initialTargetIndex = sourceOrder.indexOf(source.id)
                            .takeIf { it >= 0 }
                            ?: 0
                        localSourceOrder = sourceOrder
                        draggingSourceId = source.id
                        draggingTargetIndex = initialTargetIndex
                        dragStartOrder = sourceOrder
                        dragStartCenters = centerSnapshot
                        dragStartHeights = heightSnapshot
                        draggedCenterY = centerSnapshot[source.id] ?: 0f
                        draggedSlotCenterY = centerSnapshot[source.id] ?: 0f
                    },
                    onDragCancel = {
                        draggingSourceId = null
                        draggedCenterY = 0f
                        draggedSlotCenterY = 0f
                        draggingTargetIndex = -1
                        dragStartOrder = emptyList()
                        dragStartCenters = emptyMap()
                        dragStartHeights = emptyMap()
                        localSourceOrder = null
                    },
                    onDragEnd = {
                        val sourceId = draggingSourceId
                        val orderedIds = localSourceOrder ?: currentOrderedTabIds.value
                        val originalIds = currentTabIds.value
                        if (sourceId != null && orderedIds != originalIds) {
                            onAction(MainAction.CommitSourceOrder(orderedIds))
                        } else {
                            localSourceOrder = null
                        }
                        draggingSourceId = null
                        draggedCenterY = 0f
                        draggedSlotCenterY = 0f
                        draggingTargetIndex = -1
                        dragStartOrder = emptyList()
                        dragStartCenters = emptyMap()
                        dragStartHeights = emptyMap()
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        val sourceId = draggingSourceId ?: source.id
                        val nextCenterY = draggedCenterY + dragAmount.y
                        draggedCenterY = nextCenterY
                        val startOrder = dragStartOrder.ifEmpty { currentOrderedTabIds.value }
                        val startCenters = dragStartCenters.ifEmpty { sourceCenters.toMap() }
                        val startHeights = dragStartHeights.ifEmpty { sourceHeights.toMap() }
                        val nextTargetIndex = draggedTargetIndex(
                            startOrder = startOrder,
                            draggedSourceId = sourceId,
                            draggedCenterY = nextCenterY,
                            currentTargetIndex = draggingTargetIndex,
                            sourceCenters = startCenters,
                            hysteresisPx = dragHysteresisPx,
                        )
                        if (nextTargetIndex != draggingTargetIndex) {
                            val nextOrder = reorderedSourceIds(
                                startOrder = startOrder,
                                draggedSourceId = sourceId,
                                targetIndex = nextTargetIndex,
                            )
                            draggedSlotCenterY = draggedSlotCenterY(
                                startOrder = startOrder,
                                draggedSourceId = sourceId,
                                targetIndex = nextTargetIndex,
                                sourceCenters = startCenters,
                                sourceHeights = startHeights,
                                sourceSpacingPx = sourceSpacingPx,
                            ) ?: draggedSlotCenterY
                            draggingTargetIndex = nextTargetIndex
                            localSourceOrder = nextOrder
                        }
                    },
                )
            }
            SourceGroupCard(
                source = source,
                isExpanded = isExpanded,
                isBatchPingRunning = activeBatchPingSourceId == source.id,
                profiles = if (isExpanded) profiles else emptyList(),
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
                    .then(placementModifier)
                    .graphicsLayer {
                        translationY = dragOffset
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
    selectedSourceMetadata: SubscriptionMetadata?,
    selectedProfileName: String,
    selectedServerLabel: String,
    socksAddress: String,
    socksPort: String,
    socksUsername: String,
    socksPassword: String,
    pingAddress: String,
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
    var showConnectionInfo by remember { mutableStateOf(false) }
    val subscriptionSummary = subscriptionCardSummary(selectedSourceMetadata)
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.992f else 1f,
        animationSpec = spring(dampingRatio = 0.82f, stiffness = 720f),
        label = "main_control_bar_scale",
    )
    val shadowElevation = 20.dp * (1f - collapseProgress)

    YaxcGlassPanel(
        modifier = modifier.graphicsLayer {
            alpha = 1f - collapseProgress * 0.38f
            scaleX = pressScale
            scaleY = pressScale
        }.yaxcClickable(shape = MaterialTheme.shapes.extraLarge, onClick = onPingCurrent),
        shape = MaterialTheme.shapes.extraLarge,
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
        accentColor = if (isRunning) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
        },
        borderColor = MaterialTheme.colorScheme.primary.copy(alpha = if (isRunning) 0.24f else 0.16f),
        shadowElevation = shadowElevation,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ConnectionStatusChip(isRunning = isRunning)

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ConnectionInfoChip(
                        onClick = { showConnectionInfo = true },
                        modifier = Modifier,
                    )
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

            Text(
                text = selectedSourceName,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = selectedProfileName.ifBlank { textResource(R.string.mainNoSelectedProfile) },
                        style = MaterialTheme.typography.bodyMedium,
                        color = YaxcTheme.extendedColors.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!subscriptionSummary.isNullOrBlank()) {
                        Text(
                            text = subscriptionSummary,
                            style = MaterialTheme.typography.labelSmall,
                            color = YaxcTheme.extendedColors.textMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                PingStateBadge(pingState = pingState)
            }
        }
    }

    if (showConnectionInfo) {
        ConnectionInfoDialog(
            selectedSourceMetadata = selectedSourceMetadata,
            selectedServerLabel = selectedServerLabel,
            socksAddress = socksAddress,
            socksPort = socksPort,
            socksUsername = socksUsername,
            socksPassword = socksPassword,
            pingAddress = pingAddress,
            onDismiss = { showConnectionInfo = false },
        )
    }
}

@Composable
private fun ConnectionStatusChip(
    isRunning: Boolean,
    modifier: Modifier = Modifier,
) {
    val containerColor = if (isRunning) {
        YaxcTheme.extendedColors.success.copy(alpha = 0.14f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.34f)
    }
    val borderColor = if (isRunning) {
        YaxcTheme.extendedColors.success.copy(alpha = 0.38f)
    } else {
        YaxcTheme.extendedColors.cardBorder
    }
    val dotColor = if (isRunning) {
        YaxcTheme.extendedColors.success
    } else {
        YaxcTheme.extendedColors.textMuted
    }

    Surface(
        modifier = modifier,
        color = containerColor,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, borderColor),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(dotColor, CircleShape),
            )
            Text(
                text = if (isRunning) {
                    textResource(R.string.mainConnectionRunningShort)
                } else {
                    textResource(R.string.mainConnectionStoppedShort)
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun ConnectionInfoChip(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.yaxcClickable(
            shape = RoundedCornerShape(18.dp),
            onClick = onClick,
        ),
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.42f),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, YaxcTheme.extendedColors.cardBorder),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 9.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = textResource(R.string.mainConnectionInfo),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun ConnectionInfoDialog(
    selectedSourceMetadata: SubscriptionMetadata?,
    selectedServerLabel: String,
    socksAddress: String,
    socksPort: String,
    socksUsername: String,
    socksPassword: String,
    pingAddress: String,
    onDismiss: () -> Unit,
) {
    var showPassword by remember { mutableStateOf(false) }
    val noValue = textResource(R.string.noValue)
    val resolveFailed = textResource(R.string.mainConnectionResolveFailed)
    val trafficUsedValue = selectedSourceMetadata
        ?.usedBytes
        ?.let(::formatBytesCompact)
        ?: noValue
    val trafficLimitValue = selectedSourceMetadata
        ?.totalBytes
        ?.let { totalBytes ->
            if (totalBytes == 0L) {
                textResource(R.string.mainSubscriptionUnlimited)
            } else {
                formatBytesCompact(totalBytes)
            }
        }
        ?: noValue
    val expiresAtValue = selectedSourceMetadata
        ?.expireAtEpochSeconds
        ?.takeIf { it > 0L }
        ?.let(::formatExpiryDateTime)
        ?: noValue
    val daysLeftValue = selectedSourceMetadata
        ?.expireAtEpochSeconds
        ?.takeIf { it > 0L }
        ?.let { formatDaysLeftFull(it) }
        ?: noValue
    val autoUpdateValue = selectedSourceMetadata
        ?.updateIntervalHours
        ?.let { formatAutoUpdateFull(it) }
        ?: noValue
    val resolvedServerValue by produceState(
        initialValue = noValue,
        selectedServerLabel,
    ) {
        val target = selectedServerLabel.trim()
        value = when {
            target.isBlank() || target == noValue -> noValue
            else -> withContext(Dispatchers.IO) {
                runCatching {
                    InetAddress.getAllByName(target)
                        .mapNotNull { it.hostAddress?.trim() }
                        .filter { it.isNotEmpty() }
                        .distinct()
                        .joinToString(", ")
                        .ifBlank { noValue }
                }.getOrElse { resolveFailed }
            }
        }
    }
    val exitIpValue by produceState(
        initialValue = noValue,
        socksAddress,
        socksPort,
        socksUsername,
        socksPassword,
    ) {
        val address = socksAddress.trim()
        val port = socksPort.trim()
        value = when {
            address.isBlank() || port.isBlank() -> noValue
            else -> withContext(Dispatchers.IO) {
                runCatching {
                    HttpHelper.resolveExitIpViaSocks(
                        socksAddress = address,
                        socksPort = port,
                        socksUsername = socksUsername.trim(),
                        socksPassword = socksPassword,
                    )
                }.getOrElse { resolveFailed }
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            shadowElevation = 24.dp,
            border = BorderStroke(1.dp, YaxcTheme.extendedColors.cardBorder),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = textResource(R.string.mainConnectionInfo),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )

                ConnectionInfoRow(
                    label = textResource(R.string.mainServerAddress),
                    value = selectedServerLabel.ifBlank { textResource(R.string.noValue) },
                )
                ConnectionInfoRow(
                    label = textResource(R.string.mainResolvedAddress),
                    value = resolvedServerValue,
                )
                ConnectionInfoRow(
                    label = textResource(R.string.mainExitAddress),
                    value = exitIpValue,
                )
                if (selectedSourceMetadata != null) {
                    ConnectionInfoRow(
                        label = textResource(R.string.mainSubscriptionTrafficUsed),
                        value = trafficUsedValue,
                    )
                    ConnectionInfoRow(
                        label = textResource(R.string.mainSubscriptionTrafficLimit),
                        value = trafficLimitValue,
                    )
                    ConnectionInfoRow(
                        label = textResource(R.string.mainSubscriptionDaysLeft),
                        value = daysLeftValue,
                    )
                    ConnectionInfoRow(
                        label = textResource(R.string.mainSubscriptionExpiresAt),
                        value = expiresAtValue,
                    )
                    ConnectionInfoRow(
                        label = textResource(R.string.mainSubscriptionAutoUpdate),
                        value = autoUpdateValue,
                    )
                    selectedSourceMetadata.supportUrl?.takeIf { it.isNotBlank() }?.let {
                        ConnectionInfoRow(
                            label = textResource(R.string.mainSubscriptionSupportUrl),
                            value = it,
                        )
                    }
                    selectedSourceMetadata.profileWebPageUrl?.takeIf { it.isNotBlank() }?.let {
                        ConnectionInfoRow(
                            label = textResource(R.string.mainSubscriptionWebPage),
                            value = it,
                        )
                    }
                }
                ConnectionInfoRow(
                    label = textResource(R.string.socksAddress),
                    value = socksAddress.ifBlank { textResource(R.string.noValue) },
                )
                ConnectionInfoRow(
                    label = textResource(R.string.socksPort),
                    value = socksPort.ifBlank { textResource(R.string.noValue) },
                )
                ConnectionInfoRow(
                    label = textResource(R.string.socksUsername),
                    value = socksUsername.ifBlank { textResource(R.string.noValue) },
                )
                ConnectionInfoRow(
                    label = textResource(R.string.socksPassword),
                    value = if (showPassword) {
                        socksPassword.ifBlank { textResource(R.string.noValue) }
                    } else {
                        if (socksPassword.isBlank()) textResource(R.string.noValue)
                        else textResource(R.string.mainConnectionPasswordHidden)
                    },
                    trailing = {
                        if (socksPassword.isNotBlank()) {
                            IconButton(onClick = { showPassword = !showPassword }) {
                                Icon(
                                    imageVector = if (showPassword) {
                                        Icons.Outlined.VisibilityOff
                                    } else {
                                        Icons.Outlined.Visibility
                                    },
                                    contentDescription = null,
                                    tint = YaxcTheme.extendedColors.textMuted,
                                )
                            }
                        }
                    },
                )
                ConnectionInfoRow(
                    label = textResource(R.string.pingAddress),
                    value = pingAddress.ifBlank { textResource(R.string.noValue) },
                )
            }
        }
    }
}

@Composable
private fun ConnectionInfoRow(
    label: String,
    value: String,
    trailing: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = YaxcTheme.extendedColors.textMuted,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            trailing?.invoke()
        }
    }
}

@Composable
private fun subscriptionCardSummary(metadata: SubscriptionMetadata?): String? {
    if (metadata == null) return null
    val parts = buildList {
        formatTrafficSummaryShort(metadata)?.let(::add)
        formatDaysLeftShort(metadata.expireAtEpochSeconds)?.let(::add)
        metadata.updateIntervalHours?.let { interval ->
            add(formatAutoUpdateShort(interval))
        }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" • ")
}

@Composable
private fun formatTrafficSummaryShort(metadata: SubscriptionMetadata): String? {
    val usedBytes = metadata.usedBytes ?: return null
    val totalBytes = metadata.totalBytes
    return when {
        totalBytes == 0L && usedBytes == 0L -> stringResource(R.string.mainSubscriptionUnlimited)
        totalBytes == null -> stringResource(
            R.string.mainSubscriptionUsedShort,
            formatBytesCompact(usedBytes),
        )
        totalBytes == 0L -> stringResource(
            R.string.mainSubscriptionUsedShort,
            formatBytesCompact(usedBytes),
        )
        else -> stringResource(
            R.string.mainSubscriptionUsageShort,
            formatBytesCompact(usedBytes),
            formatBytesCompact(totalBytes),
        )
    }
}

@Composable
private fun formatDaysLeftShort(expireAtEpochSeconds: Long?): String? {
    val expireAt = expireAtEpochSeconds?.takeIf { it > 0L } ?: return null
    val zone = ZoneId.systemDefault()
    val now = Instant.now()
    if (expireAt <= now.epochSecond) {
        return stringResource(R.string.mainSubscriptionExpired)
    }
    val expireDate = Instant.ofEpochSecond(expireAt).atZone(zone).toLocalDate()
    val daysLeft = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(zone), expireDate)
    return when {
        daysLeft <= 0L -> stringResource(R.string.mainSubscriptionToday)
        else -> stringResource(R.string.mainSubscriptionDaysShort, daysLeft)
    }
}

@Composable
private fun formatDaysLeftFull(expireAtEpochSeconds: Long): String {
    val zone = ZoneId.systemDefault()
    val now = Instant.now()
    if (expireAtEpochSeconds <= now.epochSecond) {
        return stringResource(R.string.mainSubscriptionExpired)
    }
    val expireDate = Instant.ofEpochSecond(expireAtEpochSeconds).atZone(zone).toLocalDate()
    val daysLeft = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(zone), expireDate)
    return when {
        daysLeft <= 0L -> stringResource(R.string.mainSubscriptionToday)
        else -> stringResource(R.string.mainSubscriptionDaysFull, daysLeft)
    }
}

@Composable
private fun formatAutoUpdateShort(hours: Int): String {
    return stringResource(R.string.mainSubscriptionAutoUpdateShort, hours)
}

@Composable
private fun formatAutoUpdateFull(hours: Int): String {
    return stringResource(R.string.mainSubscriptionAutoUpdateFull, hours)
}

private fun formatExpiryDateTime(expireAtEpochSeconds: Long): String {
    return Instant.ofEpochSecond(expireAtEpochSeconds)
        .atZone(ZoneId.systemDefault())
        .format(
            DateTimeFormatter
                .ofLocalizedDateTime(FormatStyle.MEDIUM)
                .withLocale(Locale.getDefault())
        )
}

private fun formatBytesCompact(bytes: Long): String {
    val units = arrayOf("B", "KB", "MB", "GB", "TB", "PB")
    val locale = Locale.getDefault()
    var value = bytes.toDouble().coerceAtLeast(0.0)
    var unitIndex = 0
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex += 1
    }
    val formatted = when {
        value >= 100 || unitIndex == 0 -> value.toLong().toString()
        value >= 10 -> String.format(locale, "%.1f", value)
        else -> String.format(locale, "%.2f", value)
    }
    return "$formatted ${units[unitIndex]}"
}

@Composable
private fun FloatingConnectButton(
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
    val containerColor by animateColorAsState(
        targetValue = if (isRunning) {
            lerp(
                mainFloatingContainerColor(),
                YaxcTheme.extendedColors.success,
                if (yaxcIsLightTheme()) 0.26f else 0.22f,
            ).copy(alpha = if (yaxcIsLightTheme()) 0.98f else 0.96f)
        } else {
            mainFloatingContainerColor()
        },
        animationSpec = spring(dampingRatio = 0.88f, stiffness = 420f),
        label = "floating_connect_color",
    )
    val borderColor by animateColorAsState(
        targetValue = if (isRunning) {
            YaxcTheme.extendedColors.success.copy(alpha = if (yaxcIsLightTheme()) 0.50f else 0.42f)
        } else {
            mainFloatingBorderColor()
        },
        animationSpec = spring(dampingRatio = 0.88f, stiffness = 420f),
        label = "floating_connect_border",
    )
    val iconTint by animateColorAsState(
        targetValue = if (isRunning) YaxcTheme.extendedColors.success else yaxcSoftOnSurface(darkAlpha = 0.94f, lightAlpha = 0.90f),
        animationSpec = spring(dampingRatio = 0.88f, stiffness = 420f),
        label = "floating_connect_icon",
    )

    Box(
        modifier = modifier
            .shadow(16.dp, CircleShape, clip = false)
            .scale(scale),
    ) {
        MainFloatingSurface(
            modifier = Modifier
                .size(64.dp)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                ),
            shape = CircleShape,
            contentPadding = PaddingValues(0.dp),
            containerColor = containerColor,
            borderColor = borderColor,
            shadowElevation = 0.dp,
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

    MainFloatingSurface(
        modifier = modifier.fillMaxWidth(0.94f),
        shape = RoundedCornerShape(36.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
        shadowElevation = 14.dp,
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
    onNewProfile: () -> Unit,
    onNewSource: () -> Unit,
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
        MainFloatingSurface(
            shape = MaterialTheme.shapes.extraLarge,
            contentPadding = PaddingValues(horizontal = 13.dp, vertical = 8.dp),
        ) {
            Text(
                text = textResource(R.string.appName),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        if (showMiniBar) {
            MiniControlBar(
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
            MainFloatingSurface(
                modifier = Modifier.yaxcClickable(
                    shape = MaterialTheme.shapes.extraLarge,
                    onClick = { actionsExpanded = true },
                ),
                shape = MaterialTheme.shapes.extraLarge,
                contentPadding = PaddingValues(0.dp),
            ) {
                Box(
                    modifier = Modifier.size(40.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = textResource(R.string.newProfile),
                        tint = MaterialTheme.colorScheme.onSurface,
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
                    text = textResource(R.string.newSource),
                    onClick = {
                        actionsExpanded = false
                        onNewSource()
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
    val containerColor by animateColorAsState(
        targetValue = if (pressed) {
            mainFloatingContainerColor(pressed = true)
        } else {
            mainFloatingContainerColor()
        },
        animationSpec = spring(dampingRatio = 0.86f, stiffness = 560f),
        label = "mini_control_bar_color",
    )

    MainFloatingSurface(
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
        containerColor = containerColor,
        shadowElevation = 10.dp,
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
                    color = MaterialTheme.colorScheme.onSurface,
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
                    yaxcSoftOnSurface(darkAlpha = 0.68f, lightAlpha = 0.66f)
                },
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    yaxcSoftOnSurface(darkAlpha = 0.68f, lightAlpha = 0.66f)
                },
            )
        }
    }
}

@Composable
private fun MainFloatingSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(30.dp),
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    containerColor: Color = mainFloatingContainerColor(),
    borderColor: Color = mainFloatingBorderColor(),
    shadowElevation: androidx.compose.ui.unit.Dp = 16.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = shape,
        color = containerColor,
        tonalElevation = 0.dp,
        shadowElevation = shadowElevation,
        border = BorderStroke(1.dp, borderColor),
    ) {
        Box(
            modifier = Modifier.padding(contentPadding),
            content = content,
        )
    }
}

@Composable
@ReadOnlyComposable
private fun mainFloatingContainerColor(pressed: Boolean = false): Color {
    val scheme = MaterialTheme.colorScheme
    val isLight = yaxcIsLightTheme()
    val base = if (isLight) scheme.surfaceContainerHighest else scheme.surfaceContainerHigh
    val accent = if (isLight) scheme.primaryContainer else scheme.primaryContainer.copy(alpha = 0.86f)
    val blend = when {
        isLight && pressed -> 0.28f
        isLight -> 0.20f
        pressed -> 0.22f
        else -> 0.14f
    }
    val alpha = if (isLight) 0.98f else 0.96f
    return lerp(base, accent, blend).copy(alpha = alpha)
}

@Composable
@ReadOnlyComposable
private fun mainFloatingBorderColor(): Color {
    val scheme = MaterialTheme.colorScheme
    val base = YaxcTheme.extendedColors.cardBorder
    val accent = scheme.primary.copy(alpha = if (yaxcIsLightTheme()) 0.34f else 0.28f)
    return lerp(base, accent, if (yaxcIsLightTheme()) 0.5f else 0.36f)
}

@Composable
private fun SettingsActionCard(
    icon: ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    YaxcGlassPanel(
        modifier = Modifier.yaxcClickable(shape = RoundedCornerShape(28.dp), onClick = onClick),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Surface(
                color = yaxcSoftFill(darkAlpha = 0.10f, lightAlpha = 0.86f),
                shape = MaterialTheme.shapes.large,
            ) {
                Box(
                    modifier = Modifier.size(44.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = yaxcSoftOnSurface(darkAlpha = 0.86f, lightAlpha = 0.84f),
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
            modifier = Modifier
                .fillMaxWidth()
                .yaxcClickable(shape = RoundedCornerShape(22.dp), onClick = onToggleExpanded)
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .then(dragModifier),
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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
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
            yaxcSoftStroke(darkAlpha = 0.12f, lightAlpha = 0.92f)
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
                        text = textResource(R.string.copyProfileJson),
                        onClick = {
                            actionsExpanded = false
                            copyProfileJson(
                                context = context,
                                config = profile.profile.config,
                            )
                        },
                    )
                    YaxcLiquidDropdownMenuItem(
                        text = textResource(R.string.copyProfileDeepLink),
                        onClick = {
                            actionsExpanded = false
                            copyProfileDeepLink(
                                context = context,
                                scope = scope,
                                config = profile.profile.config,
                            )
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
        modifier = Modifier.yaxcClickable(shape = MaterialTheme.shapes.large, onClick = onClick),
        color = yaxcSoftFill(darkAlpha = 0.08f, lightAlpha = 0.84f),
        shape = MaterialTheme.shapes.large,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = BorderStroke(1.dp, yaxcSoftStroke(darkAlpha = 0.12f, lightAlpha = 0.92f)),
    ) {
        Box(
            modifier = Modifier.size(containerSize),
            contentAlignment = Alignment.Center,
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(iconSize),
                    strokeWidth = 2.dp,
                    color = yaxcSoftOnSurface(darkAlpha = 0.82f, lightAlpha = 0.8f),
                )
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = yaxcSoftOnSurface(darkAlpha = 0.76f, lightAlpha = 0.74f),
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

private fun copyProfileJson(
    context: Context,
    config: String,
) {
    val clipboardManager = context.getSystemService(ClipboardManager::class.java)
    clipboardManager?.setPrimaryClip(
        ClipData.newPlainText("profile-json", config),
    )
    Toast.makeText(
        context,
        context.getString(R.string.profileJsonCopied),
        Toast.LENGTH_SHORT,
    ).show()
}

private fun copyProfileDeepLink(
    context: Context,
    scope: CoroutineScope,
    config: String,
) {
    scope.launch {
        val shareLink = withContext(Dispatchers.Default) {
            XrayCore.share(config).trim()
        }
        if (shareLink.isBlank()) {
            Toast.makeText(
                context,
                context.getString(R.string.profileDeepLinkUnavailable),
                Toast.LENGTH_SHORT,
            ).show()
            return@launch
        }
        val clipboardManager = context.getSystemService(ClipboardManager::class.java)
        clipboardManager?.setPrimaryClip(
            ClipData.newPlainText("profile-deep-link", shareLink),
        )
        Toast.makeText(
            context,
            context.getString(R.string.profileDeepLinkCopied),
            Toast.LENGTH_SHORT,
        ).show()
    }
}

@Composable
private fun textResource(id: Int): String = stringResource(id)

@Composable
private fun textResource(id: Int, arg0: Int): String = stringResource(id, arg0)
