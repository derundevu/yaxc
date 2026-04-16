package io.github.derundevu.yaxc.presentation.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.SettingsEthernet
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.derundevu.yaxc.R
import io.github.derundevu.yaxc.Settings
import io.github.derundevu.yaxc.presentation.designsystem.YaxcTheme
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcCard
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcScaffold
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcSettingsRow
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcSwitchRow

@Immutable
data class SettingsFormState(
    val socksAddress: String,
    val socksPort: String,
    val socksUsername: String,
    val socksPassword: String,
    val geoIpAddress: String,
    val geoSiteAddress: String,
    val pingAddress: String,
    val pingTimeout: String,
    val refreshLinksInterval: String,
    val bypassLan: Boolean,
    val enableIpV6: Boolean,
    val socksUdp: Boolean,
    val tun2socks: Boolean,
    val bootAutoStart: Boolean,
    val refreshLinksOnOpen: Boolean,
    val primaryDns: String,
    val secondaryDns: String,
    val primaryDnsV6: String,
    val secondaryDnsV6: String,
    val tunName: String,
    val tunMtu: String,
    val tunAddress: String,
    val tunPrefix: String,
    val tunAddressV6: String,
    val tunPrefixV6: String,
    val hotspotInterface: String,
    val tetheringInterface: String,
    val tproxyAddress: String,
    val tproxyPort: String,
    val tproxyBypassWiFi: String,
    val tproxyAutoConnect: Boolean,
    val tproxyHotspot: Boolean,
    val tproxyTethering: Boolean,
    val transparentProxy: Boolean,
    val languageTag: String,
) {
    companion object {
        val Saver: Saver<SettingsFormState, Any> = listSaver(
            save = {
                listOf(
                    it.socksAddress,
                    it.socksPort,
                    it.socksUsername,
                    it.socksPassword,
                    it.geoIpAddress,
                    it.geoSiteAddress,
                    it.pingAddress,
                    it.pingTimeout,
                    it.refreshLinksInterval,
                    it.bypassLan,
                    it.enableIpV6,
                    it.socksUdp,
                    it.tun2socks,
                    it.bootAutoStart,
                    it.refreshLinksOnOpen,
                    it.primaryDns,
                    it.secondaryDns,
                    it.primaryDnsV6,
                    it.secondaryDnsV6,
                    it.tunName,
                    it.tunMtu,
                    it.tunAddress,
                    it.tunPrefix,
                    it.tunAddressV6,
                    it.tunPrefixV6,
                    it.hotspotInterface,
                    it.tetheringInterface,
                    it.tproxyAddress,
                    it.tproxyPort,
                    it.tproxyBypassWiFi,
                    it.tproxyAutoConnect,
                    it.tproxyHotspot,
                    it.tproxyTethering,
                    it.transparentProxy,
                    it.languageTag,
                )
            },
            restore = { values ->
                SettingsFormState(
                    socksAddress = values[0] as String,
                    socksPort = values[1] as String,
                    socksUsername = values[2] as String,
                    socksPassword = values[3] as String,
                    geoIpAddress = values[4] as String,
                    geoSiteAddress = values[5] as String,
                    pingAddress = values[6] as String,
                    pingTimeout = values[7] as String,
                    refreshLinksInterval = values[8] as String,
                    bypassLan = values[9] as Boolean,
                    enableIpV6 = values[10] as Boolean,
                    socksUdp = values[11] as Boolean,
                    tun2socks = values[12] as Boolean,
                    bootAutoStart = values[13] as Boolean,
                    refreshLinksOnOpen = values[14] as Boolean,
                    primaryDns = values[15] as String,
                    secondaryDns = values[16] as String,
                    primaryDnsV6 = values[17] as String,
                    secondaryDnsV6 = values[18] as String,
                    tunName = values[19] as String,
                    tunMtu = values[20] as String,
                    tunAddress = values[21] as String,
                    tunPrefix = values[22] as String,
                    tunAddressV6 = values[23] as String,
                    tunPrefixV6 = values[24] as String,
                    hotspotInterface = values[25] as String,
                    tetheringInterface = values[26] as String,
                    tproxyAddress = values[27] as String,
                    tproxyPort = values[28] as String,
                    tproxyBypassWiFi = values[29] as String,
                    tproxyAutoConnect = values[30] as Boolean,
                    tproxyHotspot = values[31] as Boolean,
                    tproxyTethering = values[32] as Boolean,
                    transparentProxy = values[33] as Boolean,
                    languageTag = values.getOrNull(34) as? String ?: "en",
                )
            },
        )

        fun from(settings: Settings) = SettingsFormState(
            socksAddress = settings.socksAddress,
            socksPort = settings.socksPort,
            socksUsername = settings.socksUsername,
            socksPassword = settings.socksPassword,
            geoIpAddress = settings.geoIpAddress,
            geoSiteAddress = settings.geoSiteAddress,
            pingAddress = settings.pingAddress,
            pingTimeout = settings.pingTimeout.toString(),
            refreshLinksInterval = settings.refreshLinksInterval.toString(),
            bypassLan = settings.bypassLan,
            enableIpV6 = settings.enableIpV6,
            socksUdp = settings.socksUdp,
            tun2socks = settings.tun2socks,
            bootAutoStart = settings.bootAutoStart,
            refreshLinksOnOpen = settings.refreshLinksOnOpen,
            primaryDns = settings.primaryDns,
            secondaryDns = settings.secondaryDns,
            primaryDnsV6 = settings.primaryDnsV6,
            secondaryDnsV6 = settings.secondaryDnsV6,
            tunName = settings.tunName,
            tunMtu = settings.tunMtu.toString(),
            tunAddress = settings.tunAddress,
            tunPrefix = settings.tunPrefix.toString(),
            tunAddressV6 = settings.tunAddressV6,
            tunPrefixV6 = settings.tunPrefixV6.toString(),
            hotspotInterface = settings.hotspotInterface,
            tetheringInterface = settings.tetheringInterface,
            tproxyAddress = settings.tproxyAddress,
            tproxyPort = settings.tproxyPort.toString(),
            tproxyBypassWiFi = settings.tproxyBypassWiFi.joinToString(", "),
            tproxyAutoConnect = settings.tproxyAutoConnect,
            tproxyHotspot = settings.tproxyHotspot,
            tproxyTethering = settings.tproxyTethering,
            transparentProxy = settings.transparentProxy,
            languageTag = settings.languageTag,
        )
    }
}

