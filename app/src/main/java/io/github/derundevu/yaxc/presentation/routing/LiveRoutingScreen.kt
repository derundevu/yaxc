package io.github.derundevu.yaxc.presentation.routing

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.derundevu.yaxc.R
import io.github.derundevu.yaxc.helper.AppDomainRule
import io.github.derundevu.yaxc.helper.LiveRoutingGroupStatus
import io.github.derundevu.yaxc.helper.LiveRoutingMemberStatus
import io.github.derundevu.yaxc.helper.ProxyGroup
import io.github.derundevu.yaxc.helper.ProxyGroupStrategy
import io.github.derundevu.yaxc.presentation.designsystem.YaxcTheme
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcCard
import io.github.derundevu.yaxc.viewmodel.LiveRoutingAppOption
import io.github.derundevu.yaxc.viewmodel.LiveRoutingProfileOption
import io.github.derundevu.yaxc.viewmodel.LiveRoutingViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveRoutingScreen(
    draft: LiveRoutingViewModel,
    connectionFailed: Boolean,
    onBack: () -> Unit,
    onOpenConnection: () -> Unit,
) {
    val spacing = YaxcTheme.spacing
    val ready = !draft.isLoading && !draft.isBusy
    val editable = ready && !draft.canReload
    val profilesById = remember(draft.profiles) { draft.profiles.associateBy { it.id } }
    val savedGroups = remember(draft.savedPlan) { draft.savedPlan?.groups?.associateBy { it.id }.orEmpty() }
    val runtimeGroups = remember(draft.runtimeStatus.groups) { draft.runtimeStatus.groups.associateBy { it.id } }
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val dragHysteresisPx = with(density) { 16.dp.toPx() }
    val scope = rememberCoroutineScope()
    val itemSpacingPx = with(density) { spacing.md.toPx() }
    val reorder = remember(listState, dragHysteresisPx, itemSpacingPx) {
        RoutingReorderState(listState, scope, dragHysteresisPx, itemSpacingPx)
    }
    val groupKeys = remember(draft.plan.groups) { draft.plan.groups.map { "group:${it.id}" } }
    val ruleKeys = remember(draft.plan.rules) { draft.plan.rules.map { "rule:${it.id}" } }
    val groups = remember(draft.plan.groups, reorder.previewOrder) {
        reorder.ordered(draft.plan.groups) { "group:${it.id}" }
    }
    val rules = remember(draft.plan.rules, reorder.previewOrder) {
        reorder.ordered(draft.plan.rules) { "rule:${it.id}" }
    }
    var expandedGroupId by rememberSaveable { mutableStateOf<String?>(null) }
    var expandedRuleId by rememberSaveable { mutableStateOf<String?>(null) }
    reorder.synchronize(listOf(groupKeys, ruleKeys), editable)
    var profilePicker by rememberSaveable { mutableStateOf<String?>(null) }
    var priorityPicker by rememberSaveable { mutableStateOf<String?>(null) }
    var showHelp by rememberSaveable { mutableStateOf(false) }
    var appPicker by rememberSaveable { mutableStateOf<String?>(null) }
    var targetPicker by rememberSaveable { mutableStateOf<String?>(null) }
    var defaultGroupPicker by rememberSaveable { mutableStateOf(false) }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    var confirmReload by rememberSaveable { mutableStateOf(false) }
    val requestBack = {
        if (!draft.isBusy) {
            if (draft.hasChanges) confirmDiscard = true else onBack()
        }
    }
    BackHandler(enabled = draft.hasChanges || draft.isBusy, onBack = requestBack)

    Scaffold(
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.liveRouting), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = requestBack, enabled = !draft.isBusy) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.liveRoutingBack))
                    }
                },
                actions = {
                    IconButton(onClick = { showHelp = true }, modifier = Modifier.testTag("live-routing:help")) {
                        Icon(Icons.AutoMirrored.Outlined.HelpOutline, stringResource(R.string.liveRoutingHelp))
                    }
                    TextButton(onClick = draft::save, enabled = editable && !draft.rootMode) {
                        Text(stringResource(R.string.save))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.testTag("live-routing:content").fillMaxSize().background(YaxcTheme.backgroundBrush)
                .padding(padding).navigationBarsPadding().imePadding(),
            contentPadding = PaddingValues(start = spacing.md, end = spacing.md, top = spacing.md, bottom = spacing.xl),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            if (draft.rootMode) item(key = "root-warning") {
                WarningCard(R.string.liveRoutingRootUnavailable)
            }
            if (!draft.appMatchingAvailable) item(key = "app-warning") {
                WarningCard(R.string.liveRoutingAppsUnavailable)
            }
            if (connectionFailed) item(key = "runtime-error") {
                YaxcCard {
                    Text(stringResource(R.string.liveRoutingRuntimeFailed), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onOpenConnection) { Text(stringResource(R.string.liveRoutingOpenConnection)) }
                }
            }
            draft.error?.let { error ->
                item(key = "draft-error") {
                    YaxcCard {
                        Text(stringResource(error), color = MaterialTheme.colorScheme.error)
                        if (draft.canReload) {
                            TextButton(onClick = { confirmReload = true }, enabled = ready) {
                                Text(stringResource(R.string.liveRoutingReload))
                            }
                        }
                    }
                }
            }
            if (draft.isLoading || draft.isBusy) item(key = "loading") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator()
                }
            }
            item(key = "groups-title") {
                SectionHeading(R.string.liveRoutingGroups, R.string.liveRoutingAddGroup, editable) {
                    val previousSize = draft.plan.groups.size
                    draft.addGroup()
                    if (draft.plan.groups.size > previousSize) expandedGroupId = draft.plan.groups.last().id
                }
            }
            if (draft.plan.groups.isEmpty()) item(key = "groups-empty") {
                Text(stringResource(R.string.liveRoutingNoGroups), color = YaxcTheme.extendedColors.textMuted)
            }
            if (draft.plan.groups.isNotEmpty()) item(key = "default-group") {
                YaxcCard(modifier = Modifier.testTag("live-routing:default-group")) {
                    Text(
                        stringResource(R.string.liveRoutingDefaultGroupLabel, draft.plan.defaultGroup?.name.orEmpty()),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        stringResource(R.string.liveRoutingDefaultGroupHint),
                        modifier = Modifier.padding(top = spacing.sm),
                        color = YaxcTheme.extendedColors.textMuted,
                    )
                    TextButton(
                        onClick = { defaultGroupPicker = true },
                        enabled = editable,
                        modifier = Modifier.testTag("live-routing:choose-default-group"),
                    ) {
                        Text(stringResource(R.string.liveRoutingChooseDefaultGroup))
                    }
                }
            }
            itemsIndexed(groups, key = { _, group -> "group:${group.id}" }) { index, group ->
                val interactionSource = remember(group.id) { MutableInteractionSource() }
                GroupCard(
                    group = group,
                    profiles = profilesById,
                    saved = savedGroups[group.id] == group,
                    running = draft.runtimeStatus.running,
                    runtime = runtimeGroups[group.id],
                    enabled = editable,
                    inUse = draft.plan.rules.any { it.target == group.id },
                    isExplicitDefault = draft.plan.defaultGroupId == group.id,
                    expanded = expandedGroupId == group.id,
                    dragging = reorder.isDragging,
                    reorderActive = reorder.activeKey != null,
                    index = index,
                    total = draft.plan.groups.size,
                    modifier = routingReorderItemModifier(reorder, "group:${group.id}"),
                    interactionSource = interactionSource,
                    dragModifier = reorder.headerModifier("group:${group.id}", groupKeys, editable, interactionSource, draft::moveGroup),
                    onToggleExpanded = { expandedGroupId = if (expandedGroupId == group.id) null else group.id },
                    onMove = { draft.moveGroup(index, it) },
                    onChange = draft::updateGroup,
                    onStrategyChange = { draft.selectGroupStrategy(group.id, it) },
                    onProfiles = { profilePicker = group.id },
                    onPriority = { priorityPicker = group.id },
                    onDelete = { draft.deleteGroup(group.id) },
                )
            }
            item(key = "rules-title") {
                SectionHeading(R.string.liveRoutingRules, R.string.routingAddRule, editable) {
                    val previousSize = draft.plan.rules.size
                    draft.addRule()
                    if (draft.plan.rules.size > previousSize) expandedRuleId = draft.plan.rules.last().id
                }
            }
            if (draft.plan.rules.isEmpty()) item(key = "rules-empty") {
                Text(stringResource(R.string.liveRoutingNoRules), color = YaxcTheme.extendedColors.textMuted)
            }
            itemsIndexed(rules, key = { _, rule -> "rule:${rule.id}" }) { index, rule ->
                val interactionSource = remember(rule.id) { MutableInteractionSource() }
                RuleCard(
                    rule = rule,
                    index = index,
                    total = draft.plan.rules.size,
                    domains = draft.domainText(rule),
                    targetName = targetLabel(rule.target, draft.plan.groups),
                    enabled = editable,
                    appsAvailable = draft.appMatchingAvailable,
                    expanded = expandedRuleId == rule.id,
                    dragging = reorder.isDragging,
                    reorderActive = reorder.activeKey != null,
                    modifier = routingReorderItemModifier(reorder, "rule:${rule.id}"),
                    interactionSource = interactionSource,
                    dragModifier = reorder.headerModifier("rule:${rule.id}", ruleKeys, editable, interactionSource, draft::moveRule),
                    onToggleExpanded = { expandedRuleId = if (expandedRuleId == rule.id) null else rule.id },
                    onChange = draft::updateRule,
                    onDomains = { draft.updateDomains(rule.id, it) },
                    onApps = { appPicker = rule.id },
                    onClearApps = { draft.selectPackages(rule.id, emptyList()) },
                    onTarget = { targetPicker = rule.id },
                    onMove = { draft.moveRule(index, it) },
                    onDelete = { draft.deleteRule(rule.id) },
                )
            }
        }
    }

    draft.plan.groups.firstOrNull { it.id == profilePicker }?.let { group ->
        ProfilePicker(
            group = group,
            profiles = draft.profiles,
            profilesById = profilesById,
            saved = savedGroups[group.id] == group,
            running = draft.runtimeStatus.running,
            runtime = runtimeGroups[group.id],
            onDismiss = { profilePicker = null },
            onConfirm = { ids, selected ->
                draft.selectProfiles(group.id, ids, selected)
                profilePicker = null
            },
        )
    }
    draft.plan.groups.firstOrNull { it.id == priorityPicker }?.let { group ->
        PriorityPicker(
            group = group,
            profiles = profilesById,
            saved = savedGroups[group.id] == group,
            running = draft.runtimeStatus.running,
            runtime = runtimeGroups[group.id],
            onDismiss = { priorityPicker = null },
            onConfirm = { ids, selected ->
                draft.selectProfiles(group.id, ids, selected)
                priorityPicker = null
            },
        )
    }
    if (showHelp) LiveRoutingHelp(onDismiss = { showHelp = false })
    draft.plan.rules.firstOrNull { it.id == appPicker }?.let { rule ->
        AppPicker(
            packages = rule.packages,
            apps = draft.apps,
            isLoading = draft.appsLoading,
            enabled = editable,
            onDismiss = { appPicker = null },
            onConfirm = {
                draft.selectPackages(rule.id, it)
                appPicker = null
            },
        )
    }
    draft.plan.rules.firstOrNull { it.id == targetPicker }?.let { rule ->
        TargetPicker(
            rule = rule,
            groups = draft.plan.groups,
            onDismiss = { targetPicker = null },
            onConfirm = {
                draft.selectTarget(rule.id, it)
                targetPicker = null
            },
        )
    }
    if (defaultGroupPicker && draft.plan.groups.isNotEmpty()) {
        DefaultGroupPicker(
            groups = draft.plan.groups,
            defaultGroupId = draft.plan.defaultGroupId,
            onDismiss = { defaultGroupPicker = false },
            onConfirm = {
                draft.selectDefaultGroup(it)
                defaultGroupPicker = false
            },
        )
    }
    if (confirmDiscard || confirmReload) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false; confirmReload = false },
            title = { Text(stringResource(R.string.liveRoutingDiscardTitle)) },
            text = { Text(stringResource(R.string.liveRoutingDiscardBody)) },
            confirmButton = {
                TextButton(onClick = {
                    if (confirmReload) draft.reload() else onBack()
                    confirmDiscard = false
                    confirmReload = false
                }) { Text(stringResource(R.string.liveRoutingDiscard)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false; confirmReload = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun WarningCard(message: Int) {
    YaxcCard { Text(stringResource(message), color = MaterialTheme.colorScheme.error) }
}

@Composable
private fun SectionHeading(title: Int, add: Int, enabled: Boolean, onAdd: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onAdd, enabled = enabled) { Icon(Icons.Outlined.Add, stringResource(add)) }
    }
}

@Composable
private fun RoutingCardHeader(
    name: String,
    summary: String,
    expanded: Boolean,
    enabled: Boolean,
    index: Int,
    total: Int,
    tag: String,
    dragModifier: Modifier,
    interactionSource: MutableInteractionSource,
    onToggleExpanded: () -> Unit,
    onMove: (Int) -> Unit,
    trailing: @Composable () -> Unit = {},
) {
    val expansionLabel = stringResource(if (expanded) R.string.liveRoutingCollapse else R.string.liveRoutingExpand)
    val moveUp = stringResource(R.string.liveRoutingMoveUp, name)
    val moveDown = stringResource(R.string.liveRoutingMoveDown, name)
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "live_routing_expand_rotation")
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).then(dragModifier)
                .clickable(interactionSource = interactionSource, indication = null, onClickLabel = expansionLabel, onClick = onToggleExpanded)
                .semantics {
                    stateDescription = expansionLabel
                    customActions = if (enabled) buildList {
                        if (index > 0) add(CustomAccessibilityAction(moveUp) { onMove(index - 1); true })
                        if (index < total - 1) add(CustomAccessibilityAction(moveDown) { onMove(index + 1); true })
                    } else emptyList()
                }.testTag(tag).padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Outlined.DragHandle, stringResource(R.string.liveRoutingDragToReorder), tint = YaxcTheme.extendedColors.textMuted)
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = YaxcTheme.extendedColors.textMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(Icons.Outlined.ExpandMore, null, Modifier.graphicsLayer { rotationZ = rotation })
        }
        trailing()
    }
}

