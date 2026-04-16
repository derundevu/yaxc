package io.github.derundevu.yaxc.presentation.routing

import android.widget.ImageView
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
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.blacksquircle.ui.editorkit.widget.TextProcessor
import io.github.derundevu.yaxc.R
import io.github.derundevu.yaxc.dto.AppList
import io.github.derundevu.yaxc.helper.CoreRoutingEditorMode
import io.github.derundevu.yaxc.helper.CoreRoutingRule
import io.github.derundevu.yaxc.helper.CoreRoutingTransport
import io.github.derundevu.yaxc.presentation.designsystem.YaxcTheme
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcCard
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcJsonEditor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutingScreen(
    apps: List<AppList>,
    isAppsLoading: Boolean,
    selectedPackages: Set<String>,
    appsRoutingMode: Boolean,
    coreEditorMode: CoreRoutingEditorMode,
    coreDomainStrategy: String,
    coreRules: List<CoreRoutingRule>,
    coreUnsupportedRuleCount: Int,
    isCoreLoading: Boolean,
    onBack: () -> Unit,
    onModeChange: (Boolean) -> Unit,
    onTogglePackage: (String) -> Unit,
    onSave: () -> Unit,
    onCoreEditorModeChange: (CoreRoutingEditorMode) -> Unit,
    onCoreDomainStrategyChange: (String) -> Unit,
    onCoreRuleChange: (CoreRoutingRule) -> Unit,
    onAddCoreRule: () -> Unit,
    onDeleteCoreRule: (String) -> Unit,
    onCoreEditorReady: (TextProcessor) -> Unit,
) {
    val spacing = YaxcTheme.spacing
    var query by rememberSaveable { mutableStateOf("") }
    var routingModeHelpVisible by rememberSaveable { mutableStateOf(false) }
    val density = LocalDensity.current
    val navigationBarBottom = WindowInsets.navigationBars.getBottom(density)
    val gestureNavigation = navigationBarBottom <= with(density) { 32.dp.roundToPx() }
    val reservedBottomInset = if (gestureNavigation) 0.dp else with(density) { navigationBarBottom.toDp() }

    val filteredApps = remember(apps, query) {
        val keyword = query.trim().lowercase()
        if (keyword.isBlank()) apps
        else apps.filter {
            it.appName.lowercase().contains(keyword) || it.packageName.lowercase().contains(keyword)
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets.safeDrawing.only(
            WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
        ),
        topBar = {
            TopAppBar(
                title = { Text(text = textResource(R.string.routing)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = null,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onSave) {
                        Icon(
                            imageVector = Icons.Outlined.Done,
                            contentDescription = null,
                        )
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
                SectionHeader(
                    title = textResource(R.string.appsRouting),
                    description = textResource(R.string.appsRoutingLead),
                )
            }

            item {
                AppsRoutingControls(
                    query = query,
                    selectedPackages = selectedPackages,
                    appsRoutingMode = appsRoutingMode,
                    onQueryChange = { query = it },
                    onModeChange = onModeChange,
                    onHelpClick = { routingModeHelpVisible = true },
                )
            }

            when {
                isAppsLoading -> item {
                    LoadingCard()
                }

                filteredApps.isEmpty() -> item {
                    EmptyStateCard(
                        icon = Icons.Outlined.Tune,
                        text = textResource(R.string.noAppsFound),
                    )
                }

                else -> items(
                    items = filteredApps,
                    key = { it.packageName },
                ) { app ->
                    AppRoutingRow(
                        app = app,
                        isSelected = selectedPackages.contains(app.packageName),
                        onClick = { onTogglePackage(app.packageName) },
                    )
                }
            }

            item {
                SectionHeader(
                    title = textResource(R.string.coreRouting),
                    description = textResource(R.string.coreRoutingLead),
                )
            }

            item {
                CoreRoutingControls(
                    editorMode = coreEditorMode,
                    domainStrategy = coreDomainStrategy,
                    unsupportedRuleCount = coreUnsupportedRuleCount,
                    onEditorModeChange = onCoreEditorModeChange,
                    onDomainStrategyChange = onCoreDomainStrategyChange,
                    onAddRule = onAddCoreRule,
                )
            }

            when {
                isCoreLoading -> item {
                    LoadingCard(label = textResource(R.string.loadingConfig))
                }

                coreEditorMode == CoreRoutingEditorMode.Json -> item {
                    CoreRoutingJsonCard(onEditorReady = onCoreEditorReady)
                }

                coreRules.isEmpty() -> item {
                    EmptyStateCard(
                        icon = Icons.Outlined.Route,
                        text = textResource(R.string.coreRoutingNoRules),
                    )
                }

                else -> items(
                    items = coreRules,
                    key = { it.id },
                ) { rule ->
                    CoreRoutingRuleCard(
                        rule = rule,
                        onRuleChange = onCoreRuleChange,
                        onDelete = { onDeleteCoreRule(rule.id) },
                    )
                }
            }
        }

        if (routingModeHelpVisible) {
            AlertDialog(
                onDismissRequest = { routingModeHelpVisible = false },
                title = {
                    Text(text = textResource(R.string.appsRoutingModeHelpTitle))
                },
                text = {
                    Text(
                        text = textResource(R.string.appsRoutingModeHelpBody),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                confirmButton = {
                    Text(
                        text = textResource(R.string.ok),
                        modifier = Modifier
                            .clickable { routingModeHelpVisible = false }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge,
                    )
                },
            )
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    description: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodyLarge,
            color = YaxcTheme.extendedColors.textMuted,
        )
    }
}

@Composable
private fun AppsRoutingControls(
    query: String,
    selectedPackages: Set<String>,
    appsRoutingMode: Boolean,
    onQueryChange: (String) -> Unit,
    onModeChange: (Boolean) -> Unit,
    onHelpClick: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(YaxcTheme.spacing.md)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            leadingIcon = {
                Icon(
                    imageVector = Icons.Outlined.Search,
                    contentDescription = null,
                )
            },
            label = { Text(text = textResource(R.string.search)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            singleLine = true,
        )

        YaxcCard {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = textResource(R.string.appsRoutingModeTitle),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onHelpClick) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.HelpOutline,
                        contentDescription = textResource(R.string.appsRoutingModeHelp),
                        tint = YaxcTheme.extendedColors.textMuted,
                    )
                }
            }

            TabRow(selectedTabIndex = if (appsRoutingMode) 0 else 1) {
                Tab(
                    selected = appsRoutingMode,
                    onClick = { onModeChange(true) },
                    text = { Text(text = textResource(R.string.appsRoutingModeExcludeShort)) },
                )
                Tab(
                    selected = !appsRoutingMode,
                    onClick = { onModeChange(false) },
                    text = { Text(text = textResource(R.string.appsRoutingModeIncludeShort)) },
                )
            }

            Text(
                text = if (appsRoutingMode) {
                    textResource(R.string.appsRoutingExcludeMode)
                } else {
                    textResource(R.string.appsRoutingIncludeMode)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = YaxcTheme.extendedColors.textMuted,
                modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
            )
        }

        Text(
            text = textResource(R.string.appsRoutingSelectedCount, selectedPackages.size),
            style = MaterialTheme.typography.bodyMedium,
            color = YaxcTheme.extendedColors.textMuted,
        )
    }
}

@Composable
private fun CoreRoutingControls(
    editorMode: CoreRoutingEditorMode,
    domainStrategy: String,
    unsupportedRuleCount: Int,
    onEditorModeChange: (CoreRoutingEditorMode) -> Unit,
    onDomainStrategyChange: (String) -> Unit,
    onAddRule: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(YaxcTheme.spacing.md)) {
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

        if (unsupportedRuleCount > 0) {
            YaxcCard {
                Text(
                    text = textResource(R.string.coreRoutingUnsupportedRules, unsupportedRuleCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = YaxcTheme.extendedColors.textMuted,
                )
            }
        }

        if (editorMode == CoreRoutingEditorMode.Visual) {
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
    onRuleChange: (CoreRoutingRule) -> Unit,
    onDelete: () -> Unit,
) {
    YaxcCard(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = rule.name,
                    onValueChange = { onRuleChange(rule.copy(name = it)) },
                    modifier = Modifier.weight(1f),
                    label = { Text(text = textResource(R.string.routingRuleName)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                )
                ActionBubble(
                    icon = Icons.Outlined.DeleteOutline,
                    onClick = onDelete,
                )
            }

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
            TabRow(selectedTabIndex = CoreRoutingTransport.entries.indexOf(rule.network)) {
                CoreRoutingTransport.entries.forEach { transport ->
                    Tab(
                        selected = transport == rule.network,
                        onClick = { onRuleChange(rule.copy(network = transport)) },
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
private fun AppRoutingRow(
    app: AppList,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        shape = MaterialTheme.shapes.large,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = BorderStroke(1.dp, YaxcTheme.extendedColors.cardBorder),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            AndroidView(
                factory = {
                    ImageView(it)
                },
                update = { it.setImageDrawable(app.appIcon) },
                modifier = Modifier.size(48.dp),
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.appName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = YaxcTheme.extendedColors.textMuted,
                )
            }

            Checkbox(
                checked = isSelected,
                onCheckedChange = { onClick() },
            )
        }
    }
}

@Composable
private fun ActionBubble(
    icon: ImageVector,
    onClick: () -> Unit,
    containerSize: androidx.compose.ui.unit.Dp = 40.dp,
    iconSize: androidx.compose.ui.unit.Dp = 20.dp,
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
    label: String = textResource(R.string.loading),
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