private enum class SettingsTab(val titleRes: Int) {
    Basic(R.string.settingsTabBasic),
    Advanced(R.string.settingsTabAdvanced),
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun SettingsScreen(
    formState: SettingsFormState,
    tunRoutes: List<String>,
    onFormStateChange: (SettingsFormState) -> Unit,
    onTunRoutesSave: (List<String>) -> Unit,
    onBack: () -> Unit,
    onSave: () -> Unit,
) {
    val spacing = YaxcTheme.spacing
    var selectedTab by rememberSaveable { mutableStateOf(SettingsTab.Basic) }
    var showTunRoutesDialog by rememberSaveable { mutableStateOf(false) }
    var showLanguageDialog by rememberSaveable { mutableStateOf(false) }
    var tunRoutesDraft by rememberSaveable { mutableStateOf(tunRoutes.joinToString("\n")) }

    if (showTunRoutesDialog) {
        AlertDialog(
            onDismissRequest = { showTunRoutesDialog = false },
            title = { Text(text = textResource(R.string.tunRoutes)) },
            text = {
                OutlinedTextField(
                    value = tunRoutesDraft,
                    onValueChange = { tunRoutesDraft = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 8,
                    maxLines = 12,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val routes = tunRoutesDraft.lines()
                            .map(String::trim)
                            .filter(String::isNotBlank)
                        onTunRoutesSave(routes)
                        showTunRoutesDialog = false
                    }
                ) {
                    Text(text = textResource(R.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showTunRoutesDialog = false }) {
                    Text(text = textResource(R.string.close))
                }
            },
            icon = {
                Icon(
                    imageVector = Icons.Outlined.Tune,
                    contentDescription = null,
                )
            },
        )
    }

    if (showLanguageDialog) {
        AlertDialog(
            onDismissRequest = { showLanguageDialog = false },
            title = { Text(text = textResource(R.string.settingsLanguageTitle)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LanguageOptionRow(
                        title = textResource(R.string.settingsLanguageEnglish),
                        selected = formState.languageTag == "en",
                        onClick = {
                            onFormStateChange(formState.copy(languageTag = "en"))
                            showLanguageDialog = false
                        },
                    )
                    LanguageOptionRow(
                        title = textResource(R.string.settingsLanguageRussian),
                        selected = formState.languageTag == "ru",
                        onClick = {
                            onFormStateChange(formState.copy(languageTag = "ru"))
                            showLanguageDialog = false
                        },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showLanguageDialog = false }) {
                    Text(text = textResource(R.string.close))
                }
            },
        )
    }

    YaxcScaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = textResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = null,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onSave) {
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
                text = textResource(R.string.settingsScreenLead),
                style = MaterialTheme.typography.bodyLarge,
                color = YaxcTheme.extendedColors.textMuted,
            )

            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth(),
            ) {
                TabRow(selectedTabIndex = selectedTab.ordinal) {
                    SettingsTab.entries.forEach { tab ->
                        Tab(
                            selected = selectedTab == tab,
                            onClick = { selectedTab = tab },
                            text = { Text(text = textResource(tab.titleRes)) },
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(spacing.lg),
            ) {
                when (selectedTab) {
                    SettingsTab.Basic -> BasicSettingsTab(
                        formState = formState,
                        onFormStateChange = onFormStateChange,
                        onLanguageClick = { showLanguageDialog = true },
                    )

                    SettingsTab.Advanced -> AdvancedSettingsTab(
                        formState = formState,
                        tunRoutes = tunRoutes,
                        onFormStateChange = onFormStateChange,
                        onTunRoutesClick = {
                            tunRoutesDraft = tunRoutes.joinToString("\n")
                            showTunRoutesDialog = true
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun BasicSettingsTab(
    formState: SettingsFormState,
    onFormStateChange: (SettingsFormState) -> Unit,
    onLanguageClick: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(YaxcTheme.spacing.lg)) {
        SettingsSection(title = textResource(R.string.settingsLanguageSection))
        YaxcCard {
            YaxcSettingsRow(
                title = textResource(R.string.settingsLanguageTitle),
                subtitle = textResource(R.string.settingsLanguageLead),
                value = textResource(
                    if (formState.languageTag == "ru") {
                        R.string.settingsLanguageOptionRuShort
                    } else {
                        R.string.settingsLanguageOptionEnShort
                    }
                ),
                icon = Icons.Outlined.Language,
                onClick = onLanguageClick,
            )
        }

        SettingsSection(title = textResource(R.string.settingsSectionConnection))
        YaxcCard {
            SettingsTextField(
                label = textResource(R.string.socksAddress),
                value = formState.socksAddress,
                onValueChange = { onFormStateChange(formState.copy(socksAddress = it)) },
            )
            SettingsDivider()
            SettingsTextField(
                label = textResource(R.string.socksPort),
                value = formState.socksPort,
                onValueChange = { onFormStateChange(formState.copy(socksPort = it)) },
                keyboardType = KeyboardType.Number,
            )
            SettingsDivider()
            SettingsTextField(
                label = textResource(R.string.socksUsername),
                value = formState.socksUsername,
                onValueChange = { onFormStateChange(formState.copy(socksUsername = it)) },
            )
            SettingsDivider()
            SettingsTextField(
                label = textResource(R.string.socksPassword),
                value = formState.socksPassword,
                onValueChange = { onFormStateChange(formState.copy(socksPassword = it)) },
                keyboardType = KeyboardType.Password,
                isPassword = true,
            )
        }

        SettingsSection(title = textResource(R.string.settingsSectionResources))
        YaxcCard {
            SettingsTextField(
                label = textResource(R.string.geoIpAddress),
                value = formState.geoIpAddress,
                onValueChange = { onFormStateChange(formState.copy(geoIpAddress = it)) },
            )
            SettingsDivider()
            SettingsTextField(
                label = textResource(R.string.geoSiteAddress),
                value = formState.geoSiteAddress,
                onValueChange = { onFormStateChange(formState.copy(geoSiteAddress = it)) },
            )
            SettingsDivider()
            SettingsTextField(
                label = textResource(R.string.pingAddress),
                value = formState.pingAddress,
                onValueChange = { onFormStateChange(formState.copy(pingAddress = it)) },
            )
            SettingsDivider()
            SettingsTextField(
                label = textResource(R.string.pingTimeout),
                value = formState.pingTimeout,
                onValueChange = { onFormStateChange(formState.copy(pingTimeout = it)) },
                keyboardType = KeyboardType.Number,
            )
            SettingsDivider()
            SettingsTextField(
                label = textResource(R.string.refreshLinksInterval),
                value = formState.refreshLinksInterval,
                onValueChange = { onFormStateChange(formState.copy(refreshLinksInterval = it)) },
                keyboardType = KeyboardType.Number,
            )
        }

        SettingsSection(title = textResource(R.string.settingsSectionBehavior))
        YaxcCard {
            YaxcSwitchRow(
                title = textResource(R.string.bypassLan),
                checked = formState.bypassLan,
                onCheckedChange = { onFormStateChange(formState.copy(bypassLan = it)) },
                icon = Icons.Outlined.Tune,
            )
            SettingsDivider()
            YaxcSwitchRow(
                title = textResource(R.string.enableIpV6),
                checked = formState.enableIpV6,
                onCheckedChange = { onFormStateChange(formState.copy(enableIpV6 = it)) },
                icon = Icons.Outlined.Public,
            )
            SettingsDivider()
            YaxcSwitchRow(
                title = textResource(R.string.socksUdp),
                checked = formState.socksUdp,
                onCheckedChange = { onFormStateChange(formState.copy(socksUdp = it)) },
                icon = Icons.Outlined.Sync,
            )
            SettingsDivider()
            YaxcSwitchRow(
                title = textResource(R.string.tun2socks),
                checked = formState.tun2socks,
                onCheckedChange = { onFormStateChange(formState.copy(tun2socks = it)) },
                icon = Icons.Outlined.SettingsEthernet,
            )
            SettingsDivider()
            YaxcSwitchRow(
                title = textResource(R.string.bootAutoStart),
                checked = formState.bootAutoStart,
                onCheckedChange = { onFormStateChange(formState.copy(bootAutoStart = it)) },
                icon = Icons.Outlined.Save,
            )
            SettingsDivider()
            YaxcSwitchRow(
                title = textResource(R.string.refreshLinksOnOpen),
                checked = formState.refreshLinksOnOpen,
                onCheckedChange = {
                    onFormStateChange(formState.copy(refreshLinksOnOpen = it))
                },
                icon = Icons.Outlined.Sync,
            )
        }
    }
}

@Composable
private fun AdvancedSettingsTab(
    formState: SettingsFormState,
    tunRoutes: List<String>,
    onFormStateChange: (SettingsFormState) -> Unit,
    onTunRoutesClick: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(YaxcTheme.spacing.lg)) {
        SettingsSection(title = textResource(R.string.settingsSectionDns))
        YaxcCard {
            SettingsTextField(
                label = textResource(R.string.primaryDns),
                value = formState.primaryDns,
                onValueChange = { onFormStateChange(formState.copy(primaryDns = it)) },
            )
            SettingsDivider()
            SettingsTextField(
                label = textResource(R.string.secondaryDns),
                value = formState.secondaryDns,
                onValueChange = { onFormStateChange(formState.copy(secondaryDns = it)) },
            )
            SettingsDivider()
            SettingsTextField(
                label = textResource(R.string.primaryDnsV6),
                value = formState.primaryDnsV6,
                onValueChange = { onFormStateChange(formState.copy(primaryDnsV6 = it)) },
            )
            SettingsDivider()
            SettingsTextField(
                label = textResource(R.string.secondaryDnsV6),
                value = formState.secondaryDnsV6,
                onValueChange = { onFormStateChange(formState.copy(secondaryDnsV6 = it)) },
            )
        }

        SettingsSection(title = textResource(R.string.settingsSectionTunnel))
        YaxcCard {
            SettingsTextField(
                label = textResource(R.string.tunName),
                value = formState.tunName,
                onValueChange = { onFormStateChange(formState.copy(tunName = it)) },
            )
            SettingsDivider()
            SettingsTextField(
                label = textResource(R.string.tunMtu),
                value = formState.tunMtu,
                onValueChange = { onFormStateChange(formState.copy(tunMtu = it)) },
                keyboardType = KeyboardType.Number,
            )
            SettingsDivider()
            SettingsTextField(
                label = textResource(R.string.tunAddress),
                value = formState.tunAddress,
                onValueChange = { onFormStateChange(formState.copy(tunAddress = it)) },
            )
            SettingsDivider()
            SettingsTextField(
                label = textResource(R.string.tunPrefix),
                value = formState.tunPrefix,
                onValueChange = { onFormStateChange(formState.copy(tunPrefix = it)) },
                keyboardType = KeyboardType.Number,
            )
            SettingsDivider()
            SettingsTextField(
                label = textResource(R.string.tunAddressV6),
                value = formState.tunAddressV6,
                onValueChange = { onFormStateChange(formState.copy(tunAddressV6 = it)) },
            )
            SettingsDivider()
            SettingsTextField(
                label = textResource(R.string.tunPrefixV6),
                value = formState.tunPrefixV6,
                onValueChange = { onFormStateChange(formState.copy(tunPrefixV6 = it)) },
                keyboardType = KeyboardType.Number,
            )
        }

        SettingsSection(title = textResource(R.string.settingsSectionInterfaces))
        YaxcCard {
            SettingsTextField(
                label = textResource(R.string.hotspotInterface),
                value = formState.hotspotInterface,
                onValueChange = { onFormStateChange(formState.copy(hotspotInterface = it)) },
            )
            SettingsDivider()
            SettingsTextField(
                label = textResource(R.string.tetheringInterface),
                value = formState.tetheringInterface,
                onValueChange = { onFormStateChange(formState.copy(tetheringInterface = it)) },
            )
        }

        SettingsSection(title = textResource(R.string.settingsSectionTransparentProxy))
        YaxcCard {
            SettingsTextField(
                label = textResource(R.string.tproxyAddress),
                value = formState.tproxyAddress,
                onValueChange = { onFormStateChange(formState.copy(tproxyAddress = it)) },
            )
            SettingsDivider()
            SettingsTextField(
                label = textResource(R.string.tproxyPort),
                value = formState.tproxyPort,
                onValueChange = { onFormStateChange(formState.copy(tproxyPort = it)) },
                keyboardType = KeyboardType.Number,
            )
            SettingsDivider()
            SettingsTextField(
                label = textResource(R.string.tproxyBypassWiFi),
                value = formState.tproxyBypassWiFi,
                onValueChange = { onFormStateChange(formState.copy(tproxyBypassWiFi = it)) },
            )
            SettingsDivider()
            YaxcSwitchRow(
                title = textResource(R.string.tproxyAutoConnect),
                checked = formState.tproxyAutoConnect,
                onCheckedChange = {
                    onFormStateChange(formState.copy(tproxyAutoConnect = it))
                },
                icon = Icons.Outlined.Speed,
            )
            SettingsDivider()
            YaxcSwitchRow(
                title = textResource(R.string.tproxyHotspot),
                checked = formState.tproxyHotspot,
                onCheckedChange = { onFormStateChange(formState.copy(tproxyHotspot = it)) },
                icon = Icons.Outlined.Public,
            )
            SettingsDivider()
            YaxcSwitchRow(
                title = textResource(R.string.tproxyTethering),
                checked = formState.tproxyTethering,
                onCheckedChange = {
                    onFormStateChange(formState.copy(tproxyTethering = it))
                },
                icon = Icons.Outlined.Sync,
            )
            SettingsDivider()
            YaxcSwitchRow(
                title = textResource(R.string.transparentProxy),
                checked = formState.transparentProxy,
                onCheckedChange = {
                    onFormStateChange(formState.copy(transparentProxy = it))
                },
                icon = Icons.Outlined.Security,
            )
        }

        SettingsSection(title = textResource(R.string.settingsSectionRoutes))
        YaxcCard {
            YaxcSettingsRow(
                title = textResource(R.string.tunRoutes),
                subtitle = if (tunRoutes.isEmpty()) {
                    textResource(R.string.noTunRoutesConfigured)
                } else {
                    textResource(R.string.settingsRoutesConfigured, tunRoutes.size)
                },
                value = textResource(R.string.configure),
                icon = Icons.Outlined.Tune,
                onClick = onTunRoutesClick,
            )
        }
    }
}

@Composable
private fun SettingsSection(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = YaxcTheme.extendedColors.textMuted,
        modifier = Modifier.padding(top = 4.dp, start = 4.dp),
    )
}

@Composable
private fun SettingsTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    isPassword: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        label = { Text(text = label) },
        singleLine = !isPassword,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        visualTransformation = if (isPassword) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
    )
}

@Composable
private fun SettingsDivider() {
    Divider(
        modifier = Modifier.padding(vertical = 2.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
    )
}

@Composable
private fun LanguageOptionRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = title,
            modifier = Modifier.fillMaxWidth(),
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
    }
}

@Composable
private fun textResource(id: Int): String {
    return androidx.compose.ui.res.stringResource(id)
}

@Composable
private fun textResource(id: Int, arg0: Int): String {
    return androidx.compose.ui.res.stringResource(id, arg0)
}
