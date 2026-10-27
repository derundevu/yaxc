package io.github.derundevu.yaxc.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.derundevu.yaxc.R
import io.github.derundevu.yaxc.Settings
import io.github.derundevu.yaxc.Yaxc
import io.github.derundevu.yaxc.database.Link
import io.github.derundevu.yaxc.dto.SubscriptionMetadata
import io.github.derundevu.yaxc.dto.ProfileList
import io.github.derundevu.yaxc.presentation.main.MainAction
import io.github.derundevu.yaxc.presentation.main.MainEffect
import io.github.derundevu.yaxc.presentation.main.MainPingState
import io.github.derundevu.yaxc.presentation.main.MainProfileItem
import io.github.derundevu.yaxc.presentation.main.MainUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private data class SelectionState(
        val selectedTabId: Long,
        val selectedProfileId: Long,
    )

    private data class ProfilePresentation(
        val profile: ProfileList,
        val summary: String,
        val serverLabel: String,
    )

    private data class RuntimeState(
        val isRunning: Boolean,
        val ping: MainPingSnapshot,
    )

    private val settings = Settings(application)
    private val linkRepository by lazy { getApplication<Yaxc>().linkRepository }
    private val profileRepository by lazy { getApplication<Yaxc>().profileRepository }
    private var hasResolvedTabsSnapshot = false
    private var hasResolvedProfilesSnapshot = false

    private val selectedTabId = MutableStateFlow(settings.selectedLink)
    private val selectedProfileId = MutableStateFlow(settings.selectedProfile)
    private val isRunning = MutableStateFlow(false)
    private val pingTracker = MainPingTracker()
    private val pingState = MutableStateFlow(pingTracker.snapshot)
    private val pingResultPattern = "(\\d+)\\s*(ms|мс)".toRegex(RegexOption.IGNORE_CASE)
    private val _effects = MutableSharedFlow<MainEffect>(extraBufferCapacity = 16)

    private val tabs = linkRepository.tabs.flowOn(Dispatchers.IO)
    private val allProfiles = profileRepository.all.flowOn(Dispatchers.IO).stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        emptyList(),
    )
    private val profilePresentations = allProfiles.map { profiles ->
        profiles.map(::presentProfile)
    }.flowOn(Dispatchers.Default)
    private val selection = combine(selectedTabId, selectedProfileId) { selectedTabId, selectedProfileId ->
        SelectionState(
            selectedTabId = selectedTabId,
            selectedProfileId = selectedProfileId,
        )
    }
    private val runtime = combine(isRunning, pingState) { isRunning, ping ->
        RuntimeState(isRunning = isRunning, ping = ping)
    }

    val uiState = combine(
        tabs,
        profilePresentations,
        selection,
        runtime,
    ) { tabs: List<Link>, profiles: List<ProfilePresentation>, selection: SelectionState, runtime: RuntimeState ->
        val selectedPresentation = profiles.firstOrNull { it.profile.id == selection.selectedProfileId }
        val selectedProfile = selectedPresentation?.profile
        val selectedSourceId = selectedProfile?.link?.takeIf { linkId ->
            tabs.any { it.id == linkId }
        } ?: 0L
        val resolvedTabId = selection.selectedTabId.takeIf { selected ->
            selected == 0L || tabs.any { it.id == selected }
        } ?: 0L
        val cardSourceId = if (selectedSourceId != 0L) selectedSourceId else resolvedTabId
        val selectedSource = tabs.firstOrNull { it.id == cardSourceId }
        val selectedSourceName = selectedSource?.name
            ?: application.getString(R.string.mainNoSourceSelected)
        val selectedSourceMetadata = SubscriptionMetadata.fromJsonString(
            selectedSource?.subscriptionMetadata
        )
        val selectedSourceLastRefreshedAt = selectedSource
            ?.lastRefreshedAt
            ?.takeIf { it > 0L }
        val filteredProfiles = profiles
            .filter { resolvedTabId == 0L || it.profile.link == resolvedTabId }
            .map { presentation ->
                MainProfileItem(
                    profile = presentation.profile,
                    summary = presentation.summary,
                    pingState = runtime.ping.profiles[presentation.profile.id] ?: MainPingState.Idle,
                )
            }
        val selectedProfileName = selectedProfile?.name.orEmpty()
        val selectedProfilePingState = runtime.ping.profiles[selection.selectedProfileId]
            ?: MainPingState.Idle
        val selectedServerLabel = selectedPresentation?.serverLabel
            ?: application.getString(R.string.noValue)
        val socksAddress = settings.socksAddress.trim()
        val socksPort = settings.socksPort.trim()
        val socksUsername = settings.socksUsername.trim()
        val socksPassword = settings.socksPassword
        val pingAddress = settings.pingAddress.trim()

        MainUiState(
            tabs = tabs,
            selectedTabId = resolvedTabId,
            selectedSourceId = cardSourceId,
            selectedSourceName = selectedSourceName,
            selectedSourceMetadata = selectedSourceMetadata,
            selectedSourceLastRefreshedAt = selectedSourceLastRefreshedAt,
            filteredProfiles = filteredProfiles,
            selectedProfileId = selection.selectedProfileId,
            selectedProfileName = selectedProfileName,
            selectedServerLabel = selectedServerLabel,
            socksAddress = socksAddress,
            socksPort = socksPort,
            socksUsername = socksUsername,
            socksPassword = socksPassword,
            pingAddress = pingAddress,
            profilesCount = filteredProfiles.size,
            isRunning = runtime.isRunning,
            pingState = selectedProfilePingState,
            activeBatchPingSourceId = runtime.ping.sourceId,
            batchPingCompleted = runtime.ping.completed,
            batchPingTotal = runtime.ping.total,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        MainUiState(selectedSourceName = application.getString(R.string.mainNoSourceSelected)),
    )
    val effects = _effects.asSharedFlow()

    init {
        viewModelScope.launch {
            tabs.collect { tabs ->
                if (!hasResolvedTabsSnapshot) {
                    hasResolvedTabsSnapshot = true
                    if (tabs.isEmpty()) return@collect
                }
                if (selectedTabId.value != 0L && tabs.isNotEmpty() && tabs.none { it.id == selectedTabId.value }) {
                    selectTab(0L)
                }
            }
        }
        viewModelScope.launch {
            allProfiles.collect { profiles ->
                if (!hasResolvedProfilesSnapshot) {
                    hasResolvedProfilesSnapshot = true
                    if (profiles.isEmpty()) return@collect
                }
                profileRepository.normalizeIndices()
                val validProfileIds = profiles.map { it.id }.toSet()
                pingTracker.retain(validProfileIds)
                pingState.value = pingTracker.snapshot
                if (selectedProfileId.value != 0L && profiles.isNotEmpty() && profiles.none { it.id == selectedProfileId.value }) {
                    clearSelectedProfile()
                }
            }
        }
    }

    fun selectTab(linkId: Long) {
        val nextValue = if (selectedTabId.value == linkId) 0L else linkId
        settings.selectedLink = nextValue
        selectedTabId.value = nextValue
    }

    fun selectProfile(profileId: Long) {
        settings.selectedProfile = profileId
        selectedProfileId.value = profileId
    }

    fun clearSelectedProfile() {
        settings.selectedProfile = 0L
        selectedProfileId.value = 0L
    }

    fun updateVpnRunning(running: Boolean) {
        isRunning.value = running
    }

    fun isCurrentPingOperation(operationId: Long): Boolean = pingTracker.isCurrent(operationId)

    private fun startPing(profileIds: List<Long>, sourceId: Long? = null, batch: Boolean = false): Long {
        val id = pingTracker.start(profileIds, sourceId, batch)
        pingState.value = pingTracker.snapshot
        return id
    }

    private fun finishPing(operationId: Long) {
        pingTracker.finish(operationId)
        pingState.value = pingTracker.snapshot
    }

    private fun parsePingState(result: String): MainPingState {
        val pingLabel = pingResultPattern.matchEntire(result.trim())
            ?.groupValues
            ?.getOrNull(1)
            ?.let { "$it ms" }
        return if (pingLabel == null) {
            MainPingState.Error(
                getApplication<Application>().getString(R.string.mainPingFailedShort)
            )
        } else {
            MainPingState.Success(pingLabel)
        }
    }

    private fun updatePingResult(operationId: Long, profileId: Long, result: String) {
        pingTracker.result(operationId, profileId, parsePingState(result))
        pingState.value = pingTracker.snapshot
    }

    private fun requestBatchPing(sourceId: Long?, profileIds: List<Long>) {
        if (profileIds.isEmpty()) {
            _effects.tryEmit(
                MainEffect.ShowToast(
                    getApplication<Application>().getString(R.string.mainNoProfilesInSource)
                )
            )
            return
        }
        val operationId = startPing(profileIds, sourceId, batch = true)
        _effects.tryEmit(
            MainEffect.RunBatchPing(
                operationId = operationId,
                sourceId = sourceId,
                profileIds = profileIds,
            )
        )
    }

    fun onAction(action: MainAction) {
        when (action) {
            is MainAction.SelectTab -> selectTab(action.linkId)
            is MainAction.UpdateVpnStatus -> updateVpnRunning(action.isRunning)
            is MainAction.PingResultReceived -> updatePingResult(action.operationId, action.profileId, action.result)
            is MainAction.PingFinished -> finishPing(action.operationId)
            is MainAction.SelectProfile -> {
                if (selectedProfileId.value == action.profileId) return
                selectProfile(action.profileId)
                if (uiState.value.isRunning && action.reloadRuntime) {
                    _effects.tryEmit(MainEffect.ReloadCurrentConfig)
                }
            }
            is MainAction.PingSourceClicked -> {
                requestBatchPing(
                    sourceId = action.linkId,
                    profileIds = allProfiles.value
                        .filter { it.link == action.linkId }
                        .map { it.id },
                )
            }
            is MainAction.RefreshSourceClicked -> {
                _effects.tryEmit(MainEffect.RefreshSelectedSource(action.linkId))
            }
            is MainAction.EditProfile -> {
                if (uiState.value.isRunning && uiState.value.selectedProfileId == action.profile.id) {
                    return
                }
                _effects.tryEmit(MainEffect.OpenProfileEditor(action.profile.id))
            }
            is MainAction.RequestDeleteProfile -> {
                if (uiState.value.isRunning && uiState.value.selectedProfileId == action.profile.id) {
                    return
                }
                _effects.tryEmit(MainEffect.ConfirmDeleteProfile(action.profile))
            }
            is MainAction.ConfirmDeleteProfile -> {
                viewModelScope.launch {
                    val profile = profileRepository.find(action.profile.id)
                    profileRepository.remove(profile)
                    if (selectedProfileId.value == profile.id) clearSelectedProfile()
                }
            }
            is MainAction.RequestRenameSource -> {
                val source = uiState.value.tabs.firstOrNull { it.id == action.sourceId } ?: return
                _effects.tryEmit(MainEffect.ShowRenameSourceDialog(source))
            }
            is MainAction.ConfirmRenameSource -> {
                val source = uiState.value.tabs.firstOrNull { it.id == action.sourceId } ?: return
                val nextName = action.name.trim()
                if (nextName.isBlank()) return
                viewModelScope.launch {
                    linkRepository.update(source.copy(name = nextName))
                }
            }
            is MainAction.RequestDeleteSource -> {
                val source = uiState.value.tabs.firstOrNull { it.id == action.sourceId } ?: return
                _effects.tryEmit(MainEffect.ConfirmDeleteSourceDialog(source))
            }
            is MainAction.ConfirmDeleteSource -> {
                val source = uiState.value.tabs.firstOrNull { it.id == action.sourceId } ?: return
                viewModelScope.launch {
                    linkRepository.delete(source)
                }
            }
            is MainAction.CommitSourceOrder -> {
                val currentIds = uiState.value.tabs.map { it.id }
                if (action.orderedIds.isEmpty() || action.orderedIds == currentIds) return
                viewModelScope.launch {
                    linkRepository.reorder(action.orderedIds)
                }
            }
            MainAction.ToggleVpnClicked -> _effects.tryEmit(MainEffect.HandleToggleVpn)
            MainAction.PingClicked -> {
                val profileId = selectedProfileId.value
                if (profileId == 0L) return
                val operationId = startPing(listOf(profileId))
                _effects.tryEmit(MainEffect.RunPing(operationId, profileId))
            }
            MainAction.PingAllProfilesClicked -> {
                requestBatchPing(
                    sourceId = uiState.value.selectedTabId.takeIf { it != 0L },
                    profileIds = uiState.value.filteredProfiles.map { it.profile.id },
                )
            }
            MainAction.RefreshLinksClicked -> _effects.tryEmit(MainEffect.RefreshLinks)
            MainAction.NewProfileClicked -> _effects.tryEmit(MainEffect.OpenNewProfile)
            MainAction.NewSourceClicked -> _effects.tryEmit(MainEffect.OpenNewSource)
            MainAction.ScanQrCodeClicked -> _effects.tryEmit(MainEffect.RequestQrCodeScanner)
            MainAction.ImportFromClipboardClicked -> _effects.tryEmit(MainEffect.ImportFromClipboard)
            MainAction.OpenAssetsClicked -> _effects.tryEmit(MainEffect.OpenAssets)
            MainAction.OpenLinksClicked -> _effects.tryEmit(MainEffect.OpenLinks)
            MainAction.OpenLogsClicked -> _effects.tryEmit(MainEffect.OpenLogs)
            MainAction.OpenConnectionInfoClicked -> _effects.tryEmit(MainEffect.OpenConnectionInfo)
            MainAction.OpenAppsRoutingClicked -> _effects.tryEmit(MainEffect.OpenAppsRouting)
            MainAction.OpenCoreRoutingClicked -> _effects.tryEmit(MainEffect.OpenCoreRouting)
            MainAction.OpenConfigsClicked -> _effects.tryEmit(MainEffect.OpenConfigs)
            MainAction.OpenSettingsClicked -> _effects.tryEmit(MainEffect.OpenSettings)
        }
    }

    private fun presentProfile(profile: ProfileList): ProfilePresentation {
        val noValue = getApplication<Application>().getString(R.string.noValue)
        return runCatching {
            val root = JSONObject(profile.config)
            val outbounds = root.optJSONArray("outbounds") ?: JSONArray()
            val outbound = outbounds.optJSONObject(0) ?: JSONObject()
            val settings = outbound.optJSONObject("settings") ?: JSONObject()
            val streamSettings = outbound.optJSONObject("streamSettings") ?: JSONObject()
            val serverLabel = settings.optString("address").takeIf { it.isNotBlank() }
                ?: settings.optJSONArray("vnext")
                    ?.optJSONObject(0)
                    ?.optString("address")
                    ?.takeIf { it.isNotBlank() }
                ?: streamSettings.optJSONObject("realitySettings")
                    ?.optString("serverName")
                    ?.takeIf { it.isNotBlank() }
                ?: noValue
            val protocol = outbound.optString("protocol").uppercase().takeIf { it.isNotBlank() }
            val network = streamSettings.optString("network").uppercase().takeIf { it.isNotBlank() }
            val security = streamSettings.optString("security").uppercase().takeIf { it.isNotBlank() }
            ProfilePresentation(
                profile = profile,
                summary = listOfNotNull(protocol, network, security).joinToString(" • ").ifBlank { noValue },
                serverLabel = serverLabel,
            )
        }.getOrElse { ProfilePresentation(profile, noValue, noValue) }
    }
}
