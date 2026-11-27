package io.github.derundevu.yaxc.presentation.profile

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.derundevu.yaxc.R
import io.github.derundevu.yaxc.presentation.designsystem.YaxcTheme
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcActionBubble
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcCard
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcDivider
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcSettingsRow
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcSwitchRow
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcTabOption
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcTabRow
import java.util.UUID

private data class PickerChoice(
    val value: String,
    val label: String,
)

private enum class ActivePicker {
    OutboundIndex,
    Protocol,
    OutboundTag,
    VlessFlow,
    VlessEncryption,
    VmessSecurity,
    SsMethod,
    NaiveCongestion,
    FreedomStrategy,
    BlackholeResponse,
    Network,
    XhttpMode,
    Fingerprint,
    Alpn,
}

@Composable
internal fun ProfileVisualEditor(
    name: String,
    selectedSourceName: String,
    visualConfig: ProfileVisualConfig,
    enabled: Boolean,
    onNameChange: (String) -> Unit,
    onOpenSourcePicker: () -> Unit,
    onVisualConfigChange: ((ProfileVisualConfig) -> ProfileVisualConfig) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = YaxcTheme.spacing
    val density = LocalDensity.current
    val navigationBarBottom = WindowInsets.navigationBars.getBottom(density)
    val gestureNavigation = navigationBarBottom <= with(density) { 32.dp.roundToPx() }
    val reservedBottomInset = if (gestureNavigation) 0.dp else with(density) { navigationBarBottom.toDp() }
    val outbound = visualConfig.activeOutbound
    val normalizedProtocol = outbound.protocol.trim().lowercase()
    val normalizedNetwork = outbound.network.trim().lowercase()
    val normalizedSecurity = outbound.security.trim().lowercase()
    val supportsTransportSecurity = ProfileVisualConfigHelper.supportsTransportAndSecurity(normalizedProtocol)

    var activePicker by rememberSaveable { mutableStateOf<ActivePicker?>(null) }
    var advancedExpanded by rememberSaveable(outbound.id) {
        mutableStateOf(
            outbound.muxEnabled ||
                outbound.tcpFastOpen != null ||
                outbound.dialerProxy.isNotBlank() ||
                outbound.muxConcurrency.isNotBlank(),
        )
    }
    val expandRotation by animateFloatAsState(
        targetValue = if (advancedExpanded) 180f else 0f,
        animationSpec = tween(durationMillis = 220),
        label = "profile_advanced_rotation",
    )

    LazyColumn(
        modifier = modifier
            .testTag("profile-editor:visual-content")
            .fillMaxWidth()
            .imePadding()
            .padding(horizontal = spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
        contentPadding = PaddingValues(
            top = spacing.sm,
            bottom = spacing.xl + reservedBottomInset,
        ),
    ) {
        item(key = "section_profile") {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                SectionLabel(stringResource(R.string.profileVisualSectionProfile))
                YaxcCard(contentPadding = PaddingValues(0.dp)) {
                    VisualTextField(
                        label = stringResource(R.string.profileName),
                        value = name,
                        onValueChange = onNameChange,
                        enabled = enabled,
                        testTag = "profile-visual:name",
                    )
                    YaxcDivider()
                    VisualPickerRow(
                        title = stringResource(R.string.profileSource),
                        value = selectedSourceName.ifBlank { stringResource(R.string.noValue) },
                        enabled = enabled,
                        testTag = "profile-visual:source",
                        onClick = onOpenSourcePicker,
                    )
                }
            }
        }

        item(key = "section_outbound") {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    SectionLabel(stringResource(R.string.profileVisualSectionOutbound))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        YaxcActionBubble(
                            icon = Icons.Outlined.Add,
                            onClick = { if (enabled) onVisualConfigChange { it.addOutbound() } },
                            contentDescription = stringResource(R.string.profileVisualAddOutbound),
                            modifier = Modifier.testTag("profile-visual:add-outbound"),
                        )
                        if (visualConfig.outbounds.size > 1) {
                            YaxcActionBubble(
                                icon = Icons.Outlined.DeleteOutline,
                                onClick = { if (enabled) onVisualConfigChange { it.deleteSelectedOutbound() } },
                                contentDescription = stringResource(R.string.profileVisualDeleteOutbound),
                                modifier = Modifier.testTag("profile-visual:delete-outbound"),
                            )
                        }
                    }
                }
                YaxcCard(contentPadding = PaddingValues(0.dp)) {
                    if (visualConfig.outbounds.size > 1) {
                        VisualPickerRow(
                            title = stringResource(R.string.profileVisualActiveOutbound),
                            value = outboundSummary(
                                index = visualConfig.selectedOutboundIndex,
                                outbound = outbound,
                            ),
                            enabled = enabled,
                            testTag = "profile-visual:active-outbound",
                            onClick = { activePicker = ActivePicker.OutboundIndex },
                        )
                        YaxcDivider()
                    }
                    VisualPickerRow(
                        title = stringResource(R.string.profileVisualProtocol),
                        value = displayProtocol(outbound.protocol),
                        enabled = enabled,
                        testTag = "profile-visual:protocol",
                        onClick = { activePicker = ActivePicker.Protocol },
                    )
                    YaxcDivider()
                    VisualPickerRow(
                        title = stringResource(R.string.profileVisualOutboundTag),
                        value = outbound.tag.ifBlank { stringResource(R.string.profileVisualOptionNone) },
                        enabled = enabled,
                        testTag = "profile-visual:tag",
                        onClick = { activePicker = ActivePicker.OutboundTag },
                    )
                }
            }
        }

        item(key = "section_connection") {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                SectionLabel(stringResource(R.string.profileVisualSectionConnection))
                YaxcCard(contentPadding = PaddingValues(0.dp)) {
                    ConnectionFields(
                        outbound = outbound,
                        normalizedProtocol = normalizedProtocol,
                        enabled = enabled,
                        onUpdate = { transform -> onVisualConfigChange { it.updateActiveOutbound(transform) } },
                        onOpenPicker = { activePicker = it },
                    )
                }
            }
        }

        if (supportsTransportSecurity) {
            item(key = "section_transport") {
                Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                    SectionLabel(stringResource(R.string.profileVisualSectionTransport))
                    YaxcCard(contentPadding = PaddingValues(0.dp)) {
                        VisualPickerRow(
                            title = stringResource(R.string.profileVisualNetwork),
                            value = outbound.network.uppercase(),
                            enabled = enabled,
                            testTag = "profile-visual:network",
                            onClick = { activePicker = ActivePicker.Network },
                        )
                        TransportSpecificFields(
                            outbound = outbound,
                            normalizedNetwork = normalizedNetwork,
                            enabled = enabled,
                            onUpdate = { transform -> onVisualConfigChange { it.updateActiveOutbound(transform) } },
                            onOpenPicker = { activePicker = it },
                        )
                    }
                }
            }

            item(key = "section_security") {
                Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                    SectionLabel(stringResource(R.string.profileVisualSectionSecurity))
                    YaxcCard(contentPadding = PaddingValues(0.dp)) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(spacing.sm),
                        ) {
                            Text(
                                text = stringResource(R.string.profileVisualSecurityType),
                                style = MaterialTheme.typography.labelLarge,
                                color = YaxcTheme.extendedColors.textMuted,
                            )
                            YaxcTabRow(
                                options = listOf(
                                    YaxcTabOption("none", stringResource(R.string.profileVisualSecurityNone), "profile-visual:security:none"),
                                    YaxcTabOption("tls", stringResource(R.string.profileVisualSecurityTls), "profile-visual:security:tls"),
                                    YaxcTabOption("reality", stringResource(R.string.profileVisualSecurityReality), "profile-visual:security:reality"),
                                ),
                                selected = normalizedSecurity,
                                onSelect = { selected ->
                                    onVisualConfigChange { config ->
                                        config.updateActiveOutbound { it.copy(security = selected) }
                                    }
                                },
                                enabled = enabled,
                                nested = true,
                            )
                            if (normalizedSecurity !in ProfileVisualOptions.securities) {
                                Text(
                                    text = stringResource(R.string.profileVisualSecurityCustomNotice, outbound.security),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = YaxcTheme.extendedColors.textMuted,
                                )
                            }
                        }

                        if (normalizedSecurity == "tls" || normalizedSecurity == "reality") {
                            YaxcDivider()
                            VisualTextField(
                                label = stringResource(R.string.profileVisualSni),
                                value = outbound.sni,
                                onValueChange = { value ->
                                    onVisualConfigChange { it.updateActiveOutbound { cur -> cur.copy(sni = value) } }
                                },
                                enabled = enabled,
                                testTag = "profile-visual:sni",
                            )
                            YaxcDivider()
                            VisualPickerRow(
                                title = stringResource(R.string.profileVisualFingerprint),
                                value = outbound.fingerprint.ifBlank { stringResource(R.string.profileVisualOptionNone) },
                                enabled = enabled,
                                testTag = "profile-visual:fingerprint",
                                onClick = { activePicker = ActivePicker.Fingerprint },
                            )
                        }
                        if (normalizedSecurity == "tls") {
                            YaxcDivider()
                            VisualPickerRow(
                                title = stringResource(R.string.profileVisualAlpn),
                                value = outbound.alpn.ifBlank { stringResource(R.string.profileVisualOptionDefault) },
                                enabled = enabled,
                                testTag = "profile-visual:alpn",
                                onClick = { activePicker = ActivePicker.Alpn },
                            )
                            YaxcDivider()
                            VisualSwitchRow(
                                title = stringResource(R.string.profileVisualAllowInsecure),
                                checked = outbound.allowInsecure,
                                onCheckedChange = { checked ->
                                    onVisualConfigChange { it.updateActiveOutbound { cur -> cur.copy(allowInsecure = checked) } }
                                },
                                enabled = enabled,
                            )
                        }
                        if (normalizedSecurity == "reality") {
                            YaxcDivider()
                            VisualTextField(
                                label = stringResource(R.string.profileVisualRealityPublicKey),
                                value = outbound.realityPublicKey,
                                onValueChange = { value ->
                                    onVisualConfigChange { it.updateActiveOutbound { cur -> cur.copy(realityPublicKey = value) } }
                                },
                                enabled = enabled,
                                testTag = "profile-visual:reality-pbk",
                            )
                            YaxcDivider()
                            VisualTextField(
                                label = stringResource(R.string.profileVisualRealityShortId),
                                value = outbound.realityShortId,
                                onValueChange = { value ->
                                    onVisualConfigChange { it.updateActiveOutbound { cur -> cur.copy(realityShortId = value) } }
                                },
                                enabled = enabled,
                                testTag = "profile-visual:reality-sid",
                            )
                            YaxcDivider()
                            VisualTextField(
                                label = stringResource(R.string.profileVisualRealitySpiderX),
                                value = outbound.realitySpiderX,
                                onValueChange = { value ->
                                    onVisualConfigChange { it.updateActiveOutbound { cur -> cur.copy(realitySpiderX = value) } }
                                },
                                enabled = enabled,
                            )
                            if (outbound.realityMldsa65Verify.isNotBlank()) {
                                YaxcDivider()
                                VisualTextField(
                                    label = stringResource(R.string.profileVisualRealityMldsa),
                                    value = outbound.realityMldsa65Verify,
                                    onValueChange = { value ->
                                        onVisualConfigChange { it.updateActiveOutbound { cur -> cur.copy(realityMldsa65Verify = value) } }
                                    },
                                    enabled = enabled,
                                )
                            }
                        }
                    }
                }
            }
        }

        item(key = "section_advanced") {
            YaxcCard(contentPadding = PaddingValues(0.dp)) {
                YaxcSettingsRow(
                    title = stringResource(R.string.profileVisualSectionAdvanced),
                    onClick = { advancedExpanded = !advancedExpanded },
                    shape = RectangleShape,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    modifier = Modifier.testTag("profile-visual:advanced-toggle"),
                    trailing = {
                        Icon(
                            imageVector = Icons.Outlined.UnfoldMore,
                            contentDescription = null,
                            modifier = Modifier.graphicsLayer { rotationZ = expandRotation },
                            tint = YaxcTheme.extendedColors.textMuted,
                        )
                    },
                )
                AnimatedVisibility(visible = advancedExpanded) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        YaxcDivider()
                        VisualSwitchRow(
                            title = stringResource(R.string.profileVisualMuxEnabled),
                            checked = outbound.muxEnabled,
                            onCheckedChange = { checked ->
                                onVisualConfigChange { it.updateActiveOutbound { cur -> cur.copy(muxEnabled = checked) } }
                            },
                            enabled = enabled,
                        )
                        if (outbound.muxEnabled) {
                            YaxcDivider()
                            VisualTextField(
                                label = stringResource(R.string.profileVisualMuxConcurrency),
                                value = outbound.muxConcurrency,
                                onValueChange = { value ->
                                    onVisualConfigChange {
                                        it.updateActiveOutbound { cur -> cur.copy(muxConcurrency = value.filter { ch -> ch.isDigit() || ch == '-' }) }
                                    }
                                },
                                keyboardType = KeyboardType.Number,
                                enabled = enabled,
                            )
                            YaxcDivider()
                            VisualTextField(
                                label = stringResource(R.string.profileVisualMuxXudpConcurrency),
                                value = outbound.muxXudpConcurrency,
                                onValueChange = { value ->
                                    onVisualConfigChange {
                                        it.updateActiveOutbound { cur -> cur.copy(muxXudpConcurrency = value.filter { ch -> ch.isDigit() || ch == '-' }) }
                                    }
                                },
                                keyboardType = KeyboardType.Number,
                                enabled = enabled,
                            )
                        }
                        YaxcDivider()
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(spacing.xs),
                        ) {
                            Text(
                                text = stringResource(R.string.profileVisualTcpFastOpen),
                                style = MaterialTheme.typography.labelLarge,
                                color = YaxcTheme.extendedColors.textMuted,
                            )
                            YaxcTabRow(
                                options = listOf(
                                    YaxcTabOption(null as Boolean?, stringResource(R.string.profileVisualTriStateDefault)),
                                    YaxcTabOption(true as Boolean?, stringResource(R.string.profileVisualTriStateOn)),
                                    YaxcTabOption(false as Boolean?, stringResource(R.string.profileVisualTriStateOff)),
                                ),
                                selected = outbound.tcpFastOpen,
                                onSelect = { value ->
                                    onVisualConfigChange { it.updateActiveOutbound { cur -> cur.copy(tcpFastOpen = value) } }
                                },
                                enabled = enabled,
                                nested = true,
                            )
                        }
                        YaxcDivider()
                        VisualTextField(
                            label = stringResource(R.string.profileVisualDialerProxy),
                            value = outbound.dialerProxy,
                            onValueChange = { value ->
                                onVisualConfigChange { it.updateActiveOutbound { cur -> cur.copy(dialerProxy = value) } }
                            },
                            enabled = enabled,
                        )
                    }
                }
            }
        }
    }

    activePicker?.let { picker ->
        PickerDialogHost(
            picker = picker,
            visualConfig = visualConfig,
            outbound = outbound,
            onDismiss = { activePicker = null },
            onVisualConfigChange = onVisualConfigChange,
        )
    }
}

