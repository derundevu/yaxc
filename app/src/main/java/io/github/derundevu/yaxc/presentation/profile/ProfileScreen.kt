package io.github.derundevu.yaxc.presentation.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.derundevu.yaxc.R
import io.github.derundevu.yaxc.presentation.designsystem.YaxcTheme
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcCard
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcJsonEditor
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcScaffold
import com.blacksquircle.ui.editorkit.widget.TextProcessor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    title: String,
    name: String,
    isLoading: Boolean,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onNameChange: (String) -> Unit,
    onEditorReady: (TextProcessor) -> Unit,
) {
    val spacing = YaxcTheme.spacing
    val context = LocalContext.current

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
                text = textResource(R.string.profileScreenLead),
                style = MaterialTheme.typography.bodyLarge,
                color = YaxcTheme.extendedColors.textMuted,
            )

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
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .clip(RoundedCornerShape(20.dp))
                                .background(
                                    color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.16f),
                                    shape = RoundedCornerShape(20.dp),
                                ),
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(10.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(
                                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.52f),
                                        shape = RoundedCornerShape(16.dp),
                                    )
                                    .border(
                                        width = 1.dp,
                                        color = YaxcTheme.extendedColors.cardBorder.copy(alpha = 0.7f),
                                        shape = RoundedCornerShape(16.dp),
                                    ),
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
        }
    }
}

@Composable
private fun textResource(id: Int): String {
    return androidx.compose.ui.res.stringResource(id)
}
