package io.github.derundevu.yaxc.presentation.main

import io.github.derundevu.yaxc.dto.ProfileList

sealed interface MainAction {
    data class SelectTab(val linkId: Long) : MainAction
    data class UpdateVpnStatus(val isRunning: Boolean) : MainAction
    data class PingResultReceived(val profileId: Long, val result: String) : MainAction
    data class ProfilePingUpdated(val profileId: Long, val result: String) : MainAction
    data class SetProfilePingState(val profileId: Long, val state: MainPingState) : MainAction
    data class SelectProfile(val profileId: Long, val reloadRuntime: Boolean = true) : MainAction
    data class PingSourceClicked(val linkId: Long) : MainAction
    data class RefreshSourceClicked(val linkId: Long) : MainAction
    data class EditProfile(val profile: ProfileList) : MainAction
    data class RequestDeleteProfile(val profile: ProfileList) : MainAction
    data class ConfirmDeleteProfile(val profile: ProfileList) : MainAction
    data class RequestRenameSource(val sourceId: Long) : MainAction
    data class ConfirmRenameSource(val sourceId: Long, val name: String) : MainAction
    data class RequestDeleteSource(val sourceId: Long) : MainAction
    data class ConfirmDeleteSource(val sourceId: Long) : MainAction
    data class CommitSourceOrder(val orderedIds: List<Long>) : MainAction
    data class SetBatchPingSource(val sourceId: Long?) : MainAction

    data object ToggleVpnClicked : MainAction
    data object PingClicked : MainAction
    data object PingAllProfilesClicked : MainAction
    data object RefreshLinksClicked : MainAction
    data object NewProfileClicked : MainAction
    data object NewSourceClicked : MainAction
    data object ScanQrCodeClicked : MainAction
    data object ImportFromClipboardClicked : MainAction
    data object OpenAssetsClicked : MainAction
    data object OpenLinksClicked : MainAction
    data object OpenLogsClicked : MainAction
    data object OpenConnectionInfoClicked : MainAction
    data object OpenAppsRoutingClicked : MainAction
    data object OpenCoreRoutingClicked : MainAction
    data object OpenConfigsClicked : MainAction
    data object OpenSettingsClicked : MainAction
}
