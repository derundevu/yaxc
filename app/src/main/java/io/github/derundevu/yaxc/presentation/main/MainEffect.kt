package io.github.derundevu.yaxc.presentation.main

import io.github.derundevu.yaxc.dto.ProfileList

sealed interface MainEffect {
    data object HandleToggleVpn : MainEffect
    data object RunPing : MainEffect
    data object PingAllProfiles : MainEffect
    data object RefreshLinks : MainEffect
    data class RefreshSelectedSource(val linkId: Long) : MainEffect
    data object OpenNewProfile : MainEffect
    data object RequestQrCodeScanner : MainEffect
    data object ImportFromClipboard : MainEffect
    data object OpenAssets : MainEffect
    data object OpenLinks : MainEffect
    data object OpenLogs : MainEffect
    data object OpenAppsRouting : MainEffect
    data object OpenConfigs : MainEffect
    data object OpenSettings : MainEffect
    data object ReloadCurrentConfig : MainEffect
    data class OpenProfileEditor(val profileId: Long) : MainEffect
    data class ConfirmDeleteProfile(val profile: ProfileList) : MainEffect
    data class ShowToast(val message: String) : MainEffect
}
