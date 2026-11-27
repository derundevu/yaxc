package io.github.derundevu.yaxc.presentation.root

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.derundevu.yaxc.R
import io.github.derundevu.yaxc.helper.AppUpdateUiState
import io.github.derundevu.yaxc.presentation.designsystem.YaxcTheme
import io.github.derundevu.yaxc.presentation.designsystem.components.yaxcTvFocus
import java.text.NumberFormat

@Composable
fun AppUpdatePanel(
    state: AppUpdateUiState,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val statusText = when {
        state.isReadyToInstall -> stringResource(R.string.appUpdateReadyToInstall, state.pendingVersion.orEmpty())
        state.isDownloading -> stringResource(R.string.appUpdateDownloading, state.pendingVersion.orEmpty())
        state.isChecking -> stringResource(R.string.appUpdateChecking)
        state.errorMessage != null -> stringResource(R.string.appUpdateCheckFailed)
        state.availableRelease != null -> stringResource(R.string.appUpdateAvailable, state.availableRelease.versionName)
        state.isUpToDate -> stringResource(R.string.appUpdateUpToDate)
        else -> null
    } ?: return

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = statusText,
            style = MaterialTheme.typography.bodyMedium,
            color = when {
                state.isReadyToInstall -> YaxcTheme.extendedColors.success
                state.errorMessage != null && !state.isDownloading -> YaxcTheme.extendedColors.danger
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.testTag("updates:status"),
        )
        if (state.isDownloading) {
            DownloadProgress(state)
        }
        when {
            state.isReadyToInstall -> FilledTonalButton(
                onClick = onInstall,
                modifier = Modifier.fillMaxWidth().yaxcTvFocus(CircleShape).testTag("updates:install"),
            ) { Text(stringResource(R.string.appUpdateInstall)) }
            state.availableRelease != null && !state.isDownloading -> FilledTonalButton(
                onClick = onDownload,
                modifier = Modifier.fillMaxWidth().yaxcTvFocus(CircleShape).testTag("updates:download"),
            ) { Text(stringResource(R.string.appUpdateDownload)) }
        }
    }
}

@Composable
private fun DownloadProgress(state: AppUpdateUiState) {
    val context = LocalContext.current
    val total = state.totalBytes?.takeIf { it > 0L }
    val progress = total?.let { (state.downloadedBytes.toDouble() / it).coerceIn(0.0, 1.0).toFloat() }
    val progressModifier = Modifier.fillMaxWidth().height(6.dp).testTag("updates:progress")
    if (progress != null) {
        LinearProgressIndicator(
            progress = { progress },
            modifier = progressModifier,
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
            strokeCap = StrokeCap.Round,
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
    } else {
        LinearProgressIndicator(
            modifier = progressModifier,
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
            strokeCap = StrokeCap.Round,
        )
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        val downloadedLabel = Formatter.formatShortFileSize(context, state.downloadedBytes)
        Text(
            text = if (total != null) stringResource(
                R.string.appUpdateDownloadSize, downloadedLabel, Formatter.formatShortFileSize(context, total),
            ) else downloadedLabel,
            style = MaterialTheme.typography.bodySmall,
            color = YaxcTheme.extendedColors.textMuted,
            modifier = Modifier.weight(1f).testTag("updates:download-size"),
        )
        if (progress != null) {
            Text(
                text = NumberFormat.getPercentInstance(LocalConfiguration.current.locales[0]).format(progress),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag("updates:percent"),
            )
        }
    }
}

@Composable
internal fun AppUpdateCheckButton(
    state: AppUpdateUiState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.size(48.dp), contentAlignment = Alignment.Center) {
        if (state.isChecking) {
            CircularProgressIndicator(
                color = YaxcTheme.extendedColors.textMuted,
                strokeWidth = 2.dp,
                modifier = Modifier.size(20.dp),
            )
        } else {
            IconButton(
                onClick = onClick,
                enabled = !state.isDownloading,
                modifier = Modifier.yaxcTvFocus(CircleShape),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = stringResource(R.string.appUpdateCheck),
                    tint = YaxcTheme.extendedColors.textMuted,
                )
            }
        }
    }
}