@Composable
private fun EditorExpansion(expanded: Boolean, suppressed: Boolean, restoreImmediately: Boolean, content: @Composable ColumnScope.() -> Unit) {
    // Keep form state in the card, but remove variable-height bodies during a reorder as on Main.
    if (suppressed) return
    AnimatedVisibility(
        visible = expanded,
        enter = if (restoreImmediately) EnterTransition.None else
            expandVertically(tween(260), expandFrom = Alignment.Top) + fadeIn(tween(180, delayMillis = 40)),
        exit = if (restoreImmediately) ExitTransition.None else
            shrinkVertically(tween(220), shrinkTowards = Alignment.Top) + fadeOut(tween(120)),
    ) {
        Column(Modifier.fillMaxWidth().padding(top = 8.dp), content = content)
    }
}

@Composable
private fun GroupCard(
    group: ProxyGroup,
    profiles: Map<Long, LiveRoutingProfileOption>,
    saved: Boolean,
    running: Boolean,
    runtime: LiveRoutingGroupStatus?,
    enabled: Boolean,
    inUse: Boolean,
    isExplicitDefault: Boolean,
    expanded: Boolean,
    dragging: Boolean,
    reorderActive: Boolean,
    index: Int,
    total: Int,
    modifier: Modifier,
    dragModifier: Modifier,
    interactionSource: MutableInteractionSource,
    onToggleExpanded: () -> Unit,
    onMove: (Int) -> Unit,
    onChange: (ProxyGroup) -> Unit,
    onStrategyChange: (ProxyGroupStrategy) -> Unit,
    onProfiles: () -> Unit,
    onPriority: () -> Unit,
    onDelete: () -> Unit,
) {
    val spacing = YaxcTheme.spacing
    val matched = rememberMatchingRuntime(group, profiles, saved, runtime)
    var name by rememberSaveable(group.id, stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(group.name)) }
    LaunchedEffect(group.name) {
        if (name.text != group.name) name = TextFieldValue(group.name)
    }
    YaxcCard(
        modifier = modifier.testTag("live-routing:group:${group.id}")
            .clip(MaterialTheme.shapes.large).indication(interactionSource, LocalIndication.current),
    ) {
        RoutingCardHeader(
            name = group.name.ifBlank { stringResource(R.string.liveRoutingGroupName) },
            summary = stringResource(R.string.liveRoutingGroupSummary, group.profileIds.size, stringResource(group.strategy.titleResource())),
            expanded = expanded && !dragging,
            enabled = enabled,
            index = index,
            total = total,
            tag = "live-routing:group-header:${group.id}",
            dragModifier = dragModifier,
            interactionSource = interactionSource,
            onToggleExpanded = onToggleExpanded,
            onMove = onMove,
        )
        RuntimeSummary(group, profiles, saved, running, runtime, matched, compact = !expanded || dragging)
        EditorExpansion(expanded, suppressed = dragging, restoreImmediately = reorderActive) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it; onChange(group.copy(name = it.text)) },
                label = { Text(stringResource(R.string.liveRoutingGroupName)) },
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().testTag("live-routing:group-name:${group.id}"),
            )
            Text(stringResource(R.string.liveRoutingStrategy), Modifier.padding(top = spacing.sm), style = MaterialTheme.typography.labelLarge)
            // Fixed strategy choices, not a profile list.
            ProxyGroupStrategy.entries.forEach { strategy ->
                val label = stringResource(strategy.titleResource())
                Row(
                    Modifier.fillMaxWidth().selectable(
                        selected = group.strategy == strategy,
                        enabled = enabled,
                        role = Role.RadioButton,
                        onClick = { onStrategyChange(strategy) },
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = group.strategy == strategy, onClick = null, enabled = enabled)
                    Text(label)
                }
            }
            Row(
                Modifier.fillMaxWidth().toggleable(
                    value = group.checkAvailability,
                    enabled = enabled && group.strategy != ProxyGroupStrategy.LeastPing,
                    role = Role.Checkbox,
                    onValueChange = { onChange(group.copy(checkAvailability = it)) },
                ).padding(vertical = spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = group.checkAvailability, onCheckedChange = null,
                    enabled = enabled && group.strategy != ProxyGroupStrategy.LeastPing)
                Text(stringResource(R.string.liveRoutingCheckAvailability), Modifier.padding(start = spacing.sm))
            }
            FilledTonalButton(onClick = onProfiles, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.liveRoutingChooseProfiles, group.profileIds.size))
            }
            if (group.strategy == ProxyGroupStrategy.Select) {
                val selected = (group.selectedProfileId ?: group.profileIds.firstOrNull())?.let { profiles[it]?.name }
                    ?: stringResource(R.string.liveRoutingNoSelectedProfile)
                Text(
                    stringResource(if (group.checkAvailability) R.string.liveRoutingPreferredProfile else R.string.liveRoutingSelectedProfile, selected),
                    Modifier.padding(top = spacing.sm),
                )
                if (group.checkAvailability) {
                    Text(
                        stringResource(R.string.liveRoutingBackupCount, (group.profileIds.size - 1).coerceAtLeast(0)),
                        color = YaxcTheme.extendedColors.textMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = onPriority, enabled = enabled && group.profileIds.isNotEmpty()) {
                        Text(stringResource(R.string.liveRoutingEditPriority))
                    }
                }
            } else if (group.checkAvailability) {
                Text(
                    stringResource(if (group.strategy == ProxyGroupStrategy.LeastPing)
                        R.string.liveRoutingLeastPingHint else R.string.liveRoutingHealthFilterHint),
                    Modifier.padding(top = spacing.sm),
                    color = YaxcTheme.extendedColors.textMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
                if (group.strategy == ProxyGroupStrategy.LeastPing) {
                    TextButton(onClick = onPriority, enabled = enabled && group.profileIds.isNotEmpty()) {
                        Text(stringResource(R.string.liveRoutingEditPriority))
                    }
                }
            }
            if (isExplicitDefault || inUse) Text(
                stringResource(if (isExplicitDefault) R.string.liveRoutingDefaultGroupInUse else R.string.liveRoutingGroupInUse),
                Modifier.padding(top = spacing.sm),
                color = YaxcTheme.extendedColors.textMuted,
                style = MaterialTheme.typography.bodySmall,
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDelete, enabled = enabled && !inUse && !isExplicitDefault, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.DeleteOutline, null)
                    Text(stringResource(R.string.liveRoutingDeleteGroup))
                }
                IconButton(onClick = { onMove(index - 1) }, enabled = enabled && index > 0) {
                    Icon(Icons.Outlined.ArrowUpward, stringResource(R.string.liveRoutingMoveUp, group.name))
                }
                IconButton(onClick = { onMove(index + 1) }, enabled = enabled && index < total - 1) {
                    Icon(Icons.Outlined.ArrowDownward, stringResource(R.string.liveRoutingMoveDown, group.name))
                }
            }
        }
    }
}

