package io.github.derundevu.yaxc.presentation.connection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.derundevu.yaxc.R
import io.github.derundevu.yaxc.dto.SubscriptionMetadata
import io.github.derundevu.yaxc.helper.HttpHelper
import io.github.derundevu.yaxc.presentation.designsystem.YaxcTheme
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcCard
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcDivider
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcGlassPanel
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcScaffold
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcTopAppBar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress

@Immutable
data class ConnectionInfoScreenState(
    val selectedSourceName: String,
    val selectedProfileName: String,
    val selectedSourceMetadata: SubscriptionMetadata?,
    val selectedServerLabel: String,
    val socksAddress: String,
    val socksPort: String,
    val socksUsername: String,
    val socksPassword: String,
    val pingAddress: String,
)

private sealed interface ResolveFieldState {
    data object Empty : ResolveFieldState
    data object Loading : ResolveFieldState
    data class Loaded(val value: String) : ResolveFieldState
    data object Failed : ResolveFieldState
}

private data class ConnectionInfoItem(
    val label: String,
    val value: String,
    val valueColor: Color? = null,
    val modifier: Modifier = Modifier,
    val trailing: (@Composable () -> Unit)? = null,
)

@Composable
fun ConnectionInfoScreen(
    state: ConnectionInfoScreenState,
    onBack: () -> Unit,
) {
    ConnectionInfoScreen(
        state = state,
        onBack = onBack,
        resolveServer = { target ->
            withContext(Dispatchers.IO) {
                InetAddress.getAllByName(target)
                    .mapNotNull { it.hostAddress?.trim() }
                    .filter { it.isNotEmpty() }
                    .distinct()
                    .joinToString(", ")
            }
        },
        resolveExitIp = { address, port, username, password ->
            withContext(Dispatchers.IO) {
                HttpHelper.resolveExitIpViaSocks(
                    socksAddress = address,
                    socksPort = port,
                    socksUsername = username,
                    socksPassword = password,
                )
            }
        },
    )
}

