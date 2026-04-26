package io.github.derundevu.yaxc.presentation.connection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.derundevu.yaxc.R
import io.github.derundevu.yaxc.dto.SubscriptionMetadata
import io.github.derundevu.yaxc.helper.HttpHelper
import io.github.derundevu.yaxc.presentation.designsystem.YaxcTheme
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcCard
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcScaffold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionInfoScreen(
    state: ConnectionInfoScreenState,
    onBack: () -> Unit,
) {
    val spacing = YaxcTheme.spacing
    var showPassword by rememberSaveable { mutableStateOf(false) }
    val noValue = textResource(R.string.noValue)
    val resolveFailed = textResource(R.string.mainConnectionResolveFailed)
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
        ?.updateIntervalHours
        ?.let { formatAutoUpdateFull(it) }
        ?: noValue
    val resolvedServerValue by produceState(
        initialValue = noValue,
        state.selectedServerLabel,
    ) {
        val target = state.selectedServerLabel.trim()
        value = when {
            target.isBlank() || target == noValue -> noValue
            else -> withContext(Dispatchers.IO) {
                runCatching {
                    InetAddress.getAllByName(target)
                        .mapNotNull { it.hostAddress?.trim() }
                        .filter { it.isNotEmpty() }
                        .distinct()
                        .joinToString(", ")
                        .ifBlank { noValue }
                }.getOrElse { resolveFailed }
            }
        }
    }
    val exitIpValue by produceState(
        initialValue = noValue,
        state.socksAddress,
        state.socksPort,
        state.socksUsername,
        state.socksPassword,
    ) {
        val address = state.socksAddress.trim()
        val port = state.socksPort.trim()
        value = when {
            address.isBlank() || port.isBlank() -> noValue
            else -> withContext(Dispatchers.IO) {
                runCatching {
                    HttpHelper.resolveExitIpViaSocks(
                        socksAddress = address,
                        socksPort = port,
                        socksUsername = state.socksUsername.trim(),
                        socksPassword = state.socksPassword,
                    )
                }.getOrElse { resolveFailed }
            }
        }
    }

    YaxcScaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = textResource(R.string.mainConnectionInfo)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = null,
                        )
                    }
                },
            )
        },
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.md)
                .padding(top = spacing.md, bottom = spacing.xl),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Text(
                text = textResource(R.string.connectionInfoScreenLead),
                style = MaterialTheme.typography.bodyLarge,
                color = YaxcTheme.extendedColors.textMuted,
            )

            ConnectionInfoSection(title = textResource(R.string.connectionInfoOverviewSection)) {
                ConnectionInfoRow(
                    label = textResource(R.string.connectionInfoSourceLabel),
                    value = state.selectedSourceName.ifBlank { noValue },
                )
                ConnectionInfoRow(
                    label = textResource(R.string.mainSelectedProfileLabel),
                    value = state.selectedProfileName.ifBlank { textResource(R.string.mainNoSelectedProfile) },
                )
                ConnectionInfoRow(
                    label = textResource(R.string.mainServerAddress),
                    value = state.selectedServerLabel.ifBlank { noValue },
                )
                ConnectionInfoRow(
                    label = textResource(R.string.mainResolvedAddress),
                    value = resolvedServerValue,
                )
                ConnectionInfoRow(
                    label = textResource(R.string.mainExitAddress),
                    value = exitIpValue,
                )
            }

            state.selectedSourceMetadata?.let { metadata ->
                ConnectionInfoSection(title = textResource(R.string.connectionInfoSubscriptionSection)) {
                    ConnectionInfoRow(
                        label = textResource(R.string.mainSubscriptionTrafficUsed),
                        value = trafficUsedValue,
                    )
                    ConnectionInfoRow(
                        label = textResource(R.string.mainSubscriptionTrafficLimit),
                        value = trafficLimitValue,
                    )
                    ConnectionInfoRow(
                        label = textResource(R.string.mainSubscriptionDaysLeft),
                        value = daysLeftValue,
                    )
                    ConnectionInfoRow(
                        label = textResource(R.string.mainSubscriptionExpiresAt),
                        value = expiresAtValue,
                    )
                    ConnectionInfoRow(
                        label = textResource(R.string.mainSubscriptionAutoUpdate),
                        value = autoUpdateValue,
                    )
                    metadata.supportUrl?.takeIf { it.isNotBlank() }?.let {
                        ConnectionInfoRow(
                            label = textResource(R.string.mainSubscriptionSupportUrl),
                            value = it,
                        )
                    }
                    metadata.profileWebPageUrl?.takeIf { it.isNotBlank() }?.let {
                        ConnectionInfoRow(
                            label = textResource(R.string.mainSubscriptionWebPage),
                            value = it,
                        )
                    }
                }
            }

            ConnectionInfoSection(title = textResource(R.string.connectionInfoProxySection)) {
                ConnectionInfoRow(
                    label = textResource(R.string.socksAddress),
                    value = state.socksAddress.ifBlank { noValue },
                )
                ConnectionInfoRow(
                    label = textResource(R.string.socksPort),
                    value = state.socksPort.ifBlank { noValue },
                )
                ConnectionInfoRow(
                    label = textResource(R.string.socksUsername),
                    value = state.socksUsername.ifBlank { noValue },
                )
                ConnectionInfoRow(
                    label = textResource(R.string.socksPassword),
                    value = if (showPassword) {
                        state.socksPassword.ifBlank { noValue }
                    } else {
                        if (state.socksPassword.isBlank()) noValue
                        else textResource(R.string.mainConnectionPasswordHidden)
                    },
                    trailing = {
                        if (state.socksPassword.isNotBlank()) {
                            IconButton(onClick = { showPassword = !showPassword }) {
                                Icon(
                                    imageVector = if (showPassword) {
                                        Icons.Outlined.VisibilityOff
                                    } else {
                                        Icons.Outlined.Visibility
                                    },
                                    contentDescription = null,
                                    tint = YaxcTheme.extendedColors.textMuted,
                                )
                            }
                        }
                    },
                )
                ConnectionInfoRow(
                    label = textResource(R.string.pingAddress),
                    value = state.pingAddress.ifBlank { noValue },
                )
            }
        }
    }
}

@Composable
private fun ConnectionInfoSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    YaxcCard {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
        )
        Column(
            modifier = Modifier.padding(top = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
private fun ConnectionInfoRow(
    label: String,
    value: String,
    trailing: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = YaxcTheme.extendedColors.textMuted,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            trailing?.invoke()
        }
    }
}

@Composable
private fun textResource(id: Int): String {
    return androidx.compose.ui.res.stringResource(id)
}
