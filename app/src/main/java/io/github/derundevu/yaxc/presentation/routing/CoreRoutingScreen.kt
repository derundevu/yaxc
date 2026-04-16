package io.github.derundevu.yaxc.presentation.routing

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.blacksquircle.ui.editorkit.widget.TextProcessor
import io.github.derundevu.yaxc.R
import io.github.derundevu.yaxc.helper.CoreRoutingEditorMode
import io.github.derundevu.yaxc.helper.CoreRoutingMatchType
import io.github.derundevu.yaxc.helper.CoreRoutingRule
import io.github.derundevu.yaxc.helper.CoreRoutingTransport
import io.github.derundevu.yaxc.presentation.designsystem.YaxcTheme
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcCard
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcJsonEditor
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcLiquidDropdownMenu
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcLiquidDropdownMenuItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoreRoutingScreen(
    editorMode: CoreRoutingEditorMode,
    domainStrategy: String,
    rules: List<CoreRoutingRule>,
    unsupportedRuleCount: Int,
    isLoading: Boolean,
    onBack: () -> Unit,
    onSave: () -> Boolean,
    onImportFromClipboard: () -> Unit,
    onExportJson: () -> Unit,
    onEditorModeChange: (CoreRoutingEditorMode) -> Unit,
    onDomainStrategyChange: (String) -> Unit,
    onRuleChange: (CoreRoutingRule) -> Unit,
    onAddRule: () -> Unit,
    onDeleteRule: (String) -> Unit,
    onEditorReady: (TextProcessor) -> Unit,
) {
    val spacing = YaxcTheme.spacing
    val density = LocalDensity.current
    val navigationBarBottom = WindowInsets.navigationBars.getBottom(density)
    val gestureNavigation = navigationBarBottom <= with(density) { 32.dp.roundToPx() }
    val reservedBottomInset = if (gestureNavigation) 0.dp else with(density) { navigationBarBottom.toDp() }
    var expandedRuleId by rememberSaveable { mutableStateOf<String?>(null) }
    var previousRulesCount by remember { mutableIntStateOf(rules.size) }
    var actionsExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(rules.size) {
        if (rules.size > previousRulesCount && rules.isNotEmpty()) {
            expandedRuleId = rules.last().id
        } else if (expandedRuleId != null && rules.none { it.id == expandedRuleId }) {
            expandedRuleId = null
        }
        previousRulesCount = rules.size
    }

    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets.safeDrawing.only(
            WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
        ),
        topBar = {
            TopAppBar(
                title = { Text(text = textResource(R.string.coreRouting)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = null,
                        )
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { actionsExpanded = true }) {
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
                                    if (onSave() && editorMode == CoreRoutingEditorMode.Visual) {
                                        expandedRuleId = null
                                    }
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
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(YaxcTheme.backgroundBrush)
                .padding(innerPadding)
                .padding(horizontal = spacing.md)
                .padding(top = spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
            contentPadding = PaddingValues(bottom = spacing.xl + reservedBottomInset),
        ) {
            item {
                Text(
                    text = textResource(R.string.coreRoutingLead),
                    style = MaterialTheme.typography.bodyLarge,
                    color = YaxcTheme.extendedColors.textMuted,
                )
            }

            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                    shape = MaterialTheme.shapes.large,
                ) {
                    TabRow(selectedTabIndex = CoreRoutingEditorMode.entries.indexOf(editorMode)) {
                        CoreRoutingEditorMode.entries.forEach { mode ->
                            Tab(
                                selected = mode == editorMode,
                                onClick = { onEditorModeChange(mode) },
                                text = {
                                    Text(
                                        text = when (mode) {
                                            CoreRoutingEditorMode.Visual -> textResource(R.string.routingModeVisual)
                                            CoreRoutingEditorMode.Json -> textResource(R.string.routingModeJson)
                                        }
                                    )
                                },
                            )
                        }
                    }
                }
            }

            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                    shape = MaterialTheme.shapes.large,
                ) {
                    TabRow(selectedTabIndex = domainStrategyOptions.indexOf(domainStrategy).coerceAtLeast(0)) {
                        domainStrategyOptions.forEach { strategy ->
                            Tab(
                                selected = strategy == domainStrategy,
                                onClick = { onDomainStrategyChange(strategy) },
                                text = { Text(text = textResource(strategy.titleRes())) },
                            )
                        }
                    }
                }
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

            if (editorMode == CoreRoutingEditorMode.Visual) {
                item {
                    FilledTonalButton(
                        onClick = onAddRule,
                        modifier = Modifier.widthIn(min = 160.dp),
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
            }

            when {
                isLoading -> item {
                    LoadingCard(label = textResource(R.string.loadingConfig))
                }

                editorMode == CoreRoutingEditorMode.Json -> item {
                    CoreRoutingJsonCard(onEditorReady = onEditorReady)
                }

                rules.isEmpty() -> item {
                    EmptyStateCard(
                        icon = Icons.Outlined.Route,
                        text = textResource(R.string.coreRoutingNoRules),
                    )
                }

                else -> items(
                    items = rules,
                    key = { it.id },
                ) { rule ->
                    CoreRoutingRuleCard(
                        rule = rule,
                        isExpanded = expandedRuleId == rule.id,
                        onToggleExpanded = {
                            expandedRuleId = if (expandedRuleId == rule.id) null else rule.id
                        },
                        onRuleChange = onRuleChange,
                        onToggleEnabled = { enabled ->
                            onRuleChange(rule.copy(enabled = enabled))
                        },
                        onDelete = { onDeleteRule(rule.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun CoreRoutingJsonCard(
    onEditorReady: (TextProcessor) -> Unit,
) {
    val context = LocalContext.current

    YaxcCard(
        modifier = Modifier
            .fillMaxWidth()
            .height(560.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                text = textResource(R.string.coreRoutingJsonTitle),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 12.dp),
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(20.dp),
                    ),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(10.dp)
                        .background(
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.52f),
                            shape = RoundedCornerShape(16.dp),
                        )
                        .clickable(enabled = false, onClick = {}),
                ) {
                    YaxcJsonEditor(
                        context = context,
                        onEditorReady = onEditorReady,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun CoreRoutingRuleCard(
    rule: CoreRoutingRule,
    isExpanded: Boolean,
    onToggleExpanded: () -> Unit,
    onRuleChange: (CoreRoutingRule) -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val expandRotation by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        animationSpec = tween(durationMillis = 240),
        label = "routing_rule_expand_rotation",
    )

    YaxcCard(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(
                animationSpec = spring(
                    dampingRatio = 0.92f,
                    stiffness = 520f,
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
                    .clickable(onClick = onToggleExpanded),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = rule.name.ifBlank { textResource(R.string.routingRuleName) },
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
                Switch(
                    checked = rule.enabled,
                    onCheckedChange = onToggleEnabled,
                )
                ActionBubble(
                    icon = Icons.Outlined.UnfoldMore,
                    onClick = onToggleExpanded,
                    modifier = Modifier.graphicsLayer { rotationZ = expandRotation },
                )
                ActionBubble(
                    icon = Icons.Outlined.DeleteOutline,
                    onClick = onDelete,
                )
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(
                    animationSpec = tween(durationMillis = 260),
                    expandFrom = Alignment.Top,
                ) + fadeIn(animationSpec = tween(durationMillis = 180, delayMillis = 40)),
                exit = shrinkVertically(
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
                        text = textResource(R.string.routingOutboundTag),
                        style = MaterialTheme.typography.labelLarge,
                        color = YaxcTheme.extendedColors.textMuted,
                    )
                    TabRow(selectedTabIndex = outboundTagOptions.indexOf(rule.outboundTag).coerceAtLeast(0)) {
                        outboundTagOptions.forEach { outboundTag ->
                            Tab(
                                selected = outboundTag == rule.outboundTag,
                                onClick = { onRuleChange(rule.copy(outboundTag = outboundTag)) },
                                text = { Text(text = textResource(outboundTag.titleRes())) },
                            )
                        }
                    }

                    Text(
                        text = textResource(R.string.routingMatchType),
                        style = MaterialTheme.typography.labelLarge,
                        color = YaxcTheme.extendedColors.textMuted,
                    )
                    SecondaryScrollableTabRow(
                        selectedTabIndex = CoreRoutingMatchType.entries.indexOf(rule.matchType),
                        containerColor = Color.Transparent,
                        edgePadding = 0.dp,
                    ) {
                        CoreRoutingMatchType.entries.forEach { matchType ->
                            Tab(
                                selected = matchType == rule.matchType,
                                onClick = { onRuleChange(rule.copy(matchType = matchType)) },
                                text = { Text(text = textResource(matchType.titleRes())) },
                            )
                        }
                    }

                    Text(
                        text = textResource(R.string.routingTransport),
                        style = MaterialTheme.typography.labelLarge,
                        color = YaxcTheme.extendedColors.textMuted,
                    )
                    TabRow(selectedTabIndex = CoreRoutingTransport.entries.indexOf(rule.transport)) {
                        CoreRoutingTransport.entries.forEach { transport ->
                            Tab(
                                selected = transport == rule.transport,
                                onClick = { onRuleChange(rule.copy(transport = transport)) },
                                text = {
                                    Text(
                                        text = when (transport) {
                                            CoreRoutingTransport.Any -> textResource(R.string.routingTransportAny)
                                            CoreRoutingTransport.Tcp -> textResource(R.string.routingTransportTcp)
                                            CoreRoutingTransport.Udp -> textResource(R.string.routingTransportUdp)
                                        }
                                    )
                                },
                            )
                        }
                    }

                    OutlinedTextField(
                        value = rule.valuesText,
                        onValueChange = { onRuleChange(rule.copy(valuesText = it)) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(text = textResource(R.string.routingRuleValues)) },
                        minLines = 4,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionBubble(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerSize: androidx.compose.ui.unit.Dp = 40.dp,
    iconSize: androidx.compose.ui.unit.Dp = 20.dp,
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
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
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.76f),
                modifier = Modifier.size(iconSize),
            )
        }
    }
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
    val status = textResource(
        if (rule.enabled) R.string.routingRuleStatusOn else R.string.routingRuleStatusOff
    )
    val transport = textResource(rule.transport.titleRes())
    val matchType = textResource(rule.matchType.titleRes())
    val outboundTag = textResource(rule.outboundTag.titleRes())
    return textResource(
        R.string.routingRuleSummary,
        matchType,
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

private fun CoreRoutingMatchType.titleRes(): Int {
    return when (this) {
        CoreRoutingMatchType.Domain -> R.string.routingMatchTypeDomain
        CoreRoutingMatchType.Ip -> R.string.routingMatchTypeIp
        CoreRoutingMatchType.Port -> R.string.routingMatchTypePort
        CoreRoutingMatchType.SourcePort -> R.string.routingMatchTypeSourcePort
        CoreRoutingMatchType.Protocol -> R.string.routingMatchTypeProtocol
    }
}

private fun CoreRoutingTransport.titleRes(): Int {
    return when (this) {
        CoreRoutingTransport.Any -> R.string.routingTransportAny
        CoreRoutingTransport.Tcp -> R.string.routingTransportTcp
        CoreRoutingTransport.Udp -> R.string.routingTransportUdp
    }
}
