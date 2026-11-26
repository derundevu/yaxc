package io.github.derundevu.yaxc.presentation.root

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.derundevu.yaxc.R
import io.github.derundevu.yaxc.helper.AppUpdateUiState
import io.github.derundevu.yaxc.helper.InstalledChangelog
import io.github.derundevu.yaxc.presentation.designsystem.YaxcTheme
import io.github.derundevu.yaxc.presentation.designsystem.components.FeedbackKind
import io.github.derundevu.yaxc.presentation.designsystem.components.LocalYaxcFeedback
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcFeedbackState
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcGlassPanel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val APP_REPOSITORY_URL = "https://github.com/derundevu/yaxc"

@Composable
internal fun AppVersionsPanel(
    appVersion: String,
    xrayVersion: String,
    tun2socksVersion: String,
    updateState: AppUpdateUiState,
    onCheckUpdate: () -> Unit,
    onDownloadUpdate: () -> Unit,
    onInstallUpdate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val feedback = LocalYaxcFeedback.current
    var showChangelog by rememberSaveable { mutableStateOf(false) }
    var showLicenses by rememberSaveable { mutableStateOf(false) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        YaxcGlassPanel(modifier = Modifier.testTag("settings:versions")) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(R.string.appName),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.appInstalledVersion, appVersion),
                        style = MaterialTheme.typography.bodyMedium,
                        color = YaxcTheme.extendedColors.textMuted,
                    )
                }
                AppUpdateCheckButton(state = updateState, onClick = onCheckUpdate)
            }
            Spacer(Modifier.height(16.dp))
            OutlinedButton(
                onClick = { showChangelog = true },
                modifier = Modifier.fillMaxWidth().testTag("settings:whats-new"),
            ) {
                Icon(Icons.Outlined.NewReleases, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                Text(stringResource(R.string.appWhatsNew, appVersion), textAlign = TextAlign.Center)
            }
            AppUpdatePanel(
                state = updateState,
                onDownload = onDownloadUpdate,
                onInstall = onInstallUpdate,
                modifier = Modifier.padding(top = 12.dp),
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 20.dp), color = YaxcTheme.extendedColors.cardBorder)
            Text(
                text = stringResource(R.string.appComponents),
                style = MaterialTheme.typography.labelLarge,
                color = YaxcTheme.extendedColors.textMuted,
            )
            Spacer(Modifier.height(12.dp))
            ComponentVersionRow(stringResource(R.string.xrayLabel), xrayVersion)
            Spacer(Modifier.height(12.dp))
            ComponentVersionRow(stringResource(R.string.tun2socksLabel), tun2socksVersion)
            Spacer(Modifier.height(16.dp))
            OutlinedButton(
                onClick = { openRepositoryUrl(context, feedback) },
                modifier = Modifier.fillMaxWidth().testTag("settings:source-code"),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_github),
                    contentDescription = null,
                    modifier = Modifier.padding(end = 8.dp).size(18.dp),
                )
                Text(stringResource(R.string.appSourceCode), textAlign = TextAlign.Center)
            }
            TextButton(
                onClick = { showLicenses = true },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp).testTag("settings:licenses"),
            ) {
                Text(stringResource(R.string.appOpenSourceLicenses))
            }
        }
        Text(
            text = stringResource(R.string.madeWithPeople),
            style = MaterialTheme.typography.bodySmall,
            color = YaxcTheme.extendedColors.textMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    if (showChangelog) {
        InstalledChangelogDialog(appVersion = appVersion, onDismiss = { showChangelog = false })
    }
    if (showLicenses) {
        OpenSourceLicensesDialog(onDismiss = { showLicenses = false })
    }
}

private fun openRepositoryUrl(context: Context, feedback: YaxcFeedbackState) {
    val intent = Intent(Intent.ACTION_VIEW, APP_REPOSITORY_URL.toUri()).apply {
        if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        feedback.show(context.getString(R.string.appSourceCodeUnavailable), FeedbackKind.Warning)
    }
}