@Composable
internal fun ConnectionInfoScreen(
    state: ConnectionInfoScreenState,
    onBack: () -> Unit,
    resolveServer: suspend (String) -> String,
    resolveExitIp: suspend (
        socksAddress: String,
        socksPort: String,
        socksUsername: String,
        socksPassword: String,
    ) -> String,
) {
    val spacing = YaxcTheme.spacing
    var showPassword by rememberSaveable { mutableStateOf(false) }
    val noValue = textResource(R.string.noValue)
    val checkingLabel = textResource(R.string.editorChecking)
    val resolveFailed = textResource(R.string.mainConnectionResolveFailed)
    val mutedColor = YaxcTheme.extendedColors.textMuted
    val errorColor = MaterialTheme.colorScheme.error

    val trafficUsedValue = state.selectedSourceMetadata
        ?.usedBytes
        ?.let(::formatBytesCompact)
        ?: noValue
    val trafficLimitValue = state.selectedSourceMetadata
        ?.totalBytes
        ?.let { totalBytes ->
            if (totalBytes == 0L) {
                textResource(R.string.mainSubscriptionUnlimited)
            } else {
                formatBytesCompact(totalBytes)
            }
        }
        ?: noValue
    val expiresAtValue = state.selectedSourceMetadata
        ?.expireAtEpochSeconds
        ?.takeIf { it > 0L }
        ?.let(::formatExpiryDateTime)
        ?: noValue
    val daysLeftValue = state.selectedSourceMetadata
        ?.expireAtEpochSeconds
        ?.takeIf { it > 0L }
        ?.let { formatDaysLeftFull(it) }
        ?: noValue
    val autoUpdateValue = state.selectedSourceMetadata
        ?.updateIntervalMinutes
        ?.let { formatAutoUpdateFull(it) }
        ?: noValue

    val resolvedServerState by produceState<ResolveFieldState>(
        initialValue = if (state.selectedServerLabel.trim().let { it.isBlank() || it == noValue }) {
            ResolveFieldState.Empty
        } else {
            ResolveFieldState.Loading
        },
        state.selectedServerLabel,
        noValue,
    ) {
        val target = state.selectedServerLabel.trim()
        value = if (target.isBlank() || target == noValue) {
            ResolveFieldState.Empty
        } else {
            value = ResolveFieldState.Loading
            try {
                val resolved = resolveServer(target).trim()
                if (resolved.isBlank()) ResolveFieldState.Empty else ResolveFieldState.Loaded(resolved)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                ResolveFieldState.Failed
            }
        }
    }

    val exitIpState by produceState<ResolveFieldState>(
        initialValue = if (state.socksAddress.trim().isBlank() || state.socksPort.trim().isBlank()) {
            ResolveFieldState.Empty
        } else {
            ResolveFieldState.Loading
        },
        state.socksAddress,
        state.socksPort,
        state.socksUsername,
        state.socksPassword,
    ) {
        val address = state.socksAddress.trim()
        val port = state.socksPort.trim()
        value = if (address.isBlank() || port.isBlank()) {
            ResolveFieldState.Empty
        } else {
            value = ResolveFieldState.Loading
            try {
                val resolved = resolveExitIp(
                    address,
                    port,
                    state.socksUsername.trim(),
                    state.socksPassword,
                ).trim()
                if (resolved.isBlank()) ResolveFieldState.Empty else ResolveFieldState.Loaded(resolved)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                ResolveFieldState.Failed
            }
        }
    }

    val overviewItems = listOf(
        ConnectionInfoItem(
            label = textResource(R.string.connectionInfoSourceLabel),
            value = state.selectedSourceName.ifBlank { noValue },
            valueColor = if (state.selectedSourceName.isBlank()) mutedColor else null,
        ),
        ConnectionInfoItem(
            label = textResource(R.string.mainSelectedProfileLabel),
            value = state.selectedProfileName.ifBlank { textResource(R.string.mainNoSelectedProfile) },
            valueColor = if (state.selectedProfileName.isBlank()) mutedColor else null,
        ),
        ConnectionInfoItem(
            label = textResource(R.string.mainServerAddress),
            value = state.selectedServerLabel.ifBlank { noValue },
            valueColor = if (state.selectedServerLabel.isBlank()) mutedColor else null,
        ),
        resolveFieldItem(
            label = textResource(R.string.mainResolvedAddress),
            fieldState = resolvedServerState,
            emptyValue = noValue,
            loadingValue = checkingLabel,
            failedValue = resolveFailed,
            mutedColor = mutedColor,
            errorColor = errorColor,
            testTag = "connection-info:resolved-server",
        ),
        resolveFieldItem(
            label = textResource(R.string.mainExitAddress),
            fieldState = exitIpState,
            emptyValue = noValue,
            loadingValue = checkingLabel,
            failedValue = resolveFailed,
            mutedColor = mutedColor,
            errorColor = errorColor,
            testTag = "connection-info:exit-ip",
        ),
    )

    val subscriptionItems = state.selectedSourceMetadata?.let { metadata ->
        buildList {
            add(
                ConnectionInfoItem(
                    label = textResource(R.string.mainSubscriptionTrafficUsed),
                    value = trafficUsedValue,
                    valueColor = if (metadata.usedBytes == null) mutedColor else null,
                ),
            )
            add(
                ConnectionInfoItem(
                    label = textResource(R.string.mainSubscriptionTrafficLimit),
                    value = trafficLimitValue,
                    valueColor = if (metadata.totalBytes == null) mutedColor else null,
                ),
            )
            add(
                ConnectionInfoItem(
                    label = textResource(R.string.mainSubscriptionDaysLeft),
                    value = daysLeftValue,
                    valueColor = if (metadata.expireAtEpochSeconds?.takeIf { it > 0L } == null) mutedColor else null,
                ),
            )
            add(
                ConnectionInfoItem(
                    label = textResource(R.string.mainSubscriptionExpiresAt),
                    value = expiresAtValue,
                    valueColor = if (metadata.expireAtEpochSeconds?.takeIf { it > 0L } == null) mutedColor else null,
                ),
            )
            add(
                ConnectionInfoItem(
                    label = textResource(R.string.mainSubscriptionAutoUpdate),
                    value = autoUpdateValue,
                    valueColor = if (metadata.updateIntervalMinutes == null) mutedColor else null,
                ),
            )
            metadata.supportUrl?.takeIf { it.isNotBlank() }?.let { supportUrl ->
                add(
                    ConnectionInfoItem(
                        label = textResource(R.string.mainSubscriptionSupportUrl),
                        value = supportUrl,
                    ),
                )
            }
            metadata.profileWebPageUrl?.takeIf { it.isNotBlank() }?.let { webPageUrl ->
                add(
                    ConnectionInfoItem(
                        label = textResource(R.string.mainSubscriptionWebPage),
                        value = webPageUrl,
                    ),
                )
            }
        }
    }

    val hasPassword = state.socksPassword.isNotBlank()
    val passwordValue = when {
        !hasPassword -> noValue
        showPassword -> state.socksPassword
        else -> textResource(R.string.mainConnectionPasswordHidden)
    }
    val proxyItems = listOf(
        ConnectionInfoItem(
            label = textResource(R.string.socksAddress),
            value = state.socksAddress.ifBlank { noValue },
            valueColor = if (state.socksAddress.isBlank()) mutedColor else null,
        ),
        ConnectionInfoItem(
            label = textResource(R.string.socksPort),
            value = state.socksPort.ifBlank { noValue },
            valueColor = if (state.socksPort.isBlank()) mutedColor else null,
        ),
        ConnectionInfoItem(
            label = textResource(R.string.socksUsername),
            value = state.socksUsername.ifBlank { noValue },
            valueColor = if (state.socksUsername.isBlank()) mutedColor else null,
        ),
        ConnectionInfoItem(
            label = textResource(R.string.socksPassword),
            value = passwordValue,
            valueColor = if (!hasPassword || !showPassword) mutedColor else null,
            modifier = Modifier.testTag("connection-info:password"),
            trailing = if (hasPassword) {
                {
                    IconButton(
                        onClick = { showPassword = !showPassword },
                        modifier = Modifier.testTag("connection-info:password-toggle"),
                    ) {
                        Icon(
                            imageVector = if (showPassword) {
                                Icons.Outlined.VisibilityOff
                            } else {
                                Icons.Outlined.Visibility
                            },
                            contentDescription = textResource(
                                if (showPassword) {
                                    R.string.settingsHidePassword
                                } else {
                                    R.string.settingsShowPassword
                                },
                            ),
                            tint = mutedColor,
                        )
                    }
                }
            } else {
                null
            },
        ),
        ConnectionInfoItem(
            label = textResource(R.string.pingAddress),
            value = state.pingAddress.ifBlank { noValue },
            valueColor = if (state.pingAddress.isBlank()) mutedColor else null,
        ),
    )

    YaxcScaffold(
        topBar = {
            YaxcTopAppBar(
                title = textResource(R.string.mainConnectionInfo),
                onBack = onBack,
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .testTag("connection-info:list")
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.md)
                .padding(top = spacing.md, bottom = spacing.xl),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Text(
                text = textResource(R.string.connectionInfoScreenLead),
                style = MaterialTheme.typography.bodyLarge,
                color = mutedColor,
            )

            ConnectionInfoSectionHeader(title = textResource(R.string.connectionInfoOverviewSection))
            YaxcGlassPanel(
                modifier = Modifier.testTag("connection-info:overview"),
                contentPadding = YaxcTheme.paddings.section,
                shadowElevation = 8.dp,
            ) {
                ConnectionInfoSectionRows(items = overviewItems)
            }

            if (subscriptionItems != null) {
                ConnectionInfoSectionHeader(title = textResource(R.string.connectionInfoSubscriptionSection))
                ConnectionInfoCard(modifier = Modifier.testTag("connection-info:subscription")) {
                    ConnectionInfoSectionRows(items = subscriptionItems)
                }
            }

            ConnectionInfoSectionHeader(title = textResource(R.string.connectionInfoProxySection))
            ConnectionInfoCard(modifier = Modifier.testTag("connection-info:proxy")) {
                ConnectionInfoSectionRows(items = proxyItems)
            }
        }
    }
}

