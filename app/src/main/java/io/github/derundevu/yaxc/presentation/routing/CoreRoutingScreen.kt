package io.github.derundevu.yaxc.presentation.routing

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.derundevu.yaxc.presentation.editor.JsonEditorState
import io.github.derundevu.yaxc.R
import io.github.derundevu.yaxc.database.Config
import io.github.derundevu.yaxc.helper.CoreRoutingEditorMode
import io.github.derundevu.yaxc.helper.CoreRoutingRule
import io.github.derundevu.yaxc.helper.CoreRoutingTransport
import io.github.derundevu.yaxc.presentation.designsystem.YaxcTheme
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcActionBubble
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcCard
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcJsonEditor
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcLiquidDropdownMenu
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcLiquidDropdownMenuItem
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcMessageDialog
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcScaffold
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcSwitchRow
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcTabOption
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcTabRow
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcTopAppBar
import io.github.derundevu.yaxc.presentation.designsystem.components.isYaxcTv
import io.github.derundevu.yaxc.presentation.designsystem.components.yaxcTvFocus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoreRoutingScreen(
    editorMode: CoreRoutingEditorMode,
    domainStrategy: String,
    rules: List<CoreRoutingRule>,
    unsupportedRuleCount: Int,
    isLoading: Boolean,
    isBusy: Boolean,
    savedRevision: Int,
    editorState: JsonEditorState,
    antifilterEnabled: Boolean,
    antifilterInstalled: Boolean,
    antifilterRouteCount: Int,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onAntifilterEnabledChange: (Boolean) -> Unit,
    onImportFromClipboard: () -> Unit,
    onExportJson: () -> Unit,
    onEditorModeChange: (CoreRoutingEditorMode) -> Unit,
    onDomainStrategyChange: (String) -> Unit,
    onRuleChange: (CoreRoutingRule) -> Unit,
    onAddRule: () -> Unit,
    onMoveRule: (Int, Int) -> Unit,
    onDeleteRule: (String) -> Unit,
    routingMode: Config.Mode,
    onRoutingModeChange: (Config.Mode) -> Unit,
    onImportFromProfile: () -> Unit,
) {
    val spacing = YaxcTheme.spacing
    val density = LocalDensity.current
    val navigationBarBottom = WindowInsets.navigationBars.getBottom(density)
    val gestureNavigation = navigationBarBottom <= with(density) { 32.dp.roundToPx() }
    val reservedBottomInset = if (gestureNavigation) 0.dp else with(density) { navigationBarBottom.toDp() }
    var expandedRuleId by rememberSaveable { mutableStateOf<String?>(null) }
    var previousRulesCount by remember { mutableIntStateOf(rules.size) }
    var actionsExpanded by remember { mutableStateOf(false) }
    var helpVisible by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val tvListState = if (isYaxcTv()) listState else null
    val scope = rememberCoroutineScope()
    val dragHysteresisPx = with(density) { 16.dp.toPx() }
    val itemSpacingPx = with(density) { spacing.md.toPx() }
    val reorder = remember(listState, dragHysteresisPx, itemSpacingPx) {
        RoutingReorderState(listState, scope, dragHysteresisPx, itemSpacingPx)
    }
    val ruleKeys = remember(rules) { rules.map { it.id } }
    val orderedRules = remember(rules, reorder.previewOrder) { reorder.ordered(rules) { it.id } }

    LaunchedEffect(savedRevision) {
        if (savedRevision > 0 && editorMode == CoreRoutingEditorMode.Visual) expandedRuleId = null
    }

    LaunchedEffect(rules.size) {
        if (rules.size > previousRulesCount && rules.isNotEmpty()) {
            expandedRuleId = rules.last().id
        } else if (expandedRuleId != null && rules.none { it.id == expandedRuleId }) {
            expandedRuleId = null
        }
        previousRulesCount = rules.size
    }

    reorder.synchronize(listOf(ruleKeys), !isLoading && !isBusy && editorMode == CoreRoutingEditorMode.Visual)

    YaxcScaffold(
        topBar = {
            YaxcTopAppBar(
                title = textResource(R.string.coreRouting),
                onBack = onBack,
                actions = {
                    IconButton(onClick = { helpVisible = true }, modifier = Modifier.yaxcTvFocus(CircleShape)) {
                        Icon(
                            imageVector = Icons.Outlined.HelpOutline,
                            contentDescription = textResource(R.string.coreRoutingHelp),
                        )
                    }
                    Box {
                        IconButton(
                            onClick = { actionsExpanded = true },
                            modifier = Modifier.testTag("core-routing:actions").yaxcTvFocus(CircleShape),
                            enabled = !isLoading && !isBusy,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.MoreVert,
                                contentDescription = null,
                            )
                        }
                        YaxcLiquidDropdownMenu(
                            expanded = actionsExpanded,
                            onDismissRequest = { actionsExpanded = false },
                        ) {
                            YaxcLiquidDropdownMenuItem(
                                text = textResource(R.string.save),
                                onClick = {
                                    actionsExpanded = false
                                    onSave()
                                },
                            )
                            YaxcLiquidDropdownMenuItem(
                                text = textResource(R.string.coreImportTitle),
                                onClick = {
                                    actionsExpanded = false
                                    onImportFromProfile()
                                },
                            )
                            YaxcLiquidDropdownMenuItem(
                                text = textResource(R.string.routingImportFromClipboard),
                                onClick = {
                                    actionsExpanded = false
                                    onImportFromClipboard()
                                },
                            )
                            YaxcLiquidDropdownMenuItem(
                                text = textResource(R.string.routingExportJson),
                                onClick = {
                                    actionsExpanded = false
                                    onExportJson()
                                },
                            )
                        }
                    }
                },
            )
        },
    ) {
        Column(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.md)
                    .padding(top = spacing.sm, bottom = spacing.xs),
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                YaxcTabRow(
                    options = Config.Mode.entries.map { mode ->
                        YaxcTabOption(
                            value = mode,
                            label = textResource(
                                when (mode) {
                                    Config.Mode.Disable -> R.string.coreModeProfile
                                    Config.Mode.Replace -> R.string.coreModeReplace
                                    Config.Mode.Merge -> R.string.coreModeCombine
                                }
                            ),
                            testTag = "core-routing:mode:${mode.name}",
                        )
                    },
                    selected = routingMode,
                    onSelect = onRoutingModeChange,
                    enabled = !isLoading && !isBusy,
                )
                Text(
                    text = textResource(
                        when (routingMode) {
                            Config.Mode.Disable -> R.string.coreModeProfileLead
                            Config.Mode.Replace -> R.string.coreModeReplaceLead
                            Config.Mode.Merge -> R.string.coreModeCombineLead
                        }
                    ),
                    modifier = Modifier.padding(horizontal = spacing.xs),
                    style = MaterialTheme.typography.bodySmall,
                    color = YaxcTheme.extendedColors.textMuted,
                )
                CoreRoutingModeTabs(
                    editorMode = editorMode,
                    enabled = !isLoading && !isBusy,
                    onEditorModeChange = onEditorModeChange,
                )
            }
            if (editorMode == CoreRoutingEditorMode.Json) {
                if (isLoading) {
                    LoadingCard(label = textResource(R.string.loadingConfig))
                } else {
                    YaxcJsonEditor(
                        state = editorState,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .testTag("core-routing:content")
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = spacing.md),
                    verticalArrangement = Arrangement.spacedBy(spacing.md),
                    contentPadding = PaddingValues(
                        top = spacing.sm,
                        bottom = spacing.xl + reservedBottomInset,
                    ),
                ) {

            item {
                YaxcTabRow(
                    options = domainStrategyOptions.map { strategy ->
                        YaxcTabOption(
                            value = strategy,
                            label = textResource(strategy.titleRes()),
                        )
                    },
                    selected = domainStrategy,
                    onSelect = onDomainStrategyChange,
                    enabled = !isLoading && !isBusy,
                )
            }

            if (unsupportedRuleCount > 0) {
                item {
                    YaxcCard {
                        Text(
                            text = textResource(R.string.coreRoutingUnsupportedRules, unsupportedRuleCount),
                            style = MaterialTheme.typography.bodyMedium,
                            color = YaxcTheme.extendedColors.textMuted,
                        )
                    }
                }
            }

            item {
                YaxcCard(contentPadding = PaddingValues(0.dp)) {
                    YaxcSwitchRow(
                        title = textResource(R.string.routingAntifilterTitle),
                        subtitle = if (antifilterInstalled) {
                            textResource(R.string.routingAntifilterEnabledLead, antifilterRouteCount)
                        } else {
                            textResource(R.string.routingAntifilterMissingLead)
                        },
                        checked = antifilterEnabled,
                        onCheckedChange = onAntifilterEnabledChange,
                        icon = Icons.Outlined.Public,
                        shape = RectangleShape,
                        contentPadding = YaxcTheme.paddings.card,
                    )
                }
            }

            item {
                FilledTonalButton(
                    onClick = onAddRule,
                    enabled = !isLoading && !isBusy,
                    modifier = Modifier.widthIn(min = 160.dp).yaxcTvFocus(),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = null,
                    )
                    Text(
                        text = textResource(R.string.routingAddRule),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }

            when {
                isLoading -> item {
                    LoadingCard(label = textResource(R.string.loadingConfig))
                }

                rules.isEmpty() -> item {
                    EmptyStateCard(
                        icon = Icons.Outlined.Route,
                        text = textResource(R.string.coreRoutingNoRules),
                    )
                }

                else -> itemsIndexed(
                    items = orderedRules,
                    key = { _, item -> item.id },
                ) { _, rule ->
                    val modelIndex = rules.indexOfFirst { it.id == rule.id }
                    CoreRoutingRuleCard(
                        rule = rule,
                        isExpanded = expandedRuleId == rule.id,
                        dragging = reorder.isDragging,
                        reorderActive = reorder.activeKey != null,
                        onToggleExpanded = {
                            expandedRuleId = if (expandedRuleId == rule.id) null else rule.id
                        },
                        onRuleChange = onRuleChange,
                        onToggleEnabled = { enabled ->
                            onRuleChange(rule.copy(enabled = enabled))
                        },
                        enabled = !isLoading && !isBusy,
                        index = modelIndex,
                        total = rules.size,
                        onMove = { to ->
                            moveRoutingItem(tvListState, rule.id, to - modelIndex) { onMoveRule(modelIndex, to) }
                        },
                        dragModifier = reorder.headerModifier(rule.id, ruleKeys, !isLoading && !isBusy, onMove = onMoveRule),
                        modifier = routingReorderItemModifier(reorder, rule.id)
                            .fillMaxWidth()
                            .testTag("core-routing:rule:${rule.id}"),
                        onDelete = { onDeleteRule(rule.id) },
                    )
                }
            }
        }
            }
        }

        if (helpVisible) {
            YaxcMessageDialog(
                title = textResource(R.string.coreRouting),
                message = textResource(R.string.coreRoutingModesHelp),
                confirmLabel = textResource(R.string.ok),
                onConfirm = { helpVisible = false },
                onDismiss = { helpVisible = false },
            )
        }
    }
}

