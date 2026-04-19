package io.github.derundevu.yaxc.presentation.main

import io.github.derundevu.yaxc.database.Link
import io.github.derundevu.yaxc.dto.ProfileList

sealed interface MainEffect {
    data object HandleToggleVpn : MainEffect
    data class RunPing(val profileId: Long) : MainEffect
    data class RunBatchPing(
        val sourceId: Long?,
        val profileIds: List<Long>,
        val restoreProfileId: Long,
    ) : MainEffect
    data object RefreshLinks : MainEffect
    data class RefreshSelectedSource(val linkId: Long) : MainEffect
    data object OpenNewProfile : MainEffect
    data object RequestQrCodeScanner : MainEffect
    data object ImportFromClipboard : MainEffect
    data object OpenAssets : MainEffect
    data object OpenLinks : MainEffect
    data object OpenLogs : MainEffect
    data object OpenAppsRouting : MainEffect
    data object OpenCoreRouting : MainEffect
    data object OpenConfigs : MainEffect
    data object OpenSettings : MainEffect
    data object ReloadCurrentConfig : MainEffect
    data class OpenProfileEditor(val profileId: Long) : MainEffect
    data class ConfirmDeleteProfile(val profile: ProfileList) : MainEffect
    data class ShowRenameSourceDialog(val source: Link) : MainEffect
    data class ConfirmDeleteSourceDialog(val source: Link) : MainEffect
    data class ShowToast(val message: String) : MainEffect
}
