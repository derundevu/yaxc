package io.github.derundevu.yaxc.presentation.routing

import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import io.github.derundevu.yaxc.R
import io.github.derundevu.yaxc.Settings.AppsRoutingMode
import io.github.derundevu.yaxc.dto.AppList
import io.github.derundevu.yaxc.presentation.designsystem.YaxcTheme
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcCard

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
                title = { Text(text = textResource(R.string.appsRouting)) },
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(YaxcTheme.backgroundBrush)
                .padding(innerPadding)
                .padding(horizontal = spacing.md)
                .padding(top = spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
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

            YaxcCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = textResource(R.string.appsRoutingModeTitle),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { routingModeHelpVisible = true }) {
                        Icon(
                            imageVector = Icons.Outlined.HelpOutline,
                            contentDescription = textResource(R.string.appsRoutingModeHelp),
                            tint = YaxcTheme.extendedColors.textMuted,
                        )
                    }
                }

                TabRow(selectedTabIndex = appsRoutingMode.tabIndex()) {
                    AppsRoutingMode.entries.forEach { mode ->
                        Tab(
                            selected = appsRoutingMode == mode,
                            onClick = { onModeChange(mode) },
                            text = { Text(text = textResource(mode.shortTitleRes())) },
                        )
                    }
                }

                Text(
                    text = textResource(appsRoutingMode.descriptionRes()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = YaxcTheme.extendedColors.textMuted,
                    modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
                )
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

            Text(
                text = textResource(R.string.appsRoutingSelectedCount, selectedPackages.size),
                style = MaterialTheme.typography.bodyMedium,
                color = YaxcTheme.extendedColors.textMuted,
            )

            when {
                isLoading -> {
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = textResource(R.string.loading),
                            style = MaterialTheme.typography.bodyLarge,
                            color = YaxcTheme.extendedColors.textMuted,
                        )
                    }
                }

                filteredApps.isEmpty() -> {
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.TopStart,
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
                                    imageVector = Icons.Outlined.Tune,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    text = textResource(R.string.noAppsFound),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(bottom = spacing.xl),
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

private fun AppsRoutingMode.descriptionRes(): Int = when (this) {
    AppsRoutingMode.Disabled -> R.string.appsRoutingDisabledMode
    AppsRoutingMode.Exclude -> R.string.appsRoutingExcludeMode
    AppsRoutingMode.Include -> R.string.appsRoutingIncludeMode
}
