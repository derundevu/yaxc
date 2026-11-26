package io.github.derundevu.yaxc.presentation.profile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.derundevu.yaxc.R
import io.github.derundevu.yaxc.presentation.designsystem.YaxcTheme
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcJsonEditor
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcScaffold
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcTabOption
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcTabRow
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcTopAppBar
import io.github.derundevu.yaxc.presentation.editor.JsonEditorState

data class ProfileSourceOption(
    val linkId: Long?,
    val name: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    title: String,
    editorMode: ProfileEditorMode = ProfileEditorMode.Visual,
    name: String,
    selectedSourceId: Long?,
    availableSources: List<ProfileSourceOption>,
    isLoading: Boolean,
    isSaving: Boolean,
    editorState: JsonEditorState,
    visualConfig: ProfileVisualConfig = ProfileVisualConfig(),
    onBack: () -> Unit,
    onSave: () -> Unit,
    onEditorModeChange: (ProfileEditorMode) -> Unit = {},
    onNameChange: (String) -> Unit,
    onSourceChange: (Long?) -> Unit,
    onVisualConfigChange: ((ProfileVisualConfig) -> ProfileVisualConfig) -> Unit = {},
) {
    val spacing = YaxcTheme.spacing
    var sourcePickerOpen by rememberSaveable { mutableStateOf(false) }
    var detailsOpen by rememberSaveable { mutableStateOf(false) }
    var helpOpen by rememberSaveable { mutableStateOf(false) }
    val selectedSourceName = availableSources.firstOrNull { it.linkId == selectedSourceId }?.name
        ?: availableSources.firstOrNull()?.name.orEmpty()

    YaxcScaffold(
        topBar = {
            YaxcTopAppBar(
                title = title,
                onBack = onBack,
                actions = {
                    IconButton(onClick = { detailsOpen = true }, enabled = !isLoading) {
                        Icon(
                            imageVector = Icons.Outlined.Tune,
                            contentDescription = textResource(R.string.profileDetails),
                        )
                    }
                    IconButton(onClick = { helpOpen = true }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.HelpOutline,
                            contentDescription = textResource(R.string.profileHelpTitle),
                        )
                    }
                    IconButton(
                        onClick = onSave,
                        enabled = !isLoading && !isSaving,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Done,
                            contentDescription = textResource(R.string.save),
                        )
                    }
                },
            )
        },
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.md)
                    .padding(top = spacing.sm, bottom = spacing.xs),
            ) {
                YaxcTabRow(
                    options = ProfileEditorMode.entries.map { mode ->
                        YaxcTabOption(
                            value = mode,
                            label = when (mode) {
                                ProfileEditorMode.Visual -> textResource(R.string.routingModeVisual)
                                ProfileEditorMode.Json -> textResource(R.string.routingModeJson)
                            },
                            testTag = "profile-editor:mode:${mode.name}",
                        )
                    },
                    selected = editorMode,
                    onSelect = onEditorModeChange,
                    enabled = !isLoading && !isSaving,
                )
            }

            if (isLoading) {
                Text(
                    text = textResource(R.string.loadingProfile),
                    style = MaterialTheme.typography.bodyMedium,
                    color = YaxcTheme.extendedColors.textMuted,
                    modifier = Modifier.padding(spacing.md),
                )
            } else if (editorMode == ProfileEditorMode.Json) {
                YaxcJsonEditor(
                    state = editorState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
            } else {
                ProfileVisualEditor(
                    name = name,
                    selectedSourceName = selectedSourceName,
                    visualConfig = visualConfig,
                    enabled = !isSaving,
                    onNameChange = onNameChange,
                    onOpenSourcePicker = { sourcePickerOpen = true },
                    onVisualConfigChange = onVisualConfigChange,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    if (detailsOpen) {
        ModalBottomSheet(onDismissRequest = { detailsOpen = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = spacing.md)
                    .padding(bottom = spacing.xl),
                verticalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
                Text(
                    text = textResource(R.string.profileDetails),
                    style = MaterialTheme.typography.titleLarge,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = onNameChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(text = textResource(R.string.profileName)) },
                    singleLine = true,
                )
                SourceField(
                    value = selectedSourceName,
                    onClick = { sourcePickerOpen = true },
                )
            }
        }
    }

    if (sourcePickerOpen) {
        AlertDialog(
            onDismissRequest = { sourcePickerOpen = false },
            title = {
                Text(text = textResource(R.string.profileSourceDialogTitle))
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    availableSources.forEach { source ->
                        ProfileDialogOption(
                            title = source.name,
                            selected = source.linkId == selectedSourceId,
                            onClick = {
                                onSourceChange(source.linkId)
                                sourcePickerOpen = false
                            },
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { sourcePickerOpen = false }) {
                    Text(text = textResource(R.string.close))
                }
            },
        )
    }

    if (helpOpen) {
        AlertDialog(
            onDismissRequest = { helpOpen = false },
            title = {
                Text(text = textResource(R.string.profileHelpTitle))
            },
            text = {
                Text(
                    text = textResource(R.string.profileHelpBody),
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(onClick = { helpOpen = false }) {
                    Text(text = textResource(R.string.close))
                }
            },
        )
    }
}

@Composable
private fun textResource(id: Int): String {
    return androidx.compose.ui.res.stringResource(id)
}

@Composable
private fun SourceField(
    value: String,
    onClick: () -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = {},
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        label = { Text(text = textResource(R.string.profileSource)) },
        supportingText = { Text(text = textResource(R.string.profileSourceHint)) },
        readOnly = true,
        singleLine = true,
        trailingIcon = {
            Icon(
                imageVector = Icons.Outlined.KeyboardArrowDown,
                contentDescription = null,
            )
        },
    )
}