private fun orderedMembers(group: ProxyGroup): List<Long> {
    val preferred = group.selectedProfileId
    return if (group.strategy == ProxyGroupStrategy.Select && preferred != null &&
        preferred in group.profileIds && preferred != group.profileIds.firstOrNull()
    ) {
        listOf(preferred) + group.profileIds.filterNot { it == preferred }
    } else {
        group.profileIds
    }
}

@Composable
private fun rememberMatchingRuntime(
    group: ProxyGroup,
    profiles: Map<Long, LiveRoutingProfileOption>,
    saved: Boolean,
    runtime: LiveRoutingGroupStatus?,
): LiveRoutingGroupStatus? {
    val ordered = remember(group.profileIds, group.strategy, group.selectedProfileId) { orderedMembers(group) }
    return remember(group, ordered, profiles, saved, runtime) {
        runtime?.takeIf { status ->
            saved && group.checkAvailability && status.id == group.id && status.strategy == group.strategy &&
                status.selectedProfileId == group.selectedProfileId &&
                status.members.size == ordered.size &&
                status.members.indices.all { index ->
                    val member = status.members[index]
                    member.profileId == ordered[index] && member.fingerprint.isNotEmpty() &&
                        member.fingerprint == profiles[member.profileId]?.fingerprint
                }
        }
    }
}

