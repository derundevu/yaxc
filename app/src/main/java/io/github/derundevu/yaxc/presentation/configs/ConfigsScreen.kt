package io.github.derundevu.yaxc.presentation.configs

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import io.github.derundevu.yaxc.R
import io.github.derundevu.yaxc.database.Config
import io.github.derundevu.yaxc.presentation.designsystem.YaxcTheme
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcJsonEditor
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcLiquidDropdownMenu
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcLiquidDropdownMenuItem
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcScaffold
import io.github.derundevu.yaxc.presentation.editor.JsonEditorState
import kotlinx.coroutines.CancellationException

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

internal data class DnsPreview(val connection: String, val json: String, val noUpstreams: Boolean)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConfigsScreen(
    selectedSection: ConfigSection,
    currentMode: Config.Mode,
    isLoading: Boolean,
    isSaving: Boolean,
    editorState: JsonEditorState,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onSectionSelected: (ConfigSection) -> Unit,
    onModeSelected: (Config.Mode) -> Unit,
    loadDnsPreview: suspend () -> DnsPreview?,
) {
    var modeMenuOpen by remember { mutableStateOf(false) }
    var dnsPreviewOpen by remember { mutableStateOf(false) }
    if (dnsPreviewOpen) {
        EffectiveDnsDialog(loadDnsPreview, onDismiss = { dnsPreviewOpen = false })
    }
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
                if (selectedSection == ConfigSection.Dns) {
                    TextButton(
                        onClick = { dnsPreviewOpen = true },
                        enabled = !isSaving,
                    ) {
                        Text(textResource(R.string.configDnsPreview))
                    }
                }
                YaxcJsonEditor(
                    state = editorState,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            }
        }
    }
}

private sealed interface DnsPreviewState {
    data object Loading : DnsPreviewState
    data object Unavailable : DnsPreviewState
    data object Failed : DnsPreviewState
    data class Ready(val preview: DnsPreview) : DnsPreviewState
}

@Composable
private fun EffectiveDnsDialog(loadPreview: suspend () -> DnsPreview?, onDismiss: () -> Unit) {
    val result by produceState<DnsPreviewState>(DnsPreviewState.Loading) {
        value = try {
            loadPreview()?.let(DnsPreviewState::Ready) ?: DnsPreviewState.Unavailable
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DnsPreviewState.Failed
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(textResource(R.string.configDnsPreview)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                when (val state = result) {
                    DnsPreviewState.Loading -> Text(textResource(R.string.loadingConfig))
                    DnsPreviewState.Failed -> Text(textResource(R.string.configDnsPreviewFailed))
                    DnsPreviewState.Unavailable -> Text(textResource(R.string.configDnsPreviewNoSelection))
                    is DnsPreviewState.Ready -> {
                        val preview = state.preview
                        Text(
                            stringResource(R.string.configDnsPreviewConnection, preview.connection),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            textResource(R.string.configDnsPreviewDescription),
                            modifier = Modifier.padding(vertical = YaxcTheme.spacing.sm),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (preview.noUpstreams) Text(textResource(R.string.configDnsPreviewSystemDefault))
                        SelectionContainer {
                            Text(preview.json, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(textResource(R.string.close)) } },
    )
}

@Composable
private fun textResource(id: Int): String = stringResource(id)

private fun Config.Mode.titleRes(): Int = when (this) {
    Config.Mode.Disable -> R.string.configModeDisable
    Config.Mode.Replace -> R.string.configModeReplace
    Config.Mode.Merge -> R.string.configModeMerge
}
