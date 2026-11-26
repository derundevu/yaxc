package io.github.derundevu.yaxc.presentation.settings

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import io.github.derundevu.yaxc.R

@Immutable
data class DnsPreset(
    val id: String,
    @param:StringRes val nameRes: Int,
    @param:StringRes val summaryRes: Int,
    val primaryDns: String,
    val secondaryDns: String,
    val primaryDnsV6: String,
    val secondaryDnsV6: String,
    val encryptedDns: String,
)

val popularDnsPresets: List<DnsPreset> = listOf(
    DnsPreset(
        id = "cloudflare",
        nameRes = R.string.dnsPresetCloudflare,
        summaryRes = R.string.dnsPresetCloudflareLead,
        primaryDns = "1.1.1.1",
        secondaryDns = "1.0.0.1",
        primaryDnsV6 = "2606:4700:4700::1111",
        secondaryDnsV6 = "2606:4700:4700::1001",
        encryptedDns = "https://1.1.1.1/dns-query",
    ),
    DnsPreset(
        id = "cloudflare_security",
        nameRes = R.string.dnsPresetCloudflareSecurity,
        summaryRes = R.string.dnsPresetCloudflareSecurityLead,
        primaryDns = "1.1.1.2",
        secondaryDns = "1.0.0.2",
        primaryDnsV6 = "2606:4700:4700::1112",
        secondaryDnsV6 = "2606:4700:4700::1002",
        encryptedDns = "https://1.1.1.2/dns-query",
    ),
    DnsPreset(
        id = "google",
        nameRes = R.string.dnsPresetGoogle,
        summaryRes = R.string.dnsPresetGoogleLead,
        primaryDns = "8.8.8.8",
        secondaryDns = "8.8.4.4",
        primaryDnsV6 = "2001:4860:4860::8888",
        secondaryDnsV6 = "2001:4860:4860::8844",
        encryptedDns = "https://8.8.8.8/dns-query",
    ),
    DnsPreset(
        id = "quad9",
        nameRes = R.string.dnsPresetQuad9,
        summaryRes = R.string.dnsPresetQuad9Lead,
        primaryDns = "9.9.9.9",
        secondaryDns = "149.112.112.112",
        primaryDnsV6 = "2620:fe::fe",
        secondaryDnsV6 = "2620:fe::9",
        encryptedDns = "https://9.9.9.9/dns-query",
    ),
    DnsPreset(
        id = "adguard",
        nameRes = R.string.dnsPresetAdGuard,
        summaryRes = R.string.dnsPresetAdGuardLead,
        primaryDns = "94.140.14.14",
        secondaryDns = "94.140.15.15",
        primaryDnsV6 = "2a10:50c0::ad1:ff",
        secondaryDnsV6 = "2a10:50c0::ad2:ff",
        encryptedDns = "https://94.140.14.14/dns-query",
    ),
    DnsPreset(
        id = "adguard_family",
        nameRes = R.string.dnsPresetAdGuardFamily,
        summaryRes = R.string.dnsPresetAdGuardFamilyLead,
        primaryDns = "94.140.14.15",
        secondaryDns = "94.140.15.16",
        primaryDnsV6 = "2a10:50c0::bad1:ff",
        secondaryDnsV6 = "2a10:50c0::bad2:ff",
        encryptedDns = "https://94.140.14.15/dns-query",
    ),
    DnsPreset(
        id = "dnssb",
        nameRes = R.string.dnsPresetDnsSb,
        summaryRes = R.string.dnsPresetDnsSbLead,
        primaryDns = "185.222.222.222",
        secondaryDns = "45.11.45.11",
        primaryDnsV6 = "2a09::",
        secondaryDnsV6 = "2a11::",
        encryptedDns = "https://185.222.222.222/dns-query",
    ),
    DnsPreset(
        id = "opendns",
        nameRes = R.string.dnsPresetOpenDns,
        summaryRes = R.string.dnsPresetOpenDnsLead,
        primaryDns = "208.67.222.222",
        secondaryDns = "208.67.220.220",
        primaryDnsV6 = "2620:119:35::35",
        secondaryDnsV6 = "2620:119:53::53",
        encryptedDns = "",
    ),
    DnsPreset(
        id = "yandex",
        nameRes = R.string.dnsPresetYandex,
        summaryRes = R.string.dnsPresetYandexLead,
        primaryDns = "77.88.8.8",
        secondaryDns = "77.88.8.1",
        primaryDnsV6 = "2a02:6b8::feed:0ff",
        secondaryDnsV6 = "2a02:6b8:0:1::feed:0ff",
        encryptedDns = "",
    ),
)

fun findMatchingDnsPreset(formState: SettingsFormState): DnsPreset? {
    val primary = formState.primaryDns.trim()
    val secondary = formState.secondaryDns.trim()
    val primaryV6 = formState.primaryDnsV6.trim()
    val secondaryV6 = formState.secondaryDnsV6.trim()
    val encrypted = formState.encryptedDns.trim()
    return popularDnsPresets.firstOrNull { preset ->
        preset.primaryDns == primary &&
            preset.secondaryDns == secondary &&
            preset.primaryDnsV6 == primaryV6 &&
            preset.secondaryDnsV6 == secondaryV6 &&
            preset.encryptedDns == encrypted
    }
}

fun SettingsFormState.withDnsPreset(preset: DnsPreset): SettingsFormState {
    return copy(
        primaryDns = preset.primaryDns,
        secondaryDns = preset.secondaryDns,
        primaryDnsV6 = preset.primaryDnsV6,
        secondaryDnsV6 = preset.secondaryDnsV6,
        encryptedDns = preset.encryptedDns,
    )
}
