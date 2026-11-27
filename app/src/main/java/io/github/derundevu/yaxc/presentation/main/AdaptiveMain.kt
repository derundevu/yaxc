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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.AltRoute
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ToggleOn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.derundevu.yaxc.R
import io.github.derundevu.yaxc.presentation.designsystem.YaxcTheme
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcCard
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcGlassPanel
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcLiquidDropdownMenu
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcLiquidDropdownMenuItem
import io.github.derundevu.yaxc.presentation.designsystem.components.yaxcClickable
import io.github.derundevu.yaxc.presentation.designsystem.components.yaxcTvFocus
import io.github.derundevu.yaxc.presentation.designsystem.yaxcSoftOnSurface

internal enum class MainRootTab { Connect, Routing, Settings }

internal class MainTvFocus {
    val rail = FocusRequester()
    val connect = FocusRequester()
    val add = FocusRequester()
    val profiles = FocusRequester()
    val content = FocusRequester()
}

@Composable
internal fun AdaptiveMain(
    tab: MainRootTab,
    onSelectTab: (MainRootTab) -> Unit,
    isTv: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable (MainTvFocus?) -> Unit,
) {
    val focus = if (isTv) remember { MainTvFocus() } else null
    val inputModeManager = LocalInputModeManager.current
    var focusOnEntry by remember { mutableStateOf(isTv) }
    BackHandler(enabled = isTv && tab != MainRootTab.Connect) {
        onSelectTab(MainRootTab.Connect)
        focusOnEntry = true
    }
    LaunchedEffect(focusOnEntry, tab) {
        if (focus != null && focusOnEntry) {
            inputModeManager.requestInputMode(InputMode.Keyboard)
            (if (tab == MainRootTab.Connect) focus.connect else focus.rail).requestFocus()
            focusOnEntry = false
        }
    }

    Row(
        modifier = modifier
            .fillMaxSize()
            .background(YaxcTheme.backgroundBrush)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = if (isTv) 24.dp else 12.dp, vertical = if (isTv) 6.dp else 0.dp),
        horizontalArrangement = Arrangement.spacedBy(if (isTv) 20.dp else 12.dp),
    ) {
        Column(
            modifier = Modifier
                .width(if (isTv) 124.dp else 108.dp)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MainBrandPill()
            MainFloatingSurface(
                modifier = Modifier.fillMaxWidth(),
                shape = YaxcTheme.shapes.extraLarge,
                contentPadding = YaxcTheme.paddings.dense,
                shadowElevation = 14.dp,
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().selectableGroup().focusGroup(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val itemShape = YaxcTheme.shapes.large
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
                        val focusModifier = if (focus != null) {
                            Modifier.then(if (selected) Modifier.focusRequester(focus.rail) else Modifier)
                                .focusProperties {
                                    if (tab == MainRootTab.Connect) end = focus.connect
                                }
                        } else {
                            Modifier
                        }
                        val itemColor = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            yaxcSoftOnSurface(darkAlpha = 0.68f, lightAlpha = 0.66f)
                        }
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(focusModifier)
                                .testTag("main:rail:${destination.name.lowercase()}")
                                .yaxcTvFocus(itemShape)
                                .clip(itemShape)
                                .selectable(selected = selected, role = Role.Tab) { onSelectTab(destination) },
                            color = if (selected) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.96f)
                            } else {
                                Color.Transparent
                            },
                            contentColor = itemColor,
                            shape = itemShape,
                            border = if (selected) {
                                BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.18f))
                            } else {
                                null
                            },
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 10.dp, horizontal = 6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = null,
                                    tint = itemColor,
                                    modifier = Modifier.size(22.dp),
                                )
                                Text(
                                    text = label,
                                    color = itemColor,
                                    style = MaterialTheme.typography.labelSmall,
                                    textAlign = TextAlign.Center,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(top = if (isTv) 18.dp else 12.dp),
        ) { content(focus) }
    }
}

@Composable
internal fun AdaptiveProfileHeading(
    isTv: Boolean,
    focus: MainTvFocus?,
    onAction: (MainAction) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = YaxcTheme.spacing.md),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.mainProfilesTitle),
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        MainAddActionButton(
            isTv = isTv,
            showDeviceTransfer = true,
            onAction = onAction,
            modifier = if (focus == null) {
                Modifier.testTag("main:adaptive:add")
            } else {
                Modifier
                    .testTag("main:adaptive:add")
                    .focusRequester(focus.add)
                    .focusProperties { start = focus.connect }
            },
        )
    }
}