private fun resolveFieldItem(
    label: String,
    fieldState: ResolveFieldState,
    emptyValue: String,
    loadingValue: String,
    failedValue: String,
    mutedColor: Color,
    errorColor: Color,
    testTag: String,
): ConnectionInfoItem {
    return when (fieldState) {
        ResolveFieldState.Empty -> ConnectionInfoItem(
            label = label,
            value = emptyValue,
            valueColor = mutedColor,
            modifier = Modifier.testTag(testTag),
        )
        ResolveFieldState.Loading -> ConnectionInfoItem(
            label = label,
            value = loadingValue,
            valueColor = mutedColor,
            modifier = Modifier.testTag(testTag),
            trailing = {
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(16.dp)
                        .testTag("$testTag:loading"),
                    strokeWidth = 2.dp,
                )
            },
        )
        is ResolveFieldState.Loaded -> ConnectionInfoItem(
            label = label,
            value = fieldState.value,
            modifier = Modifier.testTag(testTag),
        )
        ResolveFieldState.Failed -> ConnectionInfoItem(
            label = label,
            value = failedValue,
            valueColor = errorColor,
            modifier = Modifier.testTag(testTag),
        )
    }
}

@Composable
private fun ConnectionInfoSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = YaxcTheme.extendedColors.textMuted,
        modifier = Modifier.padding(horizontal = YaxcTheme.spacing.xs),
    )
}

@Composable
private fun ConnectionInfoCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    YaxcCard(
        modifier = modifier,
        contentPadding = YaxcTheme.paddings.section,
        content = content,
    )
}

@Composable
private fun ConnectionInfoSectionRows(items: List<ConnectionInfoItem>) {
    items.forEachIndexed { index, item ->
        if (index > 0) {
            YaxcDivider()
        }
        ConnectionInfoRow(
            label = item.label,
            value = item.value,
            valueColor = item.valueColor ?: MaterialTheme.colorScheme.onSurface,
            modifier = item.modifier,
            trailing = item.trailing,
        )
    }
}

@Composable
private fun ConnectionInfoRow(
    label: String,
    value: String,
    valueColor: Color,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = YaxcTheme.spacing.xs)
            .heightIn(min = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = YaxcTheme.extendedColors.textMuted,
            )
            SelectionContainer {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = valueColor,
                )
            }
        }
        trailing?.invoke()
    }
}

@Composable
private fun textResource(id: Int): String {
    return androidx.compose.ui.res.stringResource(id)
}
