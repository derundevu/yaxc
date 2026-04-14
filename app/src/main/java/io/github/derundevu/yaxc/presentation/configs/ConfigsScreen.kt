package io.github.derundevu.yaxc.presentation.configs

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.blacksquircle.ui.editorkit.widget.TextProcessor
import io.github.derundevu.yaxc.R
import io.github.derundevu.yaxc.database.Config
import io.github.derundevu.yaxc.presentation.designsystem.YaxcTheme
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcCard
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcScaffold

enum class ConfigSection(
    val title: String,
    val isArray: Boolean,
) {
    Log("log", false),
    Dns("dns", false),
    Inbounds("inbounds", true),
    Outbounds("outbounds", true),
    Routing("routing", false),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigsScreen(
    selectedSection: ConfigSection,
    currentMode: Config.Mode,
    currentConfigText: String,
    isLoading: Boolean,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onSectionSelected: (ConfigSection) -> Unit,
    onModeSelected: (Config.Mode) -> Unit,
    onEditorReady: (TextProcessor) -> Unit,
) {
    val spacing = YaxcTheme.spacing
    val context = LocalContext.current

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
                            text = { Text(text = section.title) },
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
                            text = { Text(text = mode.name) },
                        )
                    }
                }
            }

            YaxcCard(
                modifier = Modifier.weight(1f),
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
                    JsonEditor(
                        context = context,
                        configText = currentConfigText,
                        onEditorReady = onEditorReady,
                    )
                }
            }
        }
    }
}

@Composable
private fun JsonEditor(
    context: Context,
    configText: String,
    onEditorReady: (TextProcessor) -> Unit,
) {
    AndroidView(
        factory = {
            TextProcessor(context).also {
                it.setTextContent(configText)
                onEditorReady(it)
            }
        },
        update = {
            if (it.text.toString() != configText && it.text.isEmpty()) {
                it.setTextContent(configText)
            }
            onEditorReady(it)
        },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 320.dp),
    )
}

@Composable
private fun textResource(id: Int): String {
    return androidx.compose.ui.res.stringResource(id)
}
