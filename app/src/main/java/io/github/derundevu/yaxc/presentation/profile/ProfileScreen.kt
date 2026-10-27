package io.github.derundevu.yaxc.presentation.profile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.github.derundevu.yaxc.R
import io.github.derundevu.yaxc.presentation.designsystem.YaxcTheme
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcJsonEditor
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcScaffold
import io.github.derundevu.yaxc.presentation.editor.JsonEditorState

data class ProfileSourceOption(
    val linkId: Long?,
    val name: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    title: String,
    name: String,
    selectedSourceId: Long?,
    availableSources: List<ProfileSourceOption>,
    isLoading: Boolean,
    isSaving: Boolean,
    editorState: JsonEditorState,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onNameChange: (String) -> Unit,
    onSourceChange: (Long?) -> Unit,
) {
    val spacing = YaxcTheme.spacing
    var sourcePickerOpen by rememberSaveable { mutableStateOf(false) }
    var detailsOpen by rememberSaveable { mutableStateOf(false) }
    var helpOpen by rememberSaveable { mutableStateOf(false) }
    val selectedSourceName = availableSources.firstOrNull { it.linkId == selectedSourceId }?.name
        ?: availableSources.firstOrNull()?.name.orEmpty()

    YaxcScaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = null,
                        )
                    }
                },
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
        if (isLoading) {
            Text(
                text = textResource(R.string.loadingProfile),
                style = MaterialTheme.typography.bodyMedium,
                color = YaxcTheme.extendedColors.textMuted,
                modifier = Modifier.padding(spacing.md),
            )
        } else {
            YaxcJsonEditor(
                state = editorState,
                modifier = Modifier.fillMaxSize(),
            )
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
                        SourceOptionRow(
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

@Composable
private fun SourceOptionRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.76f)
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.18f)
                }
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