@Composable
private fun ConnectionFields(
    outbound: ProfileOutboundDraft,
    normalizedProtocol: String,
    enabled: Boolean,
    onUpdate: ((ProfileOutboundDraft) -> ProfileOutboundDraft) -> Unit,
    onOpenPicker: (ActivePicker) -> Unit,
) {
    when (normalizedProtocol) {
        "vless", "vmess" -> {
            AddressAndPortFields(outbound, enabled, onUpdate)
            YaxcDivider()
            VisualTextField(
                label = stringResource(R.string.profileVisualUuid),
                value = outbound.userId,
                onValueChange = { value -> onUpdate { it.copy(userId = value) } },
                enabled = enabled,
                testTag = "profile-visual:uuid",
                trailingContent = {
                    IconButton(
                        onClick = { onUpdate { it.copy(userId = UUID.randomUUID().toString()) } },
                        enabled = enabled,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = stringResource(R.string.profileVisualGenerateUuid),
                        )
                    }
                },
            )
            YaxcDivider()
            if (normalizedProtocol == "vless") {
                VisualPickerRow(
                    title = stringResource(R.string.profileVisualFlow),
                    value = outbound.vlessFlow.ifBlank { stringResource(R.string.profileVisualOptionNone) },
                    enabled = enabled,
                    testTag = "profile-visual:flow",
                    onClick = { onOpenPicker(ActivePicker.VlessFlow) },
                )
                YaxcDivider()
                VisualPickerRow(
                    title = stringResource(R.string.profileVisualEncryption),
                    value = outbound.vlessEncryption.ifBlank { "none" },
                    enabled = enabled,
                    onClick = { onOpenPicker(ActivePicker.VlessEncryption) },
                )
            } else {
                VisualPickerRow(
                    title = stringResource(R.string.profileVisualVmessSecurity),
                    value = outbound.vmessSecurity.ifBlank { "auto" },
                    enabled = enabled,
                    testTag = "profile-visual:vmess-security",
                    onClick = { onOpenPicker(ActivePicker.VmessSecurity) },
                )
                YaxcDivider()
                VisualTextField(
                    label = stringResource(R.string.profileVisualAlterId),
                    value = outbound.vmessAlterId,
                    onValueChange = { value -> onUpdate { it.copy(vmessAlterId = value.filter(Char::isDigit)) } },
                    keyboardType = KeyboardType.Number,
                    enabled = enabled,
                )
            }
        }
        "trojan" -> {
            AddressAndPortFields(outbound, enabled, onUpdate)
            YaxcDivider()
            VisualPasswordField(
                label = stringResource(R.string.profileVisualPassword),
                value = outbound.password,
                onValueChange = { value -> onUpdate { it.copy(password = value) } },
                enabled = enabled,
                testTag = "profile-visual:password",
            )
        }
        "shadowsocks" -> {
            AddressAndPortFields(outbound, enabled, onUpdate)
            YaxcDivider()
            VisualPickerRow(
                title = stringResource(R.string.profileVisualMethod),
                value = outbound.ssMethod.ifBlank { "aes-256-gcm" },
                enabled = enabled,
                testTag = "profile-visual:ss-method",
                onClick = { onOpenPicker(ActivePicker.SsMethod) },
            )
            YaxcDivider()
            VisualPasswordField(
                label = stringResource(R.string.profileVisualPassword),
                value = outbound.password,
                onValueChange = { value -> onUpdate { it.copy(password = value) } },
                enabled = enabled,
                testTag = "profile-visual:password",
            )
            YaxcDivider()
            VisualSwitchRow(
                title = stringResource(R.string.profileVisualSsUot),
                checked = outbound.ssUot,
                onCheckedChange = { checked -> onUpdate { it.copy(ssUot = checked) } },
                enabled = enabled,
            )
        }
        "naive" -> {
            AddressAndPortFields(outbound, enabled, onUpdate)
            YaxcDivider()
            VisualTextField(
                label = stringResource(R.string.profileVisualUsername),
                value = outbound.username,
                onValueChange = { value -> onUpdate { it.copy(username = value) } },
                enabled = enabled,
                testTag = "profile-visual:username",
            )
            YaxcDivider()
            VisualPasswordField(
                label = stringResource(R.string.profileVisualPassword),
                value = outbound.password,
                onValueChange = { value -> onUpdate { it.copy(password = value) } },
                enabled = enabled,
                testTag = "profile-visual:password",
            )
            YaxcDivider()
            VisualSwitchRow(
                title = stringResource(R.string.profileVisualNaiveQuic),
                checked = outbound.naiveQuic,
                onCheckedChange = { checked -> onUpdate { it.copy(naiveQuic = checked) } },
                enabled = enabled,
            )
            YaxcDivider()
            VisualSwitchRow(
                title = stringResource(R.string.profileVisualNaiveUdpOverTcp),
                checked = outbound.naiveUdpOverTcp,
                onCheckedChange = { checked -> onUpdate { it.copy(naiveUdpOverTcp = checked) } },
                enabled = enabled,
            )
            if (outbound.naiveQuic || outbound.naiveCongestionControl.isNotBlank()) {
                YaxcDivider()
                VisualPickerRow(
                    title = stringResource(R.string.profileVisualNaiveCongestion),
                    value = outbound.naiveCongestionControl.ifBlank { stringResource(R.string.profileVisualOptionDefault) },
                    enabled = enabled,
                    onClick = { onOpenPicker(ActivePicker.NaiveCongestion) },
                )
            }
            YaxcDivider()
            VisualTextField(
                label = stringResource(R.string.profileVisualNaiveHeaders),
                value = outbound.naiveHeadersText,
                onValueChange = { value -> onUpdate { it.copy(naiveHeadersText = value) } },
                singleLine = false,
                minLines = 2,
                helperText = stringResource(R.string.profileVisualNaiveHeadersHint),
                enabled = enabled,
            )
        }
        "socks", "http" -> {
            AddressAndPortFields(outbound, enabled, onUpdate)
            YaxcDivider()
            VisualTextField(
                label = stringResource(R.string.profileVisualUsername),
                value = outbound.username,
                onValueChange = { value -> onUpdate { it.copy(username = value) } },
                helperText = stringResource(R.string.profileVisualPasswordOptional),
                enabled = enabled,
            )
            YaxcDivider()
            VisualPasswordField(
                label = stringResource(R.string.profileVisualPassword),
                value = outbound.password,
                onValueChange = { value -> onUpdate { it.copy(password = value) } },
                enabled = enabled,
            )
        }
        "hysteria" -> {
            AddressAndPortFields(outbound, enabled, onUpdate)
            YaxcDivider()
            VisualTextField(
                label = stringResource(R.string.profileVisualHysteriaVersion),
                value = outbound.hysteriaVersion,
                onValueChange = { value -> onUpdate { it.copy(hysteriaVersion = value.filter(Char::isDigit)) } },
                keyboardType = KeyboardType.Number,
                enabled = enabled,
            )
            YaxcDivider()
            VisualPasswordField(
                label = stringResource(R.string.profileVisualHysteriaAuth),
                value = outbound.hysteriaAuth,
                onValueChange = { value -> onUpdate { it.copy(hysteriaAuth = value) } },
                enabled = enabled,
            )
        }
        "freedom" -> {
            VisualPickerRow(
                title = stringResource(R.string.profileVisualFreedomStrategy),
                value = outbound.freedomDomainStrategy.ifBlank { stringResource(R.string.profileVisualOptionDefault) },
                enabled = enabled,
                onClick = { onOpenPicker(ActivePicker.FreedomStrategy) },
            )
            YaxcDivider()
            VisualTextField(
                label = stringResource(R.string.profileVisualFreedomRedirect),
                value = outbound.freedomRedirect,
                onValueChange = { value -> onUpdate { it.copy(freedomRedirect = value) } },
                enabled = enabled,
            )
        }
        "blackhole" -> {
            VisualPickerRow(
                title = stringResource(R.string.profileVisualBlackholeResponse),
                value = outbound.blackholeResponseType.ifBlank { "none" },
                enabled = enabled,
                onClick = { onOpenPicker(ActivePicker.BlackholeResponse) },
            )
        }
        else -> {
            Text(
                text = stringResource(R.string.profileVisualUnsupportedProtocolNotice),
                style = MaterialTheme.typography.bodyMedium,
                color = YaxcTheme.extendedColors.textMuted,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

@Composable
private fun AddressAndPortFields(
    outbound: ProfileOutboundDraft,
    enabled: Boolean,
    onUpdate: ((ProfileOutboundDraft) -> ProfileOutboundDraft) -> Unit,
) {
    VisualTextField(
        label = stringResource(R.string.profileVisualServerAddress),
        value = outbound.address,
        onValueChange = { value -> onUpdate { it.copy(address = value) } },
        enabled = enabled,
        testTag = "profile-visual:address",
    )
    YaxcDivider()
    VisualTextField(
        label = stringResource(R.string.profileVisualServerPort),
        value = outbound.port,
        onValueChange = { value -> onUpdate { it.copy(port = value.filter(Char::isDigit)) } },
        keyboardType = KeyboardType.Number,
        enabled = enabled,
        testTag = "profile-visual:port",
    )
}

@Composable
private fun TransportSpecificFields(
    outbound: ProfileOutboundDraft,
    normalizedNetwork: String,
    enabled: Boolean,
    onUpdate: ((ProfileOutboundDraft) -> ProfileOutboundDraft) -> Unit,
    onOpenPicker: (ActivePicker) -> Unit,
) {
    when (normalizedNetwork) {
        "tcp", "raw" -> {
            YaxcDivider()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(YaxcTheme.spacing.xs),
            ) {
                Text(
                    text = stringResource(R.string.profileVisualTcpHeaderType),
                    style = MaterialTheme.typography.labelLarge,
                    color = YaxcTheme.extendedColors.textMuted,
                )
                YaxcTabRow(
                    options = listOf(
                        YaxcTabOption("none", stringResource(R.string.profileVisualOptionNone)),
                        YaxcTabOption("http", "HTTP"),
                    ),
                    selected = outbound.tcpHeaderType.lowercase(),
                    onSelect = { header -> onUpdate { it.copy(tcpHeaderType = header) } },
                    enabled = enabled,
                    nested = true,
                )
            }
            if (outbound.tcpHeaderType.equals("http", ignoreCase = true)) {
                YaxcDivider()
                VisualTextField(
                    label = stringResource(R.string.profileVisualTcpHttpHost),
                    value = outbound.tcpHttpHost,
                    onValueChange = { value -> onUpdate { it.copy(tcpHttpHost = value) } },
                    enabled = enabled,
                )
                YaxcDivider()
                VisualTextField(
                    label = stringResource(R.string.profileVisualTcpHttpPath),
                    value = outbound.tcpHttpPath,
                    onValueChange = { value -> onUpdate { it.copy(tcpHttpPath = value) } },
                    enabled = enabled,
                )
            }
        }
        "ws", "websocket", "httpupgrade" -> {
            YaxcDivider()
            VisualTextField(
                label = stringResource(R.string.profileVisualTransportHost),
                value = outbound.transportHost,
                onValueChange = { value -> onUpdate { it.copy(transportHost = value) } },
                enabled = enabled,
            )
            YaxcDivider()
            VisualTextField(
                label = stringResource(R.string.profileVisualTransportPath),
                value = outbound.transportPath,
                onValueChange = { value -> onUpdate { it.copy(transportPath = value) } },
                enabled = enabled,
            )
        }
        "xhttp", "splithttp" -> {
            YaxcDivider()
            VisualPickerRow(
                title = stringResource(R.string.profileVisualXhttpMode),
                value = outbound.xhttpMode.ifBlank { stringResource(R.string.profileVisualOptionDefault) },
                enabled = enabled,
                onClick = { onOpenPicker(ActivePicker.XhttpMode) },
            )
            YaxcDivider()
            VisualTextField(
                label = stringResource(R.string.profileVisualTransportHost),
                value = outbound.transportHost,
                onValueChange = { value -> onUpdate { it.copy(transportHost = value) } },
                enabled = enabled,
            )
            YaxcDivider()
            VisualTextField(
                label = stringResource(R.string.profileVisualTransportPath),
                value = outbound.transportPath,
                onValueChange = { value -> onUpdate { it.copy(transportPath = value) } },
                enabled = enabled,
            )
        }
        "grpc", "gun" -> {
            YaxcDivider()
            VisualTextField(
                label = stringResource(R.string.profileVisualGrpcServiceName),
                value = outbound.transportPath,
                onValueChange = { value -> onUpdate { it.copy(transportPath = value) } },
                enabled = enabled,
            )
            YaxcDivider()
            VisualTextField(
                label = stringResource(R.string.profileVisualTransportHost),
                value = outbound.transportHost,
                onValueChange = { value -> onUpdate { it.copy(transportHost = value) } },
                enabled = enabled,
            )
            YaxcDivider()
            VisualSwitchRow(
                title = stringResource(R.string.profileVisualGrpcMultiMode),
                checked = outbound.grpcMultiMode,
                onCheckedChange = { checked -> onUpdate { it.copy(grpcMultiMode = checked) } },
                enabled = enabled,
            )
        }
    }
}

@Composable
private fun PickerDialogHost(
    picker: ActivePicker,
    visualConfig: ProfileVisualConfig,
    outbound: ProfileOutboundDraft,
    onDismiss: () -> Unit,
    onVisualConfigChange: ((ProfileVisualConfig) -> ProfileVisualConfig) -> Unit,
) {
    val noneLabel = stringResource(R.string.profileVisualOptionNone)
    val defaultLabel = stringResource(R.string.profileVisualOptionDefault)
    when (picker) {
        ActivePicker.OutboundIndex -> {
            ProfileOptionPickerDialog(
                title = stringResource(R.string.profileVisualActiveOutbound),
                selectedValue = visualConfig.selectedOutboundIndex.toString(),
                options = visualConfig.outbounds.mapIndexed { index, item ->
                    PickerChoice(index.toString(), outboundSummary(index, item))
                },
                allowCustom = false,
                onSelect = { selected ->
                    selected.toIntOrNull()?.let { index ->
                        onVisualConfigChange { it.selectOutbound(index) }
                    }
                    onDismiss()
                },
                onDismiss = onDismiss,
            )
        }
        ActivePicker.Protocol -> {
            ProfileOptionPickerDialog(
                title = stringResource(R.string.profileVisualProtocol),
                selectedValue = outbound.protocol.lowercase(),
                options = ProfileVisualOptions.protocols.map { PickerChoice(it, displayProtocol(it)) },
                allowCustom = true,
                onSelect = { value ->
                    onVisualConfigChange { config ->
                        config.updateActiveOutbound { it.withProtocol(value.trim()) }
                    }
                    onDismiss()
                },
                onDismiss = onDismiss,
            )
        }
        ActivePicker.OutboundTag -> {
            ProfileOptionPickerDialog(
                title = stringResource(R.string.profileVisualOutboundTag),
                selectedValue = outbound.tag,
                options = ProfileVisualOptions.outboundTags.map { PickerChoice(it, it) },
                allowCustom = true,
                onSelect = { value ->
                    onVisualConfigChange { it.updateActiveOutbound { cur -> cur.copy(tag = value.trim()) } }
                    onDismiss()
                },
                onDismiss = onDismiss,
            )
        }
        ActivePicker.VlessFlow -> {
            ProfileOptionPickerDialog(
                title = stringResource(R.string.profileVisualFlow),
                selectedValue = outbound.vlessFlow,
                options = ProfileVisualOptions.vlessFlows.map { PickerChoice(it, it.ifBlank { noneLabel }) },
                allowCustom = true,
                onSelect = { value ->
                    onVisualConfigChange { it.updateActiveOutbound { cur -> cur.copy(vlessFlow = value.trim()) } }
                    onDismiss()
                },
                onDismiss = onDismiss,
            )
        }
        ActivePicker.VlessEncryption -> {
            ProfileOptionPickerDialog(
                title = stringResource(R.string.profileVisualEncryption),
                selectedValue = outbound.vlessEncryption,
                options = ProfileVisualOptions.vlessEncryptions.map { PickerChoice(it, it) },
                allowCustom = true,
                onSelect = { value ->
                    onVisualConfigChange { it.updateActiveOutbound { cur -> cur.copy(vlessEncryption = value.trim().ifBlank { "none" }) } }
                    onDismiss()
                },
                onDismiss = onDismiss,
            )
        }
        ActivePicker.VmessSecurity -> {
            ProfileOptionPickerDialog(
                title = stringResource(R.string.profileVisualVmessSecurity),
                selectedValue = outbound.vmessSecurity,
                options = ProfileVisualOptions.vmessSecurities.map { PickerChoice(it, it) },
                allowCustom = true,
                onSelect = { value ->
                    onVisualConfigChange { it.updateActiveOutbound { cur -> cur.copy(vmessSecurity = value.trim()) } }
                    onDismiss()
                },
                onDismiss = onDismiss,
            )
        }
        ActivePicker.SsMethod -> {
            ProfileOptionPickerDialog(
                title = stringResource(R.string.profileVisualMethod),
                selectedValue = outbound.ssMethod,
                options = ProfileVisualOptions.shadowsocksMethods.map { PickerChoice(it, it) },
                allowCustom = true,
                onSelect = { value ->
                    onVisualConfigChange { it.updateActiveOutbound { cur -> cur.copy(ssMethod = value.trim()) } }
                    onDismiss()
                },
                onDismiss = onDismiss,
            )
        }
        ActivePicker.NaiveCongestion -> {
            ProfileOptionPickerDialog(
                title = stringResource(R.string.profileVisualNaiveCongestion),
                selectedValue = outbound.naiveCongestionControl,
                options = ProfileVisualOptions.naiveCongestionControls.map { PickerChoice(it, it.ifBlank { defaultLabel }) },
                allowCustom = true,
                onSelect = { value ->
                    onVisualConfigChange { it.updateActiveOutbound { cur -> cur.copy(naiveCongestionControl = value.trim()) } }
                    onDismiss()
                },
                onDismiss = onDismiss,
            )
        }
        ActivePicker.FreedomStrategy -> {
            ProfileOptionPickerDialog(
                title = stringResource(R.string.profileVisualFreedomStrategy),
                selectedValue = outbound.freedomDomainStrategy,
                options = ProfileVisualOptions.freedomDomainStrategies.map { PickerChoice(it, it.ifBlank { defaultLabel }) },
                allowCustom = false,
                onSelect = { value ->
                    onVisualConfigChange { it.updateActiveOutbound { cur -> cur.copy(freedomDomainStrategy = value) } }
                    onDismiss()
                },
                onDismiss = onDismiss,
            )
        }
        ActivePicker.BlackholeResponse -> {
            ProfileOptionPickerDialog(
                title = stringResource(R.string.profileVisualBlackholeResponse),
                selectedValue = outbound.blackholeResponseType,
                options = ProfileVisualOptions.blackholeResponseTypes.map { PickerChoice(it, it) },
                allowCustom = false,
                onSelect = { value ->
                    onVisualConfigChange { it.updateActiveOutbound { cur -> cur.copy(blackholeResponseType = value) } }
                    onDismiss()
                },
                onDismiss = onDismiss,
            )
        }
        ActivePicker.Network -> {
            ProfileOptionPickerDialog(
                title = stringResource(R.string.profileVisualNetwork),
                selectedValue = outbound.network.lowercase(),
                options = ProfileVisualOptions.networks.map { PickerChoice(it, it.uppercase()) },
                allowCustom = true,
                onSelect = { value ->
                    onVisualConfigChange { it.updateActiveOutbound { cur -> cur.copy(network = value.trim()) } }
                    onDismiss()
                },
                onDismiss = onDismiss,
            )
        }
        ActivePicker.XhttpMode -> {
            ProfileOptionPickerDialog(
                title = stringResource(R.string.profileVisualXhttpMode),
                selectedValue = outbound.xhttpMode,
                options = ProfileVisualOptions.xhttpModes.map { PickerChoice(it, it.ifBlank { defaultLabel }) },
                allowCustom = true,
                onSelect = { value ->
                    onVisualConfigChange { it.updateActiveOutbound { cur -> cur.copy(xhttpMode = value.trim()) } }
                    onDismiss()
                },
                onDismiss = onDismiss,
            )
        }
        ActivePicker.Fingerprint -> {
            ProfileOptionPickerDialog(
                title = stringResource(R.string.profileVisualFingerprint),
                selectedValue = outbound.fingerprint,
                options = ProfileVisualOptions.fingerprints.map { PickerChoice(it, it.ifBlank { noneLabel }) },
                allowCustom = true,
                onSelect = { value ->
                    onVisualConfigChange { it.updateActiveOutbound { cur -> cur.copy(fingerprint = value.trim()) } }
                    onDismiss()
                },
                onDismiss = onDismiss,
            )
        }
        ActivePicker.Alpn -> {
            ProfileOptionPickerDialog(
                title = stringResource(R.string.profileVisualAlpn),
                selectedValue = outbound.alpn,
                options = ProfileVisualOptions.alpnOptions.map { PickerChoice(it, it.ifBlank { defaultLabel }) },
                allowCustom = true,
                onSelect = { value ->
                    onVisualConfigChange { it.updateActiveOutbound { cur -> cur.copy(alpn = value.trim()) } }
                    onDismiss()
                },
                onDismiss = onDismiss,
            )
        }
    }
}

@Composable
private fun ProfileOptionPickerDialog(
    title: String,
    selectedValue: String,
    options: List<PickerChoice>,
    allowCustom: Boolean,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val isKnown = options.any { it.value == selectedValue }
    var customExpanded by remember { mutableStateOf(allowCustom && !isKnown) }
    var customValue by remember(selectedValue) { mutableStateOf(if (!isKnown) selectedValue else "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                options.forEach { option ->
                    ProfileDialogOption(
                        title = option.label,
                        selected = !customExpanded && option.value == selectedValue,
                        onClick = { onSelect(option.value) },
                    )
                }
                if (allowCustom) {
                    ProfileDialogOption(
                        title = stringResource(R.string.profileVisualOptionCustom),
                        selected = customExpanded,
                        onClick = { customExpanded = true },
                    )
                    if (customExpanded) {
                        OutlinedTextField(
                            value = customValue,
                            onValueChange = { customValue = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(text = stringResource(R.string.profileVisualCustomValueLabel)) },
                            singleLine = true,
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (customExpanded) {
                TextButton(onClick = { onSelect(customValue) }) {
                    Text(text = stringResource(R.string.profileVisualApplyCustom))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.close))
            }
        },
    )
}

@Composable
internal fun ProfileDialogOption(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val optionContentColor = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.78f)
        } else {
            MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)
        },
        contentColor = optionContentColor,
        shape = MaterialTheme.shapes.large,
        onClick = onClick,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = optionContentColor,
            modifier = Modifier.padding(YaxcTheme.paddings.section),
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = YaxcTheme.extendedColors.textMuted,
        modifier = Modifier.padding(horizontal = YaxcTheme.spacing.xs),
    )
}

