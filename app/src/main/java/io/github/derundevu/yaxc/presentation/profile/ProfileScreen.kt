package io.github.derundevu.yaxc.presentation.profile

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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.blacksquircle.ui.editorkit.widget.TextProcessor
import io.github.derundevu.yaxc.R
import io.github.derundevu.yaxc.presentation.designsystem.YaxcTheme
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcCard
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcScaffold

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    title: String,
    name: String,
    configText: String,
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
                    JsonEditor(
                        context = context,
                        configText = configText,
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
