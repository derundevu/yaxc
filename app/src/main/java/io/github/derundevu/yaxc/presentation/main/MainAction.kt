package io.github.derundevu.yaxc.presentation.main

import io.github.derundevu.yaxc.dto.ProfileList

sealed interface MainAction {
    data class SelectTab(val linkId: Long) : MainAction
    data class UpdateVpnStatus(val isRunning: Boolean) : MainAction
    data class PingResultReceived(val result: String) : MainAction
    data class SelectProfile(val profileId: Long) : MainAction
    data class PingSourceClicked(val linkId: Long) : MainAction
    data class RefreshSourceClicked(val linkId: Long) : MainAction
    data class EditProfile(val profile: ProfileList) : MainAction
    data class RequestDeleteProfile(val profile: ProfileList) : MainAction
    data class ConfirmDeleteProfile(val profile: ProfileList) : MainAction

    data object ToggleVpnClicked : MainAction
    data object PingClicked : MainAction
    data object PingAllProfilesClicked : MainAction
    data object RefreshLinksClicked : MainAction
    data object NewProfileClicked : MainAction
    data object ScanQrCodeClicked : MainAction
    data object ImportFromClipboardClicked : MainAction
    data object OpenAssetsClicked : MainAction
    data object OpenLinksClicked : MainAction
    data object OpenLogsClicked : MainAction
    data object OpenAppsRoutingClicked : MainAction
    data object OpenConfigsClicked : MainAction
    data object OpenSettingsClicked : MainAction
}