@Composable
private fun VisualPickerRow(
    title: String,
    value: String,
    enabled: Boolean,
    testTag: String? = null,
    onClick: () -> Unit,
) {
    YaxcSettingsRow(
        title = title,
        onClick = onClick,
        enabled = enabled,
        shape = RectangleShape,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        modifier = testTag?.let { Modifier.testTag(it) } ?: Modifier,
        trailing = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = YaxcTheme.extendedColors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    imageVector = Icons.Outlined.KeyboardArrowDown,
                    contentDescription = null,
                    tint = YaxcTheme.extendedColors.textMuted,
                )
            }
        },
    )
}

@Composable
private fun VisualSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean,
) {
    YaxcSwitchRow(
        title = title,
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        shape = RectangleShape,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
    )
}

@Composable
private fun VisualTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    keyboardType: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
    minLines: Int = 1,
    helperText: String? = null,
    testTag: String? = null,
    trailingContent: @Composable (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(YaxcTheme.spacing.xs),
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .let { base -> if (testTag != null) base.testTag(testTag) else base },
            label = { Text(text = label) },
            singleLine = singleLine,
            minLines = minLines,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            trailingIcon = trailingContent,
        )
        helperText?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = YaxcTheme.extendedColors.textMuted,
            )
        }
    }
}

@Composable
private fun VisualPasswordField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    testTag: String? = null,
) {
    var visible by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .let { base -> if (testTag != null) base.testTag(testTag) else base },
            label = { Text(text = label) },
            singleLine = true,
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { visible = !visible }, enabled = enabled) {
                    Icon(
                        imageVector = if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription = null,
                    )
                }
            },
        )
    }
}

private fun outboundSummary(index: Int, outbound: ProfileOutboundDraft): String {
    val label = outbound.tag.ifBlank { "#${index + 1}" }
    return "$label • ${displayProtocol(outbound.protocol)}"
}

private fun displayProtocol(protocol: String): String = when (protocol.trim().lowercase()) {
    "vless" -> "VLESS"
    "vmess" -> "VMess"
    "trojan" -> "Trojan"
    "shadowsocks" -> "Shadowsocks"
    "naive" -> "NaiveProxy"
    "socks" -> "SOCKS"
    "http" -> "HTTP"
    "hysteria" -> "Hysteria2"
    "freedom" -> "Direct (Freedom)"
    "blackhole" -> "Block (Blackhole)"
    "dns" -> "DNS"
    else -> protocol.ifBlank { "VLESS" }
}
