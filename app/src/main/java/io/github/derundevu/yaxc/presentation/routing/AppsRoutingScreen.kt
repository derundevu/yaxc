package io.github.derundevu.yaxc.presentation.routing

import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import io.github.derundevu.yaxc.R
import io.github.derundevu.yaxc.Settings.AppsRoutingMode
import io.github.derundevu.yaxc.dto.AppList
import io.github.derundevu.yaxc.presentation.designsystem.YaxcTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsRoutingScreen(
    apps: List<AppList>,
    isLoading: Boolean,
    selectedPackages: Set<String>,
    appsRoutingMode: AppsRoutingMode,
    onBack: () -> Unit,
    onModeChange: (AppsRoutingMode) -> Unit,
    onTogglePackage: (String) -> Unit,
    onSave: () -> Unit,
) {
    val spacing = YaxcTheme.spacing
    var query by rememberSaveable { mutableStateOf("") }
    var routingModeHelpVisible by rememberSaveable { mutableStateOf(false) }
    var showSystemApps by rememberSaveable { mutableStateOf(true) }
    var controlsHeightPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current

    val filteredApps = remember(apps, query, showSystemApps) {
        val keyword = query.trim()
        if (showSystemApps && keyword.isEmpty()) apps else apps.filter { app ->
            (showSystemApps || !app.isSystem) &&
                (keyword.isEmpty() || app.appName.contains(keyword, ignoreCase = true) ||
                    app.packageName.contains(keyword, ignoreCase = true))
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets.safeDrawing.only(
            WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
        ),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = textResource(R.string.appsRouting),
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = textResource(R.string.appsRoutingSelectedCount, selectedPackages.size),
                            style = MaterialTheme.typography.labelSmall,
                            color = YaxcTheme.extendedColors.textMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = null,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { routingModeHelpVisible = true }) {
                        Icon(
                            imageVector = Icons.Outlined.HelpOutline,
                            contentDescription = textResource(R.string.appsRoutingModeHelp),
                            tint = YaxcTheme.extendedColors.textMuted,
                        )
                    }
                    IconButton(onClick = onSave) {
                        Icon(
                            imageVector = Icons.Outlined.Done,
                            contentDescription = textResource(R.string.save),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(YaxcTheme.backgroundBrush)
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = spacing.md),
        ) {
            val listAreaHeight = (maxHeight - with(density) { controlsHeightPx.toDp() } - spacing.sm * 2)
                .coerceAtLeast(96.dp)

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = spacing.sm),
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .onSizeChanged { controlsHeightPx = it.height },
                    verticalArrangement = Arrangement.spacedBy(spacing.xs),
                ) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
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

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                        shape = MaterialTheme.shapes.large,
                    ) {
                        TabRow(selectedTabIndex = appsRoutingMode.tabIndex()) {
                            AppsRoutingMode.entries.forEach { mode ->
                                Tab(
                                    selected = appsRoutingMode == mode,
                                    onClick = { onModeChange(mode) },
                                    text = { Text(text = textResource(mode.shortTitleRes())) },
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = showSystemApps,
                                role = Role.Checkbox,
                                onValueChange = { showSystemApps = it },
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = showSystemApps, onCheckedChange = null)
                        Text(
                            text = textResource(R.string.appsRoutingShowSystemApps),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(start = spacing.sm, top = spacing.sm, bottom = spacing.sm),
                        )
                    }
                }

                when {
                    isLoading -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(listAreaHeight),
                            contentAlignment = Alignment.Center,
                        ) {
                            val loadingDescription = textResource(R.string.loading)
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .size(48.dp)
                                    .semantics { contentDescription = loadingDescription },
                            )
                        }
                    }

                    filteredApps.isEmpty() -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(listAreaHeight),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = textResource(R.string.noAppsFound),
                                style = MaterialTheme.typography.bodyLarge,
                                color = YaxcTheme.extendedColors.textMuted,
                            )
                        }
                    }

                    else -> {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(listAreaHeight),
                            verticalArrangement = Arrangement.spacedBy(spacing.sm),
                        ) {
                            items(
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
                    }
                }
            }
        }

        if (routingModeHelpVisible) {
            AlertDialog(
                onDismissRequest = { routingModeHelpVisible = false },
                title = { Text(text = textResource(R.string.appsRoutingModeHelpTitle)) },
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
        border = androidx.compose.foundation.BorderStroke(1.dp, YaxcTheme.extendedColors.cardBorder),
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
private fun textResource(id: Int): String {
    return androidx.compose.ui.res.stringResource(id)
}

@Composable
private fun textResource(id: Int, arg: Int): String {
    return androidx.compose.ui.res.stringResource(id, arg)
}

private fun AppsRoutingMode.tabIndex(): Int = when (this) {
    AppsRoutingMode.Disabled -> 0
    AppsRoutingMode.Exclude -> 1
    AppsRoutingMode.Include -> 2
}

private fun AppsRoutingMode.shortTitleRes(): Int = when (this) {
    AppsRoutingMode.Disabled -> R.string.appsRoutingModeDisabledShort
    AppsRoutingMode.Exclude -> R.string.appsRoutingModeExcludeShort
    AppsRoutingMode.Include -> R.string.appsRoutingModeIncludeShort
}

