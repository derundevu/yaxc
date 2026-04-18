package io.github.derundevu.yaxc.presentation.root

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.derundevu.yaxc.R
import io.github.derundevu.yaxc.helper.AppUpdateUiState
import io.github.derundevu.yaxc.presentation.designsystem.YaxcTheme

@Composable
fun AppUpdatePanel(
    state: AppUpdateUiState,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val statusText = when {
        state.isChecking -> textResource(R.string.appUpdateChecking)
        state.isReadyToInstall -> textResource(
            R.string.appUpdateReadyToInstall,
            state.pendingVersion.orEmpty(),
        )
        state.isDownloading -> textResource(
            R.string.appUpdateDownloading,
            state.pendingVersion.orEmpty(),
        )
        state.availableRelease != null -> textResource(
            R.string.appUpdateAvailable,
            state.availableRelease.versionName,
        )
        state.errorMessage != null -> textResource(R.string.appUpdateCheckFailed)
        state.isUpToDate -> textResource(R.string.appUpdateUpToDate)
        else -> null
    } ?: return

    Text(
        text = statusText,
        style = MaterialTheme.typography.bodySmall,
        color = when {
            state.isReadyToInstall || state.availableRelease != null -> YaxcTheme.extendedColors.success
            state.errorMessage != null -> YaxcTheme.extendedColors.danger
            else -> YaxcTheme.extendedColors.textMuted
        },
        textAlign = TextAlign.Center,
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
    )

    when {
        state.isChecking -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.padding(2.dp),
                )
            }
        }

        state.isReadyToInstall -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalButton(onClick = onInstall) {
                    Text(text = textResource(R.string.appUpdateInstall))
                }
            }
        }

        state.availableRelease != null && !state.isDownloading -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalButton(onClick = onDownload) {
                    Text(text = textResource(R.string.appUpdateDownload))
                }
            }
        }
    }
}

@Composable
private fun textResource(id: Int): String = stringResource(id)

@Composable
private fun textResource(id: Int, arg0: String): String = stringResource(id, arg0)