@Composable
private fun ComponentVersionRow(label: String, version: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = YaxcTheme.extendedColors.textMuted,
            modifier = Modifier.weight(1f),
        )
        Text(text = version, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

private sealed interface ChangelogState {
    data object Loading : ChangelogState
    data class Ready(val blocks: List<ChangelogBlock>) : ChangelogState
    data object Failed : ChangelogState
}

@Composable
private fun InstalledChangelogDialog(appVersion: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales.toLanguageTags()
    val state by produceState<ChangelogState>(ChangelogState.Loading, context, locale) {
        value = try {
            val text = InstalledChangelog.load(context)
            ChangelogState.Ready(withContext(Dispatchers.Default) { parseChangelog(text) })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ChangelogState.Failed
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 32.dp),
            contentAlignment = Alignment.Center,
        ) {
            YaxcGlassPanel(
                modifier = Modifier.widthIn(max = 560.dp).heightIn(max = maxHeight).testTag("changelog:dialog"),
                contentPadding = PaddingValues(20.dp),
            ) {
                Text(
                    text = stringResource(R.string.appWhatsNew, appVersion),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(16.dp))
                when (val notes = state) {
                    ChangelogState.Loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                    ChangelogState.Failed -> Text(
                        stringResource(R.string.appChangelogUnavailable),
                        color = MaterialTheme.colorScheme.error,
                    )
                    is ChangelogState.Ready -> LazyColumn(
                        modifier = Modifier.weight(1f, fill = false).testTag("changelog:content"),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(notes.blocks) { block ->
                            when (block.kind) {
                                ChangelogBlockKind.Heading -> Text(
                                    text = block.text,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                ChangelogBlockKind.Bullet -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text("•", color = MaterialTheme.colorScheme.primary)
                                    Text(block.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                ChangelogBlockKind.Paragraph -> Text(
                                    text = block.text,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End).testTag("changelog:close")) {
                    Text(stringResource(R.string.close))
                }
            }
        }
    }
}

private data class LicenseDocument(val name: String, val paragraphs: List<String>)

private sealed interface LicensesState {
    data object Loading : LicensesState
    data class Ready(val documents: List<LicenseDocument>) : LicensesState
    data object Failed : LicensesState
}

@Composable
private fun OpenSourceLicensesDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val state by produceState<LicensesState>(LicensesState.Loading, context) {
        value = try {
            LicensesState.Ready(withContext(Dispatchers.IO) {
                listOf("NOTICE.txt", "NaiveProxy.txt", "Chromium.txt", "Cronet.txt", "GPL-3.0.txt", "MPL-2.0.txt", "MIT.txt").map { name ->
                    val text = context.assets.open("licenses/$name").bufferedReader(Charsets.UTF_8).use { it.readText() }
                    LicenseDocument(name, text.split(Regex("\\r?\\n\\s*\\r?\\n")).filter { it.isNotBlank() })
                }
            })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            LicensesState.Failed
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 32.dp),
            contentAlignment = Alignment.Center,
        ) {
            YaxcGlassPanel(
                modifier = Modifier.widthIn(max = 560.dp).heightIn(max = maxHeight).testTag("licenses:dialog"),
                contentPadding = PaddingValues(20.dp),
            ) {
                Text(stringResource(R.string.appOpenSourceLicenses), style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(16.dp))
                when (val licenses = state) {
                    LicensesState.Loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                    LicensesState.Failed -> Text(
                        stringResource(R.string.appLicensesUnavailable), color = MaterialTheme.colorScheme.error,
                    )
                    is LicensesState.Ready -> LazyColumn(
                        modifier = Modifier.weight(1f, fill = false).testTag("licenses:content"),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        licenses.documents.forEach { document ->
                            item(key = document.name) {
                                Text(document.name, style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary)
                            }
                            items(count = document.paragraphs.size, key = { "${document.name}:$it" }) { index ->
                                SelectionContainer {
                                    Text(document.paragraphs[index], style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End).testTag("licenses:close")) {
                    Text(stringResource(R.string.close))
                }
            }
        }
    }
}

internal enum class ChangelogBlockKind { Heading, Bullet, Paragraph }
internal data class ChangelogBlock(val kind: ChangelogBlockKind, val text: String)

/** Release notes use a leading #/## title, ### sections, bullets and wrapped paragraphs. */
internal fun parseChangelog(markdown: String): List<ChangelogBlock> = buildList {
    val text = StringBuilder()
    var kind = ChangelogBlockKind.Paragraph
    var firstContent = true
    fun flush() {
        if (text.isNotEmpty()) {
            add(ChangelogBlock(kind, text.toString()))
            text.setLength(0)
        }
        kind = ChangelogBlockKind.Paragraph
    }
    markdown.lineSequence().forEach { raw ->
        val line = raw.trim()
        when {
            line.isEmpty() -> flush()
            firstContent && (line.startsWith("# ") || line.startsWith("## ")) -> firstContent = false
            line.startsWith("#") && line.substringBefore(' ').all { it == '#' } && ' ' in line -> {
                firstContent = false
                flush()
                add(ChangelogBlock(ChangelogBlockKind.Heading, line.substringAfter(' ').trim()))
            }
            line.startsWith("- ") -> {
                firstContent = false
                flush()
                kind = ChangelogBlockKind.Bullet
                text.append(line.removePrefix("- "))
            }
            else -> {
                firstContent = false
                if (text.isNotEmpty()) text.append(' ')
                text.append(line)
            }
        }
    }
    flush()
}