@Composable
private fun RuntimeSummary(
    group: ProxyGroup,
    profiles: Map<Long, LiveRoutingProfileOption>,
    saved: Boolean,
    running: Boolean,
    runtime: LiveRoutingGroupStatus?,
    matched: LiveRoutingGroupStatus?,
    compact: Boolean = false,
) {
    val message = when {
        !saved -> stringResource(R.string.liveRoutingHealthNotApplied)
        !group.checkAvailability && running && runtime != null -> stringResource(R.string.liveRoutingHealthMismatch)
        !group.checkAvailability -> stringResource(R.string.liveRoutingHealthDisabled)
        !running -> stringResource(R.string.liveRoutingHealthStopped)
        runtime == null -> stringResource(R.string.liveRoutingHealthUnknown)
        matched == null -> stringResource(R.string.liveRoutingHealthMismatch)
        matched.state == "error" -> stringResource(R.string.liveRoutingHealthError)
        matched.state == "checking" -> stringResource(R.string.liveRoutingHealthChecking)
        matched.state != "available" && matched.state != "unavailable" -> stringResource(R.string.liveRoutingHealthUnknown)
        matched.state == "unavailable" || matched.eligibleProfileIds.isEmpty() ->
            stringResource(R.string.liveRoutingHealthNoAvailable)
        group.strategy == ProxyGroupStrategy.Select || group.strategy == ProxyGroupStrategy.LeastPing -> {
            val active = matched.activeProfileId?.takeIf { it in matched.eligibleProfileIds }
            val name = active?.let { profiles[it]?.name }
            if (name == null) stringResource(R.string.liveRoutingHealthUnknown)
            else stringResource(if (group.strategy == ProxyGroupStrategy.LeastPing)
                R.string.liveRoutingLeastPingActive else R.string.liveRoutingHealthActive, name)
        }
        else -> stringResource(R.string.liveRoutingHealthParticipants, matched.eligibleProfileIds.size, group.profileIds.size)
    }
    Text(
        message,
        Modifier.padding(top = YaxcTheme.spacing.sm).testTag("live-routing:health:${group.id}"),
        color = if (matched?.state == "error" || matched?.state == "unavailable") {
            MaterialTheme.colorScheme.error
        } else {
            YaxcTheme.extendedColors.textMuted
        },
        style = MaterialTheme.typography.bodySmall,
        maxLines = if (compact) 1 else Int.MAX_VALUE,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun RuntimeMember(member: LiveRoutingMemberStatus?, active: Boolean, eligible: Boolean) {
    val label = when (member?.state) {
        "available" -> stringResource(R.string.liveRoutingMemberAvailable)
        "unavailable" -> stringResource(R.string.liveRoutingMemberUnavailable)
        else -> stringResource(R.string.liveRoutingMemberChecking)
    }
    Text(
        if (member?.state == "available" && member.lastCheckedAt > 0 && member.delayMs >= 0) {
            stringResource(R.string.liveRoutingMemberDelay, label, member.delayMs)
        } else {
            label
        },
        color = if (member?.state == "unavailable") MaterialTheme.colorScheme.error else YaxcTheme.extendedColors.textMuted,
        style = MaterialTheme.typography.bodySmall,
    )
    if (active || eligible) Text(
        stringResource(if (active) R.string.liveRoutingMemberActive else R.string.liveRoutingMemberEligible),
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun RuleCard(
    rule: AppDomainRule,
    index: Int,
    total: Int,
    domains: String,
    targetName: String,
    enabled: Boolean,
    appsAvailable: Boolean,
    expanded: Boolean,
    dragging: Boolean,
    reorderActive: Boolean,
    modifier: Modifier,
    dragModifier: Modifier,
    interactionSource: MutableInteractionSource,
    onToggleExpanded: () -> Unit,
    onChange: (AppDomainRule) -> Unit,
    onDomains: (String) -> Unit,
    onApps: () -> Unit,
    onClearApps: () -> Unit,
    onTarget: () -> Unit,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit,
) {
    val spacing = YaxcTheme.spacing
    val toggleLabel = stringResource(R.string.liveRoutingEnableRule, rule.name)
    val enabledLabel = stringResource(if (rule.enabled) R.string.liveRoutingRuleEnabled else R.string.liveRoutingRuleDisabled)
    var name by rememberSaveable(rule.id, stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(rule.name)) }
    var domainValue by rememberSaveable(rule.id, stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(domains)) }
    LaunchedEffect(rule.name) {
        if (name.text != rule.name) name = TextFieldValue(rule.name)
    }
    LaunchedEffect(domains) {
        if (domainValue.text != domains) domainValue = TextFieldValue(domains)
    }
    YaxcCard(
        modifier = modifier.testTag("live-routing:rule:${rule.id}")
            .clip(MaterialTheme.shapes.large).indication(interactionSource, LocalIndication.current),
    ) {
        RoutingCardHeader(
            name = rule.name.ifBlank { stringResource(R.string.routingRuleName) },
            summary = stringResource(R.string.liveRoutingRuleSummary, rule.packages.size, rule.domains.size, targetName),
            expanded = expanded && !dragging,
            enabled = enabled,
            index = index,
            total = total,
            tag = "live-routing:rule-header:${rule.id}",
            dragModifier = dragModifier,
            interactionSource = interactionSource,
            onToggleExpanded = onToggleExpanded,
            onMove = onMove,
            trailing = {
                Switch(
                    checked = rule.enabled,
                    onCheckedChange = { onChange(rule.copy(enabled = it)) },
                    enabled = enabled,
                    modifier = Modifier.semantics { contentDescription = toggleLabel; stateDescription = enabledLabel },
                )
            },
        )
        EditorExpansion(expanded, suppressed = dragging, restoreImmediately = reorderActive) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.liveRoutingRulePosition, index + 1, total), modifier = Modifier.weight(1f))
                IconButton(onClick = { onMove(index - 1) }, enabled = enabled && index > 0) {
                    Icon(Icons.Outlined.ArrowUpward, stringResource(R.string.liveRoutingMoveUp, rule.name))
                }
                IconButton(onClick = { onMove(index + 1) }, enabled = enabled && index < total - 1) {
                    Icon(Icons.Outlined.ArrowDownward, stringResource(R.string.liveRoutingMoveDown, rule.name))
                }
            }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it; onChange(rule.copy(name = it.text)) },
                label = { Text(stringResource(R.string.routingRuleName)) },
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().testTag("live-routing:rule-name:${rule.id}"),
            )
            FilledTonalButton(
                onClick = onApps,
                enabled = enabled && appsAvailable,
                modifier = Modifier.fillMaxWidth().padding(top = spacing.sm).testTag("live-routing:choose-apps:${rule.id}"),
            ) {
                Text(stringResource(R.string.liveRoutingChooseApps, rule.packages.size))
            }
            if (rule.packages.isNotEmpty()) TextButton(onClick = onClearApps, enabled = enabled) {
                Text(stringResource(R.string.liveRoutingClearApps))
            }
            OutlinedTextField(
                value = domainValue,
                onValueChange = { domainValue = it; onDomains(it.text) },
                label = { Text(stringResource(R.string.routingRuleDomains)) },
                supportingText = { Text(stringResource(R.string.liveRoutingDomainsHint)) },
                minLines = 2,
                maxLines = 5,
                enabled = enabled,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, keyboardType = KeyboardType.Text, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth().padding(top = spacing.sm).testTag("live-routing:rule-domains:${rule.id}"),
            )
            FilledTonalButton(onClick = onTarget, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.liveRoutingTarget, targetName))
            }
            TextButton(onClick = onDelete, enabled = enabled) {
                Icon(Icons.Outlined.DeleteOutline, null)
                Text(stringResource(R.string.liveRoutingDeleteRule))
            }
        }
    }
}

