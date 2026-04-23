package io.github.derundevu.yaxc.presentation.main

import io.github.derundevu.yaxc.database.Link
import io.github.derundevu.yaxc.dto.SubscriptionMetadata
import io.github.derundevu.yaxc.dto.ProfileList

sealed interface MainPingState {
    data object Idle : MainPingState
    data object Loading : MainPingState
    data class Success(val label: String) : MainPingState
    data class Error(val label: String) : MainPingState
}

data class MainProfileItem(
    val profile: ProfileList,
    val summary: String,
    val pingState: MainPingState = MainPingState.Idle,
)

data class MainUiState(
    val tabs: List<Link> = emptyList(),
    val selectedTabId: Long = 0L,
    val selectedSourceId: Long = 0L,
    val selectedSourceName: String = "",
    val selectedSourceMetadata: SubscriptionMetadata? = null,
    val filteredProfiles: List<MainProfileItem> = emptyList(),
    val selectedProfileId: Long = 0L,
    val selectedProfileName: String = "",
    val selectedServerLabel: String = "",
    val socksAddress: String = "",
    val socksPort: String = "",
    val socksUsername: String = "",
    val socksPassword: String = "",
    val pingAddress: String = "",
    val profilesCount: Int = 0,
    val isRunning: Boolean = false,
    val pingState: MainPingState = MainPingState.Idle,
    val activeBatchPingSourceId: Long? = null,
)