@Composable
private fun CoreRoutingModeTabs(
    editorMode: CoreRoutingEditorMode,
    enabled: Boolean,
    onEditorModeChange: (CoreRoutingEditorMode) -> Unit,
) {
    YaxcTabRow(
        options = CoreRoutingEditorMode.entries.map { mode ->
            YaxcTabOption(
                value = mode,
                label = when (mode) {
                    CoreRoutingEditorMode.Visual -> textResource(R.string.routingModeVisual)
                    CoreRoutingEditorMode.Json -> textResource(R.string.routingModeJson)
                },
            )
        },
        selected = editorMode,
        onSelect = onEditorModeChange,
        enabled = enabled,
    )
}

@Composable
private fun CoreRoutingRuleCard(
    rule: CoreRoutingRule,
    isExpanded: Boolean,
    dragging: Boolean,
    reorderActive: Boolean,
    onToggleExpanded: () -> Unit,
    onRuleChange: (CoreRoutingRule) -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    enabled: Boolean,
    index: Int,
    total: Int,
    onMove: (Int) -> Unit,
    dragModifier: Modifier = Modifier,
    modifier: Modifier = Modifier,
    onDelete: () -> Unit,
) {
    val expandRotation by animateFloatAsState(
        targetValue = if (isExpanded && !dragging) 180f else 0f,
        animationSpec = tween(durationMillis = 240),
        label = "routing_rule_expand_rotation",
    )
    val television = isYaxcTv()
    val ruleName = rule.name.ifBlank { textResource(R.string.routingRuleName) }
    val moveUp = textResource(R.string.liveRoutingMoveUp, ruleName)
    val moveDown = textResource(R.string.liveRoutingMoveDown, ruleName)
    val headerTag = "core-routing:rule-header:${rule.id}"
    val expansionLabel = if (television) {
        textResource(if (isExpanded) R.string.liveRoutingCollapse else R.string.liveRoutingExpand)
    } else null
    val canReorder = enabled && !reorderActive
    val expandHeader = Modifier
        .yaxcTvFocus()
        .clip(MaterialTheme.shapes.medium)
        .clickable(onClickLabel = expansionLabel, onClick = onToggleExpanded)
        .semantics {
            customActions = if (canReorder) buildList {
                if (index > 0) add(CustomAccessibilityAction(moveUp) { onMove(index - 1); true })
                if (index in 0 until total - 1) add(CustomAccessibilityAction(moveDown) { onMove(index + 1); true })
            } else emptyList()
        }

    YaxcCard(
        modifier = modifier.then(
            if (reorderActive) Modifier else Modifier.animateContentSize(
                animationSpec = spring(dampingRatio = 0.92f, stiffness = 520f),
            ),
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (television) Modifier else Modifier.testTag(headerTag))
                    .then(dragModifier)
                    .then(if (television) Modifier else expandHeader),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.weight(1f)
                        .then(if (television) Modifier.testTag(headerTag).then(expandHeader) else Modifier),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = ruleName,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                        )
                        Text(
                            text = buildRuleSummary(rule),
                            style = MaterialTheme.typography.bodySmall,
                            color = YaxcTheme.extendedColors.textMuted,
                            maxLines = 1,
                        )
                    }
                    if (television) {
                        Icon(
                            Icons.Outlined.UnfoldMore,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.graphicsLayer { rotationZ = expandRotation },
                        )
                    }
                }
                if (television) {
                    RoutingMoveButtons(index, total, canReorder, moveUp, moveDown, onMove)
                }
                Switch(
                    checked = rule.enabled,
                    onCheckedChange = onToggleEnabled,
                    modifier = Modifier.yaxcTvFocus(CircleShape),
                )
                if (!television) {
                    YaxcActionBubble(
                        icon = Icons.Outlined.UnfoldMore,
                        onClick = onToggleExpanded,
                        modifier = Modifier.graphicsLayer { rotationZ = expandRotation },
                    )
                }
                YaxcActionBubble(
                    icon = Icons.Outlined.DeleteOutline,
                    onClick = onDelete,
                    contentDescription = textResource(R.string.liveRoutingDeleteRule),
                )
            }

            if (!dragging) AnimatedVisibility(
                visible = isExpanded,
                enter = if (reorderActive) EnterTransition.None else expandVertically(
                    animationSpec = tween(durationMillis = 260),
                    expandFrom = Alignment.Top,
                ) + fadeIn(animationSpec = tween(durationMillis = 180, delayMillis = 40)),
                exit = if (reorderActive) ExitTransition.None else shrinkVertically(
                    animationSpec = tween(durationMillis = 220),
                    shrinkTowards = Alignment.Top,
                ) + fadeOut(animationSpec = tween(durationMillis = 120)),
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedTextField(
                        value = rule.name,
                        onValueChange = { onRuleChange(rule.copy(name = it)) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(text = textResource(R.string.routingRuleName)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    )

                    Text(
                        text = textResource(R.string.routingRuleDomains),
                        style = MaterialTheme.typography.labelLarge,
                        color = YaxcTheme.extendedColors.textMuted,
                    )
                    OutlinedTextField(
                        value = rule.domainsText,
                        onValueChange = { onRuleChange(rule.copy(domainsText = it)) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(text = textResource(R.string.routingRuleDomains)) },
                        minLines = 2,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    )
                    FieldHint(text = textResource(R.string.routingRuleDomainsHint))

                    Text(
                        text = textResource(R.string.routingRuleIpValues),
                        style = MaterialTheme.typography.labelLarge,
                        color = YaxcTheme.extendedColors.textMuted,
                    )
                    OutlinedTextField(
                        value = rule.ipsText,
                        onValueChange = { onRuleChange(rule.copy(ipsText = it)) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(text = textResource(R.string.routingRuleIpValues)) },
                        minLines = 2,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    )
                    FieldHint(text = textResource(R.string.routingRuleIpHint))

                    OutlinedTextField(
                        value = rule.portsText,
                        onValueChange = { onRuleChange(rule.copy(portsText = it)) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(text = textResource(R.string.routingRulePorts)) },
                        minLines = 1,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    )
                    FieldHint(text = textResource(R.string.routingRulePortsHint))

                    OutlinedTextField(
                        value = rule.sourcePortsText,
                        onValueChange = { onRuleChange(rule.copy(sourcePortsText = it)) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(text = textResource(R.string.routingRuleSourcePorts)) },
                        minLines = 1,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    )
                    FieldHint(text = textResource(R.string.routingRuleSourcePortsHint))

                    OutlinedTextField(
                        value = rule.protocolsText,
                        onValueChange = { onRuleChange(rule.copy(protocolsText = it)) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(text = textResource(R.string.routingRuleProtocols)) },
                        minLines = 2,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    )
                    FieldHint(text = textResource(R.string.routingRuleProtocolsHint))

                    Text(
                        text = textResource(R.string.routingRuleNetwork),
                        style = MaterialTheme.typography.labelLarge,
                        color = YaxcTheme.extendedColors.textMuted,
                    )
                    YaxcTabRow(
                        options = CoreRoutingTransport.entries.map { transport ->
                            YaxcTabOption(
                                value = transport,
                                label = textResource(transport.titleRes()),
                            )
                        },
                        selected = rule.network,
                        onSelect = { onRuleChange(rule.copy(network = it)) },
                        enabled = enabled,
                        nested = true,
                    )

                    Text(
                        text = textResource(R.string.routingOutboundTag),
                        style = MaterialTheme.typography.labelLarge,
                        color = YaxcTheme.extendedColors.textMuted,
                    )
                    YaxcTabRow(
                        options = outboundTagOptions.map { outboundTag ->
                            YaxcTabOption(
                                value = outboundTag,
                                label = textResource(outboundTag.titleRes()),
                            )
                        },
                        selected = rule.outboundTag,
                        onSelect = { onRuleChange(rule.copy(outboundTag = it)) },
                        enabled = enabled,
                        nested = true,
                    )
                }
            }
        }
    }
}

