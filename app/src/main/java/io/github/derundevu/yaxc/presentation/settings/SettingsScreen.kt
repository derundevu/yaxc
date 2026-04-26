package io.github.derundevu.yaxc.presentation.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.SettingsEthernet
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.derundevu.yaxc.R
import io.github.derundevu.yaxc.Settings
import io.github.derundevu.yaxc.presentation.designsystem.YaxcTheme
import io.github.derundevu.yaxc.presentation.designsystem.YaxcThemeStyle
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
    val userAgent: String,
    val xHwid: String,
    val pingAddress: String,
    val pingType: String,
    val pingTimeout: String,
    val refreshLinksInterval: String,
    val bypassLan: Boolean,
    val enableIpV6: Boolean,
    val socksUdp: Boolean,
    val tun2socks: Boolean,
    val tunOwnerDefense: Boolean,
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
    val themeStyle: String,
) {
    companion object {
        val Saver: Saver<SettingsFormState, Any> = listSaver(
            save = {
                listOf(
                    it.socksAddress,
                    it.socksPort,
                    it.socksUsername,
                    it.socksPassword,
                    it.userAgent,
                    it.pingAddress,
                    it.pingType,
                    it.pingTimeout,
                    it.refreshLinksInterval,
                    it.bypassLan,
                    it.enableIpV6,
                    it.socksUdp,
                    it.tun2socks,
                    it.tunOwnerDefense,
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
                    it.themeStyle,
                    it.xHwid,
                )
            },
            restore = { values ->
                SettingsFormState(
                    socksAddress = values[0] as String,
                    socksPort = values[1] as String,
                    socksUsername = values[2] as String,
                    socksPassword = values[3] as String,
                    userAgent = values[4] as String,
                    xHwid = values.getOrNull(37) as? String ?: "",
                    pingAddress = values[5] as String,
                    pingType = values.getOrNull(6) as? String ?: Settings.PingType.Get.value,
                    pingTimeout = values[7] as String,
                    refreshLinksInterval = values[8] as String,
                    bypassLan = values[9] as Boolean,
                    enableIpV6 = values[10] as Boolean,
                    socksUdp = values[11] as Boolean,
                    tun2socks = values[12] as Boolean,
                    tunOwnerDefense = values.getOrNull(13) as? Boolean ?: false,
                    bootAutoStart = values.getOrNull(14) as? Boolean ?: false,
                    refreshLinksOnOpen = values.getOrNull(15) as? Boolean ?: false,
                    primaryDns = values.getOrNull(16) as? String ?: "",
                    secondaryDns = values.getOrNull(17) as? String ?: "",
                    primaryDnsV6 = values.getOrNull(18) as? String ?: "",
                    secondaryDnsV6 = values.getOrNull(19) as? String ?: "",
                    tunName = values.getOrNull(20) as? String ?: "",
                    tunMtu = values.getOrNull(21) as? String ?: "",
                    tunAddress = values.getOrNull(22) as? String ?: "",
                    tunPrefix = values.getOrNull(23) as? String ?: "",
                    tunAddressV6 = values.getOrNull(24) as? String ?: "",
                    tunPrefixV6 = values.getOrNull(25) as? String ?: "",
                    hotspotInterface = values.getOrNull(26) as? String ?: "",
                    tetheringInterface = values.getOrNull(27) as? String ?: "",
                    tproxyAddress = values.getOrNull(28) as? String ?: "",
                    tproxyPort = values.getOrNull(29) as? String ?: "",
                    tproxyBypassWiFi = values.getOrNull(30) as? String ?: "",
                    tproxyAutoConnect = values.getOrNull(31) as? Boolean ?: false,
                    tproxyHotspot = values.getOrNull(32) as? Boolean ?: false,
                    tproxyTethering = values.getOrNull(33) as? Boolean ?: false,
                    transparentProxy = values.getOrNull(34) as? Boolean ?: false,
                    languageTag = values.getOrNull(35) as? String ?: "system",
                    themeStyle = values.getOrNull(36) as? String ?: io.github.derundevu.yaxc.presentation.designsystem.YaxcThemeStyle.System.value,
                )
            },
        )

        fun from(settings: Settings) = SettingsFormState(
            socksAddress = settings.socksAddress,
            socksPort = settings.socksPort,
            socksUsername = settings.socksUsername,
            socksPassword = settings.socksPassword,
            userAgent = settings.userAgent,
            xHwid = settings.xHwid,
            pingAddress = settings.pingAddress,
            pingType = settings.pingType.value,
            pingTimeout = settings.pingTimeout.toString(),
            refreshLinksInterval = settings.refreshLinksInterval.toString(),
            bypassLan = settings.bypassLan,
            enableIpV6 = settings.enableIpV6,
            socksUdp = settings.socksUdp,
            tun2socks = settings.tun2socks,
            tunOwnerDefense = settings.tunOwnerDefense,
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
            themeStyle = settings.themeStyle.value,
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
    generatedXHwid: String,
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
    var showThemeDialog by rememberSaveable { mutableStateOf(false) }
    var showPingTypeDialog by rememberSaveable { mutableStateOf(false) }
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
                        title = textResource(R.string.settingsLanguageSystem),
                        selected = formState.languageTag == "system",
                        onClick = {
                            onFormStateChange(formState.copy(languageTag = "system"))
                            showLanguageDialog = false
                        },
                    )
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

    if (showThemeDialog) {
        AlertDialog(
            onDismissRequest = { showThemeDialog = false },
            title = { Text(text = textResource(R.string.settingsThemeTitle)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LanguageOptionRow(
                        title = textResource(R.string.settingsThemeSystem),
                        selected = formState.themeStyle == YaxcThemeStyle.System.value,
                        onClick = {
                            onFormStateChange(formState.copy(themeStyle = YaxcThemeStyle.System.value))
                            showThemeDialog = false
                        },
                    )
                    LanguageOptionRow(
                        title = textResource(R.string.settingsThemeMidnightBlue),
                        selected = formState.themeStyle == YaxcThemeStyle.MidnightBlue.value,
                        onClick = {
                            onFormStateChange(formState.copy(themeStyle = YaxcThemeStyle.MidnightBlue.value))
                            showThemeDialog = false
                        },
                    )
                    LanguageOptionRow(
                        title = textResource(R.string.settingsThemeGraphite),
                        selected = formState.themeStyle == YaxcThemeStyle.Graphite.value,
                        onClick = {
                            onFormStateChange(formState.copy(themeStyle = YaxcThemeStyle.Graphite.value))
                            showThemeDialog = false
                        },
                    )
                    LanguageOptionRow(
                        title = textResource(R.string.settingsThemeLightSlate),
                        selected = formState.themeStyle == YaxcThemeStyle.LightSlate.value,
                        onClick = {
                            onFormStateChange(formState.copy(themeStyle = YaxcThemeStyle.LightSlate.value))
                            showThemeDialog = false
                        },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showThemeDialog = false }) {
                    Text(text = textResource(R.string.close))
                }
            },
        )
    }

    YaxcScaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = textResource(R.string.preferences)) },
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
                .padding(top = spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth(),
            ) {
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                ) {
                    SettingsTab.entries.forEach { tab ->
                        SegmentedButton(
                            shape = SegmentedButtonDefaults.itemShape(
                                index = tab.ordinal,
                                count = SettingsTab.entries.size,
                            ),
                            selected = selectedTab == tab,
                            onClick = { selectedTab = tab },
                            label = { Text(text = textResource(tab.titleRes)) },
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = spacing.xl),
                verticalArrangement = Arrangement.spacedBy(spacing.lg),
            ) {
                when (selectedTab) {
                    SettingsTab.Basic -> BasicSettingsTab(
                        formState = formState,
                        generatedXHwid = generatedXHwid,
                        onFormStateChange = onFormStateChange,
                        onLanguageClick = { showLanguageDialog = true },
                        onThemeClick = { showThemeDialog = true },
                        onPingTypeClick = { showPingTypeDialog = true },
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

    if (showPingTypeDialog) {
        AlertDialog(
            onDismissRequest = { showPingTypeDialog = false },
            title = { Text(text = textResource(R.string.pingType)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PingTypeOption(
                        title = textResource(R.string.pingTypeHead),
                        selected = formState.pingType == Settings.PingType.Head.value,
                        onClick = {
                            onFormStateChange(formState.copy(pingType = Settings.PingType.Head.value))
                            showPingTypeDialog = false
                        },
                    )
                    PingTypeOption(
                        title = textResource(R.string.pingTypeGet),
                        selected = formState.pingType == Settings.PingType.Get.value,
                        onClick = {
                            onFormStateChange(formState.copy(pingType = Settings.PingType.Get.value))
                            showPingTypeDialog = false
                        },
                    )
                    PingTypeOption(
                        title = textResource(R.string.pingTypeTcp),
                        selected = formState.pingType == Settings.PingType.Tcp.value,
                        onClick = {
                            onFormStateChange(formState.copy(pingType = Settings.PingType.Tcp.value))
                            showPingTypeDialog = false
                        },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showPingTypeDialog = false }) {
                    Text(text = textResource(R.string.close))
                }
            },
        )
    }
}

@Composable
private fun BasicSettingsTab(
    formState: SettingsFormState,
    generatedXHwid: String,
    onFormStateChange: (SettingsFormState) -> Unit,
    onLanguageClick: () -> Unit,
    onThemeClick: () -> Unit,
    onPingTypeClick: () -> Unit,
) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    val copyToClipboard: (Int, String) -> Unit = { labelRes, value ->
        clipboardManager.setText(AnnotatedString(value))
        android.widget.Toast.makeText(
            context,
            context.getString(R.string.settingsValueCopied, context.getString(labelRes)),
            android.widget.Toast.LENGTH_SHORT,
        ).show()
    }

    Column(verticalArrangement = Arrangement.spacedBy(YaxcTheme.spacing.lg)) {
        SettingsSection(title = textResource(R.string.settingsLanguageSection))
        SettingsCard {
            YaxcSettingsRow(
                title = textResource(R.string.settingsLanguageTitle),
                subtitle = textResource(R.string.settingsLanguageLead),
                value = textResource(
                    if (formState.languageTag == "system") {
                        R.string.settingsLanguageOptionSystemShort
                    } else if (formState.languageTag == "ru") {
                        R.string.settingsLanguageOptionRuShort
                    } else {
                        R.string.settingsLanguageOptionEnShort
                    }
                ),
                icon = Icons.Outlined.Language,
                onClick = onLanguageClick,
            )
            SettingsDivider()
            YaxcSettingsRow(
                title = textResource(R.string.settingsThemeTitle),
                subtitle = textResource(R.string.settingsThemeLead),
                value = textResource(
                    when (formState.themeStyle) {
                        YaxcThemeStyle.MidnightBlue.value -> R.string.settingsThemeMidnightBlueShort
                        YaxcThemeStyle.Graphite.value -> R.string.settingsThemeGraphiteShort
                        YaxcThemeStyle.LightSlate.value -> R.string.settingsThemeLightSlateShort
                        else -> R.string.settingsThemeSystemShort
                    }
                ),
                icon = Icons.Outlined.Tune,
                onClick = onThemeClick,
            )
        }

        SettingsSection(title = textResource(R.string.settingsSectionConnection))
        SettingsCard {
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
                helperText = textResource(R.string.settingsSocksUsernameLead),
                trailingContent = {
                    IconButton(
                        onClick = { copyToClipboard(R.string.socksUsername, formState.socksUsername) }
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ContentCopy,
                            contentDescription = textResource(R.string.settingsCopyUsername),
                        )
                    }
                },
            )
            SettingsDivider()
            SettingsTextField(
                label = textResource(R.string.socksPassword),
                value = formState.socksPassword,
                onValueChange = { onFormStateChange(formState.copy(socksPassword = it)) },
                keyboardType = KeyboardType.Password,
                isPassword = !passwordVisible,
                helperText = textResource(R.string.settingsSocksPasswordLead),
                trailingContent = {
                    Row {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) {
                                    Icons.Outlined.VisibilityOff
                                } else {
                                    Icons.Outlined.Visibility
                                },
                                contentDescription = textResource(
                                    if (passwordVisible) {
                                        R.string.settingsHidePassword
                                    } else {
                                        R.string.settingsShowPassword
                                    }
                                ),
                            )
                        }
                        IconButton(
                            onClick = { copyToClipboard(R.string.socksPassword, formState.socksPassword) }
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.ContentCopy,
                                contentDescription = textResource(R.string.settingsCopyPassword),
                            )
                        }
                    }
                },
            )
        }

        SettingsSection(title = textResource(R.string.settingsSectionResources))
        SettingsCard {
            SettingsTextField(
                label = textResource(R.string.settingsUserAgent),
                value = formState.userAgent,
                onValueChange = { onFormStateChange(formState.copy(userAgent = it)) },
                helperText = textResource(R.string.settingsUserAgentLead),
            )
            SettingsDivider()
            SettingsTextField(
                label = textResource(R.string.settingsXHwid),
                value = formState.xHwid,
                onValueChange = { onFormStateChange(formState.copy(xHwid = it)) },
                helperText = textResource(R.string.settingsXHwidLead),
                trailingContent = {
                    IconButton(
                        onClick = { onFormStateChange(formState.copy(xHwid = generatedXHwid)) }
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Sync,
                            contentDescription = textResource(R.string.settingsResetXHwid),
                        )
                    }
                },
            )
            SettingsDivider()
            SettingsTextField(
                label = textResource(R.string.pingAddress),
                value = formState.pingAddress,
                onValueChange = { onFormStateChange(formState.copy(pingAddress = it)) },
                helperText = textResource(R.string.settingsPingAddressLead),
            )
            SettingsDivider()
            YaxcSettingsRow(
                title = textResource(R.string.pingType),
                subtitle = textResource(R.string.settingsPingTypeLead),
                value = when (formState.pingType) {
                    Settings.PingType.Head.value -> textResource(R.string.pingTypeHead)
                    Settings.PingType.Tcp.value -> textResource(R.string.pingTypeTcp)
                    else -> textResource(R.string.pingTypeGet)
                },
                icon = Icons.Outlined.Speed,
                onClick = onPingTypeClick,
            )
            SettingsDivider()
            SettingsTextField(
                label = textResource(R.string.pingTimeout),
                value = formState.pingTimeout,
                onValueChange = { onFormStateChange(formState.copy(pingTimeout = it)) },
                keyboardType = KeyboardType.Number,
                helperText = textResource(R.string.settingsPingTimeoutLead),
            )
            SettingsDivider()
            SettingsTextField(
                label = textResource(R.string.refreshLinksInterval),
                value = formState.refreshLinksInterval,
                onValueChange = { onFormStateChange(formState.copy(refreshLinksInterval = it)) },
                keyboardType = KeyboardType.Number,
                helperText = textResource(R.string.settingsRefreshLinksIntervalLead),
            )
        }

        SettingsSection(title = textResource(R.string.settingsSectionBehavior))
        SettingsCard {
            YaxcSwitchRow(
                title = textResource(R.string.bypassLan),
                checked = formState.bypassLan,
                onCheckedChange = { onFormStateChange(formState.copy(bypassLan = it)) },
                icon = Icons.Outlined.Tune,
                subtitle = textResource(R.string.settingsBypassLanLead),
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
                subtitle = textResource(R.string.settingsSocksUdpLead),
            )
            SettingsDivider()
            YaxcSwitchRow(
                title = textResource(R.string.tun2socks),
                checked = formState.tun2socks,
                onCheckedChange = { onFormStateChange(formState.copy(tun2socks = it)) },
                icon = Icons.Outlined.SettingsEthernet,
                subtitle = textResource(R.string.settingsTun2socksLead),
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
private fun PingTypeOption(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.78f)
        } else {
            MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)
        },
        shape = MaterialTheme.shapes.large,
        onClick = onClick,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(YaxcTheme.paddings.section),
        )
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
        SettingsCard {
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
        SettingsCard {
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
            SettingsDivider()
            YaxcSwitchRow(
                title = textResource(R.string.settingsTunOwnerDefense),
                checked = formState.tunOwnerDefense,
                onCheckedChange = { onFormStateChange(formState.copy(tunOwnerDefense = it)) },
                icon = Icons.Outlined.Security,
                subtitle = textResource(
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        R.string.settingsTunOwnerDefenseLead
                    } else {
                        R.string.settingsTunOwnerDefenseUnsupported
                    }
                ),
                enabled = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q,
            )
        }

        SettingsSection(title = textResource(R.string.settingsSectionInterfaces))
        SettingsCard {
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

        SettingsSection(
            title = textResource(R.string.settingsSectionTransparentProxy),
            description = textResource(R.string.settingsSectionTransparentProxyLead),
        )
        SettingsCard {
            SettingsTextField(
                label = textResource(R.string.tproxyAddress),
                value = formState.tproxyAddress,
                onValueChange = { onFormStateChange(formState.copy(tproxyAddress = it)) },
                helperText = textResource(R.string.settingsTproxyAddressLead),
            )
            SettingsDivider()
            SettingsTextField(
                label = textResource(R.string.tproxyPort),
                value = formState.tproxyPort,
                onValueChange = { onFormStateChange(formState.copy(tproxyPort = it)) },
                keyboardType = KeyboardType.Number,
                helperText = textResource(R.string.settingsTproxyPortLead),
            )
            SettingsDivider()
            SettingsTextField(
                label = textResource(R.string.tproxyBypassWiFi),
                value = formState.tproxyBypassWiFi,
                onValueChange = { onFormStateChange(formState.copy(tproxyBypassWiFi = it)) },
                helperText = textResource(R.string.settingsTproxyBypassWifiLead),
            )
            SettingsDivider()
            YaxcSwitchRow(
                title = textResource(R.string.tproxyAutoConnect),
                checked = formState.tproxyAutoConnect,
                onCheckedChange = {
                    onFormStateChange(formState.copy(tproxyAutoConnect = it))
                },
                icon = Icons.Outlined.Speed,
                subtitle = textResource(R.string.settingsTproxyAutoConnectLead),
            )
            SettingsDivider()
            YaxcSwitchRow(
                title = textResource(R.string.tproxyHotspot),
                checked = formState.tproxyHotspot,
                onCheckedChange = { onFormStateChange(formState.copy(tproxyHotspot = it)) },
                icon = Icons.Outlined.Public,
                subtitle = textResource(R.string.settingsTproxyHotspotLead),
            )
            SettingsDivider()
            YaxcSwitchRow(
                title = textResource(R.string.tproxyTethering),
                checked = formState.tproxyTethering,
                onCheckedChange = {
                    onFormStateChange(formState.copy(tproxyTethering = it))
                },
                icon = Icons.Outlined.Sync,
                subtitle = textResource(R.string.settingsTproxyTetheringLead),
            )
            SettingsDivider()
            YaxcSwitchRow(
                title = textResource(R.string.transparentProxy),
                checked = formState.transparentProxy,
                onCheckedChange = {
                    onFormStateChange(formState.copy(transparentProxy = it))
                },
                icon = Icons.Outlined.Security,
                subtitle = textResource(R.string.settingsTransparentProxyLead),
            )
        }

        SettingsSection(title = textResource(R.string.settingsSectionRoutes))
        SettingsCard {
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
private fun SettingsCard(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    YaxcCard(
        modifier = modifier,
        contentPadding = YaxcTheme.paddings.section,
        content = content,
    )
}

@Composable
private fun SettingsSection(
    title: String,
    description: String? = null,
) {
    Column(
        modifier = Modifier.padding(horizontal = YaxcTheme.spacing.xs),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = YaxcTheme.extendedColors.textMuted,
        )
        description?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = YaxcTheme.extendedColors.textMuted,
            )
        }
    }
}

@Composable
private fun SettingsTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    isPassword: Boolean = false,
    helperText: String? = null,
    trailingContent: @Composable (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = YaxcTheme.spacing.xs),
        verticalArrangement = Arrangement.spacedBy(YaxcTheme.spacing.xs),
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(text = label) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            visualTransformation = if (isPassword) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
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
private fun SettingsDivider() {
    HorizontalDivider(
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
