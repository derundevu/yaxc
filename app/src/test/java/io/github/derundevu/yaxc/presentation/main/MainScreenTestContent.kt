package io.github.derundevu.yaxc.presentation.main

import androidx.compose.runtime.Composable
import io.github.derundevu.yaxc.helper.AppUpdateUiState

/** Supplies unrelated app-version/update chrome without invoking native code or networking. */
@Composable
internal fun MainScreenTestContent(state: MainUiState, onAction: (MainAction) -> Unit) {
    MainScreen(
        tabs = state.tabs,
        selectedTabId = state.selectedTabId,
        selectedSourceId = state.selectedSourceId,
        isRunning = state.isRunning,
        connectionFailure = state.connectionFailure,
        selectedSourceName = state.selectedSourceName,
        selectedSourceMetadata = state.selectedSourceMetadata,
        selectedSourceLastRefreshedAt = state.selectedSourceLastRefreshedAt,
        selectedProfileName = state.selectedProfileName,
        selectedServerLabel = state.selectedServerLabel,
        socksAddress = state.socksAddress,
        socksPort = state.socksPort,
        socksUsername = state.socksUsername,
        socksPassword = state.socksPassword,
        pingAddress = state.pingAddress,
        pingState = state.pingState,
        profiles = state.filteredProfiles,
        selectedProfileId = state.selectedProfileId,
        profilesCount = state.profilesCount,
        activeBatchPingSourceId = state.activeBatchPingSourceId,
        batchPingCompleted = state.batchPingCompleted,
        batchPingTotal = state.batchPingTotal,
        appVersion = "regression",
        xrayVersion = "regression",
        tun2socksVersion = "regression",
        appUpdateState = AppUpdateUiState(isChecking = false),
        onCheckAppUpdate = {},
        onDownloadAppUpdate = {},
        onInstallAppUpdate = {},
        onAction = onAction,
    )
}