@Composable
private fun FieldHint(
    text: String,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = YaxcTheme.extendedColors.textMuted,
    )
}

@Composable
private fun LoadingCard(
    label: String,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = YaxcTheme.extendedColors.textMuted,
        )
    }
}

@Composable
private fun EmptyStateCard(
    icon: ImageVector,
    text: String,
) {
    YaxcCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

private val domainStrategyOptions = listOf("IPIfNonMatch", "AsIs", "IPOnDemand")
private val outboundTagOptions = listOf("proxy", "direct", "block", "dns-out")

@Composable
private fun buildRuleSummary(rule: CoreRoutingRule): String {
    val matchers = buildList {
        if (rule.domainsText.isNotBlank()) add(textResource(R.string.routingMatchTypeDomain))
        if (rule.ipsText.isNotBlank()) add(textResource(R.string.routingMatchTypeIp))
        if (rule.portsText.isNotBlank()) add(textResource(R.string.routingMatchTypePort))
        if (rule.sourcePortsText.isNotBlank()) add(textResource(R.string.routingMatchTypeSourcePort))
        if (rule.protocolsText.isNotBlank()) add(textResource(R.string.routingMatchTypeProtocol))
    }.joinToString(" + ").ifBlank { textResource(R.string.noValue) }
    val status = textResource(
        if (rule.enabled) R.string.routingRuleStatusOn else R.string.routingRuleStatusOff
    )
    val transport = textResource(rule.network.titleRes())
    val outboundTag = textResource(rule.outboundTag.titleRes())
    return textResource(
        R.string.routingRuleSummary,
        matchers,
        transport,
        outboundTag,
        status,
    )
}

@Composable
private fun textResource(id: Int, vararg args: Any): String {
    return androidx.compose.ui.res.stringResource(id, *args)
}

private fun String.titleRes(): Int {
    return when (this) {
        "IPIfNonMatch" -> R.string.routingDomainStrategyIpIfNonMatch
        "AsIs" -> R.string.routingDomainStrategyAsIs
        "IPOnDemand" -> R.string.routingDomainStrategyIpOnDemand
        "proxy" -> R.string.routingOutboundProxy
        "direct" -> R.string.routingOutboundDirect
        "block" -> R.string.routingOutboundBlock
        "dns-out" -> R.string.routingOutboundDnsOut
        else -> R.string.noValue
    }
}

private fun CoreRoutingTransport.titleRes(): Int {
    return when (this) {
        CoreRoutingTransport.Any -> R.string.routingTransportAny
        CoreRoutingTransport.Tcp -> R.string.routingTransportTcp
        CoreRoutingTransport.Udp -> R.string.routingTransportUdp
    }
}
