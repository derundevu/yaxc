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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.github.derundevu.yaxc.R
import io.github.derundevu.yaxc.presentation.designsystem.YaxcTheme
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcCard
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcJsonEditorSurface
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcScaffold
import com.blacksquircle.ui.editorkit.widget.TextProcessor

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
    onBack: () -> Unit,
    onSave: () -> Unit,
    onNameChange: (String) -> Unit,
    onSourceChange: (Long?) -> Unit,
    onEditorReady: (TextProcessor) -> Unit,
) {
    val spacing = YaxcTheme.spacing
    var sourcePickerOpen by remember { mutableStateOf(false) }
    var helpOpen by remember { mutableStateOf(false) }
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
                    IconButton(onClick = { helpOpen = true }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.HelpOutline,
                            contentDescription = null,
                        )
                    }
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
            YaxcCard {
                SourceField(
                    value = selectedSourceName,
                    onClick = { sourcePickerOpen = true },
                )
            }

            YaxcCard {
                OutlinedTextField(
                    value = name,
                    onValueChange = onNameChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(text = textResource(R.string.profileName)) },
                    singleLine = true,
                )
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
                            text = textResource(R.string.loadingProfile),
                            style = MaterialTheme.typography.bodyMedium,
                            color = YaxcTheme.extendedColors.textMuted,
                        )
                    } else {
                        YaxcJsonEditorSurface(
                            onEditorReady = onEditorReady,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                        )
                    }
                }
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
                Text(text = textResource(R.string.profileHelpBody))
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