@Composable
internal fun AdaptiveConnectionControls(
    isRunning: Boolean,
    isBlocked: Boolean,
    selectedProfileName: String,
    isTv: Boolean,
    focus: MainTvFocus?,
    actions: @Composable () -> List<MainMenuAction>,
    onAction: (MainAction) -> Unit,
    modifier: Modifier = Modifier,
    connectionCard: @Composable () -> Unit,
) {
    val quickActionsLabel = stringResource(R.string.mainQuickActions)
    var quickActionsExpanded by remember { mutableStateOf(false) }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .widthIn(max = 492.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                // Leave headroom for FloatingConnectButton and card elevation inside the scroll clip.
                .padding(horizontal = 24.dp, vertical = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            connectionCard()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    MainFloatingSurface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 64.dp)
                            .then(if (focus == null) Modifier else Modifier.focusProperties { start = focus.rail })
                            .yaxcClickable(shape = YaxcTheme.shapes.pill) { quickActionsExpanded = true },
                        shape = YaxcTheme.shapes.pill,
                        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
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
                                    text = stringResource(
                                        when {
                                            isBlocked -> R.string.killSwitchBlocked
                                            isRunning -> R.string.mainStatusRunning
                                            else -> R.string.mainStatusStopped
                                        },
                                    ),
                                    color = if (isBlocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = selectedProfileName.ifBlank { quickActionsLabel },
                                    color = YaxcTheme.extendedColors.textMuted,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Icon(
                                imageVector = Icons.Outlined.MoreVert,
                                contentDescription = quickActionsLabel,
                                tint = yaxcSoftOnSurface(darkAlpha = 0.76f, lightAlpha = 0.74f),
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }

                    MainActionMenu(
                        title = quickActionsLabel,
                        isTv = isTv,
                        expanded = quickActionsExpanded,
                        onDismiss = { quickActionsExpanded = false },
                        actions = actions,
                    )
                }

                FloatingConnectButton(
                    isRunning = isRunning,
                    isBlocked = isBlocked,
                    onClick = { onAction(MainAction.ToggleVpnClicked) },
                    size = if (isTv) 72.dp else 64.dp,
                    iconSize = if (isTv) 30.dp else 26.dp,
                    modifier = Modifier
                        .testTag("main:adaptive:connect")
                        .then(
                            if (focus == null) {
                                Modifier
                            } else {
                                Modifier
                                    .focusRequester(focus.connect)
                                    .focusProperties { end = focus.add }
                            },
                        ),
                )
            }
        }
    }
}

internal data class MainMenuAction(val label: String, val onClick: () -> Unit)

@Composable
internal fun mainMenuAction(label: Int, onClick: () -> Unit) = MainMenuAction(stringResource(label), onClick)

@Composable
internal fun MainActionMenu(
    title: String,
    isTv: Boolean,
    expanded: Boolean,
    onDismiss: () -> Unit,
    actions: @Composable () -> List<MainMenuAction>,
) {
    if (isTv) {
        if (!expanded) return
        val initialItem = remember { FocusRequester() }
        Dialog(onDismissRequest = onDismiss) {
            YaxcGlassPanel(
                modifier = Modifier.widthIn(max = 420.dp),
                contentPadding = PaddingValues(18.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    actions().forEachIndexed { index, action ->
                        YaxcCard(
                            onClick = { onDismiss(); action.onClick() },
                            shape = MaterialTheme.shapes.medium,
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                            modifier = if (index == 0) Modifier.focusRequester(initialItem) else Modifier,
                        ) {
                            Text(
                                text = action.label,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                    LaunchedEffect(Unit) { initialItem.requestFocus() }
                }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End).padding(top = 8.dp).yaxcTvFocus(CircleShape),
                ) {
                    Text(stringResource(R.string.connectionDismiss))
                }
            }
        }
    } else {
        YaxcLiquidDropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
            actions().forEach { action ->
                YaxcLiquidDropdownMenuItem(text = action.label, onClick = { onDismiss(); action.onClick() })
            }
        }
    }
}
