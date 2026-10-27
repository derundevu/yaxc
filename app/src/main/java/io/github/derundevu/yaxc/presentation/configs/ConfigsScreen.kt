package io.github.derundevu.yaxc.presentation.configs

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.github.derundevu.yaxc.R
import io.github.derundevu.yaxc.database.Config
import io.github.derundevu.yaxc.presentation.designsystem.YaxcTheme
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcJsonEditor
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcLiquidDropdownMenu
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcLiquidDropdownMenuItem
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcScaffold
import io.github.derundevu.yaxc.presentation.editor.JsonEditorState

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
    isSaving: Boolean,
    editorState: JsonEditorState,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onSectionSelected: (ConfigSection) -> Unit,
    onModeSelected: (Config.Mode) -> Unit,
) {
    var modeMenuOpen by remember { mutableStateOf(false) }
    YaxcScaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = textResource(R.string.configs)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    Box {
                        TextButton(onClick = { modeMenuOpen = true }, enabled = !isLoading) {
                            Text(text = textResource(currentMode.titleRes()))
                        }
                        YaxcLiquidDropdownMenu(
                            expanded = modeMenuOpen,
                            onDismissRequest = { modeMenuOpen = false },
                        ) {
                            Config.Mode.entries.forEach { mode ->
                                YaxcLiquidDropdownMenuItem(
                                    text = textResource(mode.titleRes()),
                                    onClick = {
                                        modeMenuOpen = false
                                        onModeSelected(mode)
                                    },
                                )
                            }
                        }
                    }
                    IconButton(onClick = onSave, enabled = !isLoading && !isSaving) {
                        Icon(Icons.Outlined.Done, contentDescription = textResource(R.string.save))
                    }
                },
            )
        },
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            SecondaryScrollableTabRow(
                selectedTabIndex = ConfigSection.entries.indexOf(selectedSection),
                modifier = Modifier.fillMaxWidth(),
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
            if (isLoading) {
                Text(
                    text = textResource(R.string.loadingConfig),
                    style = MaterialTheme.typography.bodyMedium,
                    color = YaxcTheme.extendedColors.textMuted,
                    modifier = Modifier.padding(YaxcTheme.spacing.md),
                )
            } else {
                YaxcJsonEditor(
                    state = editorState,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            }
        }
    }
}

@Composable
private fun textResource(id: Int): String = androidx.compose.ui.res.stringResource(id)

private fun Config.Mode.titleRes(): Int = when (this) {
    Config.Mode.Disable -> R.string.configModeDisable
    Config.Mode.Replace -> R.string.configModeReplace
    Config.Mode.Merge -> R.string.configModeMerge
}
