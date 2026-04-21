package io.github.derundevu.yaxc.presentation.configs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.annotation.StringRes
import io.github.derundevu.yaxc.R
import io.github.derundevu.yaxc.database.Config
import io.github.derundevu.yaxc.presentation.designsystem.YaxcTheme
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcCard
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcJsonEditorSurface
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcScaffold
import com.blacksquircle.ui.editorkit.widget.TextProcessor

enum class ConfigSection(
    @StringRes val titleRes: Int,
    val isArray: Boolean,
) {
    Log(R.string.configSectionLog, false),
    Dns(R.string.configSectionDns, false),
    Inbounds(R.string.configSectionInbounds, true),
    Outbounds(R.string.configSectionOutbounds, true),
    Routing(R.string.configSectionRouting, false),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigsScreen(
    selectedSection: ConfigSection,
    currentMode: Config.Mode,
    isLoading: Boolean,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onSectionSelected: (ConfigSection) -> Unit,
    onModeSelected: (Config.Mode) -> Unit,
    onEditorReady: (TextProcessor) -> Unit,
) {
    val spacing = YaxcTheme.spacing

    YaxcScaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = textResource(R.string.configs)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = null,
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = onSave,
                        enabled = !isLoading,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Done,
                            contentDescription = null,
                        )
                    }
                },
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = spacing.md)
                .padding(top = spacing.md, bottom = spacing.xl),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Text(
                text = textResource(R.string.configsScreenLead),
                style = MaterialTheme.typography.bodyLarge,
                color = YaxcTheme.extendedColors.textMuted,
            )

            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                shape = MaterialTheme.shapes.large,
            ) {
                SecondaryScrollableTabRow(
                    selectedTabIndex = ConfigSection.entries.indexOf(selectedSection),
                    containerColor = androidx.compose.ui.graphics.Color.Transparent,
                ) {
                    ConfigSection.entries.forEach { section ->
                        Tab(
                            selected = section == selectedSection,
                            onClick = { onSectionSelected(section) },
                            text = { Text(text = textResource(section.titleRes)) },
                        )
                    }
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                shape = MaterialTheme.shapes.large,
            ) {
                SecondaryScrollableTabRow(
                    selectedTabIndex = Config.Mode.entries.indexOf(currentMode),
                    containerColor = androidx.compose.ui.graphics.Color.Transparent,
                ) {
                    Config.Mode.entries.forEach { mode ->
                        Tab(
                            selected = mode == currentMode,
                            onClick = { onModeSelected(mode) },
                            text = { Text(text = textResource(mode.titleRes())) },
                        )
                    }
                }
            }

            YaxcCard(
                modifier = Modifier.weight(1f),
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Text(
                        text = textResource(R.string.profileConfig),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )

                    if (isLoading) {
                        Text(
                            text = textResource(R.string.loadingConfig),
                            style = MaterialTheme.typography.bodyMedium,
                            color = YaxcTheme.extendedColors.textMuted,
                        )
                    } else {
                        YaxcJsonEditorSurface(
                            onEditorReady = onEditorReady,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun textResource(id: Int): String {
    return androidx.compose.ui.res.stringResource(id)
}

private fun Config.Mode.titleRes(): Int {
    return when (this) {
        Config.Mode.Disable -> R.string.configModeDisable
        Config.Mode.Replace -> R.string.configModeReplace
        Config.Mode.Merge -> R.string.configModeMerge
    }
}