@Composable
private fun ProfilePicker(
    group: ProxyGroup,
    profiles: List<LiveRoutingProfileOption>,
    profilesById: Map<Long, LiveRoutingProfileOption>,
    saved: Boolean,
    running: Boolean,
    runtime: LiveRoutingGroupStatus?,
    onDismiss: () -> Unit,
    onConfirm: (List<Long>, Long?) -> Unit,
) {
    var query by rememberSaveable(group.id) { mutableStateOf("") }
    var ids by rememberSaveable(group.id) { mutableStateOf(group.profileIds) }
    var selected by rememberSaveable(group.id) { mutableStateOf(group.selectedProfileId ?: group.profileIds.firstOrNull()) }
    val unchanged = ids == group.profileIds && selected == (group.selectedProfileId ?: group.profileIds.firstOrNull())
    val includedIds = remember(ids) { ids.toHashSet() }
    val matched = rememberMatchingRuntime(group, profilesById, saved && unchanged, runtime)
    val members = remember(matched) { matched?.members?.associateBy { it.profileId }.orEmpty() }
    val eligible = remember(matched) { matched?.eligibleProfileIds?.toHashSet().orEmpty() }
    val missingLabel = stringResource(R.string.liveRoutingMissingProfileLabel)
    val options by produceState<List<LiveRoutingProfileOption>>(emptyList(), profiles, profilesById, ids, query) {
        value = withContext(Dispatchers.Default) {
            val missing = ids.filterNot { it in profilesById }.map { LiveRoutingProfileOption(it, missingLabel, "") }
            val all = if (missing.isEmpty()) profiles else missing + profiles
            val keyword = query.trim()
            if (keyword.isEmpty()) all else all.filter {
                it.name.contains(keyword, true) || it.source.contains(keyword, true)
            }
        }
    }
    PickerDialog(
        title = stringResource(R.string.liveRoutingProfilesTitle),
        onDismiss = onDismiss,
        onConfirm = { onConfirm(ids, if (unchanged) group.selectedProfileId else selected) },
        confirmEnabled = ids.isNotEmpty() && (group.strategy != ProxyGroupStrategy.Select || selected in ids),
    ) {
        SearchField(query, { query = it })
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
            item(key = "profile-help") {
                Text(
                    stringResource(if (group.checkAvailability) R.string.liveRoutingHealthProfilesHint else R.string.liveRoutingProfilesHint),
                    Modifier.padding(16.dp),
                )
                if (group.checkAvailability || !saved) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        RuntimeSummary(group, profilesById, saved && unchanged, running, runtime, matched)
                    }
                }
            }
            if (options.isEmpty()) item(key = "empty") {
                Text(stringResource(R.string.liveRoutingNoProfiles), Modifier.padding(16.dp))
            }
            items(options, key = { "profile:${it.id}" }) { option ->
                val included = option.id in includedIds
                Row(
                    modifier = Modifier.fillMaxWidth().toggleable(
                        value = included,
                        role = Role.Checkbox,
                        onValueChange = {
                            ids = if (it) ids + option.id else ids - option.id
                            if (selected !in ids) selected = ids.firstOrNull()
                        },
                    ).padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = included, onCheckedChange = null)
                    Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                        Text(option.name, style = MaterialTheme.typography.bodyLarge)
                        if (option.source.isNotBlank()) Text(option.source, color = YaxcTheme.extendedColors.textMuted, style = MaterialTheme.typography.bodySmall)
                        if (group.checkAvailability && included) {
                            if (group.strategy == ProxyGroupStrategy.Select && selected == option.id) Text(
                                stringResource(R.string.liveRoutingPrimary),
                                style = MaterialTheme.typography.labelMedium,
                            )
                            RuntimeMember(
                                member = members[option.id],
                                active = matched?.state == "available" &&
                                    (group.strategy == ProxyGroupStrategy.Select || group.strategy == ProxyGroupStrategy.LeastPing) &&
                                    matched.activeProfileId == option.id && option.id in eligible,
                                eligible = matched?.state == "available" &&
                                    group.strategy != ProxyGroupStrategy.Select && group.strategy != ProxyGroupStrategy.LeastPing &&
                                    option.id in eligible,
                            )
                        }
                    }
                    if (group.strategy == ProxyGroupStrategy.Select) {
                        val description = stringResource(
                            if (group.checkAvailability) R.string.liveRoutingSetPrimary else R.string.liveRoutingUseProfile,
                            option.name,
                        )
                        RadioButton(
                            selected = selected == option.id && included,
                            onClick = { selected = option.id },
                            enabled = included,
                            modifier = Modifier.semantics { contentDescription = description },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PriorityPicker(
    group: ProxyGroup,
    profiles: Map<Long, LiveRoutingProfileOption>,
    saved: Boolean,
    running: Boolean,
    runtime: LiveRoutingGroupStatus?,
    onDismiss: () -> Unit,
    onConfirm: (List<Long>, Long?) -> Unit,
) {
    val initial = remember(group.profileIds, group.selectedProfileId) { orderedMembers(group) }
    var order by rememberSaveable(group.id) { mutableStateOf(initial) }
    val unchanged = order == initial
    val matched = rememberMatchingRuntime(group, profiles, saved && unchanged, runtime)
    val members = remember(matched) { matched?.members?.associateBy { it.profileId }.orEmpty() }
    val eligible = remember(matched) { matched?.eligibleProfileIds?.toHashSet().orEmpty() }
    PickerDialog(
        title = stringResource(R.string.liveRoutingPriorityTitle),
        onDismiss = onDismiss,
        onConfirm = {
            if (unchanged) onConfirm(group.profileIds, group.selectedProfileId)
            else onConfirm(order, if (group.strategy == ProxyGroupStrategy.Select) order.firstOrNull() else group.selectedProfileId)
        },
        confirmEnabled = order.isNotEmpty(),
    ) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
            item(key = "priority-help") {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(if (group.strategy == ProxyGroupStrategy.LeastPing)
                        R.string.liveRoutingLeastPingHint else R.string.liveRoutingPriorityHint))
                    RuntimeSummary(group, profiles, saved && unchanged, running, runtime, matched)
                }
            }
            itemsIndexed(order, key = { _, id -> "priority:$id" }) { index, id ->
                val option = profiles[id]
                val name = option?.name ?: stringResource(R.string.liveRoutingMissingProfileLabel)
                val primaryDescription = stringResource(R.string.liveRoutingSetPrimary, name)
                Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        if (group.strategy == ProxyGroupStrategy.Select) RadioButton(
                            selected = index == 0,
                            onClick = { if (index > 0) order = listOf(id) + order.filterNot { it == id } },
                            modifier = Modifier.semantics { contentDescription = primaryDescription },
                        )
                        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                            Text(
                                if (group.strategy == ProxyGroupStrategy.LeastPing)
                                    stringResource(R.string.liveRoutingPriorityNumber, index + 1)
                                else if (index == 0) stringResource(R.string.liveRoutingPrimary)
                                else stringResource(R.string.liveRoutingBackupNumber, index),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(name, style = MaterialTheme.typography.bodyLarge)
                            if (option != null && option.source.isNotBlank()) Text(
                                option.source,
                                color = YaxcTheme.extendedColors.textMuted,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            RuntimeMember(
                                member = members[id],
                                active = matched?.state == "available" && matched.activeProfileId == id && id in eligible,
                                eligible = false,
                            )
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        IconButton(
                            onClick = { order = order.toMutableList().apply { add(index - 1, removeAt(index)) } },
                            enabled = index > 0,
                        ) {
                            Icon(Icons.Outlined.ArrowUpward, stringResource(R.string.liveRoutingMoveUp, name))
                        }
                        IconButton(
                            onClick = { order = order.toMutableList().apply { add(index + 1, removeAt(index)) } },
                            enabled = index < order.lastIndex,
                        ) {
                            Icon(Icons.Outlined.ArrowDownward, stringResource(R.string.liveRoutingMoveDown, name))
                        }
                    }
                }
            }
        }
    }
}

private class AppPickerRequest(
    val apps: List<LiveRoutingAppOption>,
    val packages: List<String>,
    val selected: List<String>,
    val query: String,
    val missingLabel: String,
)

private class AppPickerOptions(
    val request: AppPickerRequest,
    val apps: List<LiveRoutingAppOption>,
    val selected: Set<String>,
    val visiblePackages: Set<String>,
    val selectedVisibleCount: Int,
)

@Composable
private fun AppPicker(
    packages: List<String>,
    apps: List<LiveRoutingAppOption>,
    isLoading: Boolean,
    enabled: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var selected by rememberSaveable { mutableStateOf(packages) }
    val missingLabel = stringResource(R.string.liveRoutingMissingAppLabel)
    val request = remember(apps, packages, selected, query, missingLabel) {
        AppPickerRequest(apps, packages, selected, query, missingLabel)
    }
    val options by produceState<AppPickerOptions?>(null, request) {
        value = withContext(Dispatchers.Default) {
            val chosen = request.selected.toHashSet()
            val known = request.apps.mapTo(mutableSetOf()) { it.packageName }
            val missing = request.packages.filterNot { it in known }
                .map { LiveRoutingAppOption(it, request.missingLabel, false) }
            val all = if (missing.isEmpty()) request.apps else missing + request.apps
            val keyword = request.query.trim()
            val filtered = if (keyword.isEmpty()) all else all.filter {
                it.name.contains(keyword, true) || it.packageName.contains(keyword, true)
            }
            val sorted = filtered.sortedWith(
                compareBy<LiveRoutingAppOption> { it.packageName !in chosen }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.packageName }
                    .thenBy { it.packageName },
            )
            AppPickerOptions(
                request = request,
                apps = sorted,
                selected = chosen,
                visiblePackages = sorted.mapTo(mutableSetOf()) { it.packageName },
                selectedVisibleCount = sorted.count { it.packageName in chosen },
            )
        }
    }
    val current = options
    val pending = current?.request !== request
    val selectionEnabled = enabled && !isLoading && !pending
    PickerDialog(
        title = stringResource(R.string.liveRoutingAppsTitle),
        onDismiss = onDismiss,
        onConfirm = { onConfirm(selected) },
        confirmEnabled = enabled && !isLoading,
    ) {
        SearchField(query, { query = it }, Modifier.testTag("live-routing:app-search"))
        VisibleAppsSelection(
            selectedCount = selected.size,
            visibleCount = current?.apps?.size ?: 0,
            selectedVisibleCount = current?.selectedVisibleCount ?: 0,
            enabled = selectionEnabled,
            onCheckedChange = { checked ->
                if (selectionEnabled) {
                    selected = if (checked) {
                        selected + current.visiblePackages.filterNot { it in current.selected }
                    } else {
                        selected.filterNot { it in current.visiblePackages }
                    }
                }
            },
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        if (isLoading || pending) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
        LazyColumn(Modifier.weight(1f).testTag("live-routing:apps-list"), contentPadding = PaddingValues(bottom = 16.dp)) {
            item(key = "app-help") {
                Text(stringResource(R.string.liveRoutingAppIdentityHint), Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
            }
            if (current?.apps.isNullOrEmpty() && !isLoading && !pending) item(key = "apps-empty") {
                Text(stringResource(R.string.noAppsFound), Modifier.padding(16.dp))
            }
            items(current?.apps.orEmpty(), key = { it.packageName }) { option ->
                val checked = option.packageName in current?.selected.orEmpty()
                Row(
                    Modifier.fillMaxWidth().testTag("live-routing:app:${option.packageName}").toggleable(
                        value = checked,
                        enabled = selectionEnabled,
                        role = Role.Checkbox,
                        onValueChange = { selected = if (it) selected + option.packageName else selected - option.packageName },
                    ).padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = checked, onCheckedChange = null, enabled = selectionEnabled)
                    Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                        Text(option.name, style = MaterialTheme.typography.bodyLarge)
                        Text(option.packageName, color = YaxcTheme.extendedColors.textMuted, style = MaterialTheme.typography.bodySmall)
                        if (option.excluded) Text(stringResource(R.string.liveRoutingAppExcluded), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun TargetPicker(rule: AppDomainRule, groups: List<ProxyGroup>, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var selected by rememberSaveable(rule.id) { mutableStateOf(rule.target) }
    PickerDialog(stringResource(R.string.liveRoutingTargetTitle), onDismiss, { onConfirm(selected) }) {
        Text(
            stringResource(R.string.liveRoutingDefaultRouteHint),
            Modifier.padding(16.dp),
            color = YaxcTheme.extendedColors.textMuted,
            style = MaterialTheme.typography.bodySmall,
        )
        LazyColumn(Modifier.weight(1f)) {
            items(listOf("proxy", "direct", "block") + groups.map { it.id }, key = { it }) { target ->
                Row(
                    Modifier.fillMaxWidth().selectable(
                        selected = selected == target,
                        role = Role.RadioButton,
                        onClick = { selected = target },
                    ).padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected == target, onClick = null)
                    Text(targetLabel(target, groups), Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun DefaultGroupPicker(
    groups: List<ProxyGroup>,
    defaultGroupId: String?,
    onDismiss: () -> Unit,
    onConfirm: (String?) -> Unit,
) {
    var selected by rememberSaveable { mutableStateOf(defaultGroupId) }
    PickerDialog(
        title = stringResource(R.string.liveRoutingChooseDefaultGroup),
        onDismiss = onDismiss,
        onConfirm = { onConfirm(selected) },
        confirmEnabled = selected == null || groups.any { it.id == selected },
    ) {
        LazyColumn(Modifier.weight(1f)) {
            items(
                count = groups.size + 1,
                key = { index -> if (index == 0) "automatic" else "group:${groups[index - 1].id}" },
            ) { index ->
                val group = groups.getOrNull(index - 1)
                val id = group?.id
                Row(
                    Modifier.fillMaxWidth().selectable(
                        selected = selected == id,
                        role = Role.RadioButton,
                        onClick = { selected = id },
                    ).padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected == id, onClick = null)
                    Text(
                        group?.name ?: stringResource(R.string.liveRoutingDefaultGroupAutomatic, groups.first().name),
                        Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun LiveRoutingHelp(onDismiss: () -> Unit) {
    val spacing = YaxcTheme.spacing
    val sections = remember {
        listOf(
            R.string.liveRoutingHelpSubscriptionsTitle to R.string.liveRoutingHelpSubscriptions,
            R.string.liveRoutingHelpSetupTitle to R.string.liveRoutingHelpSetup,
            R.string.liveRoutingHelpConnectionsTitle to R.string.liveRoutingLead,
            R.string.liveRoutingHelpDefaultsTitle to R.string.liveRoutingHelpDefaults,
            R.string.liveRoutingHelpStrategiesTitle to R.string.liveRoutingHelpStrategies,
            R.string.liveRoutingHelpRulesTitle to R.string.liveRoutingSemantics,
            R.string.liveRoutingHelpExamplesTitle to R.string.liveRoutingHelpExamples,
            R.string.liveRoutingHelpHealthTitle to R.string.liveRoutingHelpHealth,
            R.string.liveRoutingHelpHealthLimitsTitle to R.string.liveRoutingHelpHealthLimits,
            R.string.liveRoutingHelpUpdatesTitle to R.string.liveRoutingHelpUpdates,
            R.string.liveRoutingHelpAppsTitle to R.string.liveRoutingExcludedApps,
            R.string.liveRoutingHelpIdentityTitle to R.string.liveRoutingAppIdentityHint,
            R.string.liveRoutingHelpModesTitle to R.string.liveRoutingHelpModes,
        )
    }
    PickerDialog(title = stringResource(R.string.liveRoutingHelp), onDismiss = onDismiss, onConfirm = onDismiss) {
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().background(YaxcTheme.backgroundBrush).testTag("live-routing:help-content"),
            contentPadding = PaddingValues(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            items(sections, key = { it.first }) { (title, body) ->
                val warning = title == R.string.liveRoutingHelpSubscriptionsTitle
                YaxcCard {
                    Text(
                        stringResource(title),
                        Modifier.semantics { heading() },
                        color = if (warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        stringResource(body),
                        Modifier.padding(top = spacing.sm),
                        color = if (warning) MaterialTheme.colorScheme.error else YaxcTheme.extendedColors.textMuted,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PickerDialog(
    title: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    confirmEnabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.fillMaxSize().systemBarsPadding().imePadding(),
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Scaffold(
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onSurface,
                topBar = {
                    TopAppBar(
                        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        navigationIcon = {
                            IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.cancel)) }
                        },
                        actions = { TextButton(onClick = onConfirm, enabled = confirmEnabled) { Text(stringResource(R.string.ok)) } },
                    )
                },
            ) { padding ->
                Column(Modifier.fillMaxSize().padding(padding), content = content)
            }
        }
    }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(R.string.search)) },
        singleLine = true,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
    )
}

private fun ProxyGroupStrategy.titleResource(): Int = when (this) {
    ProxyGroupStrategy.Select -> R.string.liveRoutingStrategySelect
    ProxyGroupStrategy.Random -> R.string.liveRoutingStrategyRandom
    ProxyGroupStrategy.RoundRobin -> R.string.liveRoutingStrategyRoundRobin
    ProxyGroupStrategy.LeastPing -> R.string.liveRoutingLeastPing
}

@Composable
private fun targetLabel(target: String, groups: List<ProxyGroup>): String = when (target) {
    "proxy" -> stringResource(R.string.liveRoutingDefaultTarget)
    "direct" -> stringResource(R.string.routingOutboundDirect)
    "block" -> stringResource(R.string.routingOutboundBlock)
    else -> groups.firstOrNull { it.id == target }?.name ?: stringResource(R.string.liveRoutingMissingGroup)
}
