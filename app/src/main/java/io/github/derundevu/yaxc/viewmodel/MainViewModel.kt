package io.github.derundevu.yaxc.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.derundevu.yaxc.R
import io.github.derundevu.yaxc.Settings
import io.github.derundevu.yaxc.Yaxc
import io.github.derundevu.yaxc.database.Link
import io.github.derundevu.yaxc.dto.ProfileList
import io.github.derundevu.yaxc.helper.XrayBatchPingHelper
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
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private data class SelectionState(
        val selectedTabId: Long,
        val selectedProfileId: Long,
    )

    private data class RuntimeState(
        val isRunning: Boolean,
        val profilePingStates: Map<Long, MainPingState>,
        val activeBatchPingSourceId: Long?,
    )

    private val settings = Settings(application)
    private val linkRepository by lazy { getApplication<Yaxc>().linkRepository }
    private val profileRepository by lazy { getApplication<Yaxc>().profileRepository }
    private var hasResolvedTabsSnapshot = false
    private var hasResolvedProfilesSnapshot = false

    private val selectedTabId = MutableStateFlow(settings.selectedLink)
    private val selectedProfileId = MutableStateFlow(settings.selectedProfile)
    private val isRunning = MutableStateFlow(false)
    private val profilePingStates = MutableStateFlow<Map<Long, MainPingState>>(emptyMap())
    private val activeBatchPingSourceId = MutableStateFlow<Long?>(null)
    private val _effects = MutableSharedFlow<MainEffect>(extraBufferCapacity = 16)

    private val tabs = linkRepository.tabs.flowOn(Dispatchers.IO)
    private val allProfiles = profileRepository.all.flowOn(Dispatchers.IO).stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        emptyList(),
    )
    private val selection = combine(selectedTabId, selectedProfileId) { selectedTabId, selectedProfileId ->
        SelectionState(
            selectedTabId = selectedTabId,
            selectedProfileId = selectedProfileId,
        )
    }
    private val runtime = combine(
        isRunning,
        profilePingStates,
        activeBatchPingSourceId,
    ) { isRunning, profilePingStates, activeBatchPingSourceId ->
        RuntimeState(
            isRunning = isRunning,
            profilePingStates = profilePingStates,
            activeBatchPingSourceId = activeBatchPingSourceId,
        )
    }

    val uiState = combine(
        tabs,
        allProfiles,
        selection,
        runtime,
    ) { tabs: List<Link>, profiles: List<ProfileList>, selection: SelectionState, runtime: RuntimeState ->
        val selectedProfile = profiles.firstOrNull { it.id == selection.selectedProfileId }
        val selectedSourceId = selectedProfile?.link?.takeIf { linkId ->
            tabs.any { it.id == linkId }
        } ?: 0L
        val resolvedTabId = selection.selectedTabId.takeIf { selected ->
            selected == 0L || tabs.any { it.id == selected }
        } ?: 0L
        val cardSourceId = if (selectedSourceId != 0L) selectedSourceId else resolvedTabId
        val selectedSourceName = tabs.firstOrNull { it.id == cardSourceId }?.name
            ?: application.getString(R.string.mainNoSourceSelected)
        val filteredProfiles = profiles
            .filter { resolvedTabId == 0L || it.link == resolvedTabId }
            .map { profile ->
                MainProfileItem(
                    profile = profile,
                    summary = extractProfileSummary(profile.config),
                    pingState = runtime.profilePingStates[profile.id] ?: MainPingState.Idle,
                )
            }
        val selectedProfileName = selectedProfile?.name.orEmpty()
        val selectedProfilePingState = runtime.profilePingStates[selection.selectedProfileId]
            ?: MainPingState.Idle
        val selectedServerLabel = selectedProfile
            ?.config
            ?.let(::extractServerLabel)
            ?: application.getString(R.string.noValue)

        MainUiState(
            tabs = tabs,
            selectedTabId = resolvedTabId,
            selectedSourceId = cardSourceId,
            selectedSourceName = selectedSourceName,
            filteredProfiles = filteredProfiles,
            selectedProfileId = selection.selectedProfileId,
            selectedProfileName = selectedProfileName,
            selectedServerLabel = selectedServerLabel,
            profilesCount = filteredProfiles.size,
            isRunning = runtime.isRunning,
            pingState = selectedProfilePingState,
            activeBatchPingSourceId = runtime.activeBatchPingSourceId,
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
                fixIndex(profiles)
                val validProfileIds = profiles.map { it.id }.toSet()
                profilePingStates.value = profilePingStates.value
                    .filterKeys { it in validProfileIds }
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
        if (!running && !XrayBatchPingHelper.supportsIsolatedPing()) {
            activeBatchPingSourceId.value = null
            profilePingStates.value = profilePingStates.value.mapValues { (_, value) ->
                if (value == MainPingState.Loading) MainPingState.Idle else value
            }
        }
    }

    fun markPingTesting(profileId: Long) {
        setProfilePingState(profileId, MainPingState.Loading)
    }

    fun setProfilePingState(profileId: Long, state: MainPingState) {
        profilePingStates.value = profilePingStates.value.toMutableMap().apply {
            put(profileId, state)
        }
    }

    private fun parsePingState(result: String): MainPingState {
        val pingLabel = "(\\d+)\\s*(ms|мс)".toRegex(RegexOption.IGNORE_CASE)
            .find(result)
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

    fun updatePingResult(profileId: Long, result: String) {
        setProfilePingState(profileId, parsePingState(result))
    }

    private fun requestBatchPing(sourceId: Long?, profileIds: List<Long>) {
        if (!uiState.value.isRunning && !XrayBatchPingHelper.supportsIsolatedPing()) {
            _effects.tryEmit(
                MainEffect.ShowToast(
                    getApplication<Application>().getString(R.string.pingNotConnected)
                )
            )
            return
        }
        if (profileIds.isEmpty()) {
            _effects.tryEmit(
                MainEffect.ShowToast(
                    getApplication<Application>().getString(R.string.mainNoProfilesInSource)
                )
            )
            return
        }
        activeBatchPingSourceId.value = sourceId
        val updatedStates = profilePingStates.value.toMutableMap()
        profileIds.forEach { profileId ->
            updatedStates[profileId] = MainPingState.Loading
        }
        profilePingStates.value = updatedStates
        _effects.tryEmit(
            MainEffect.RunBatchPing(
                sourceId = sourceId,
                profileIds = profileIds,
                restoreProfileId = selectedProfileId.value,
            )
        )
    }

    fun onAction(action: MainAction) {
        when (action) {
            is MainAction.SelectTab -> selectTab(action.linkId)
            is MainAction.UpdateVpnStatus -> updateVpnRunning(action.isRunning)
            is MainAction.PingResultReceived -> {
                val profileId = selectedProfileId.value
                if (profileId != 0L) updatePingResult(profileId, action.result)
            }
            is MainAction.ProfilePingUpdated -> updatePingResult(action.profileId, action.result)
            is MainAction.SetProfilePingState -> setProfilePingState(action.profileId, action.state)
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
            is MainAction.SetBatchPingSource -> {
                activeBatchPingSourceId.value = action.sourceId
            }
            MainAction.ToggleVpnClicked -> _effects.tryEmit(MainEffect.HandleToggleVpn)
            MainAction.PingClicked -> {
                val profileId = selectedProfileId.value
                if (profileId == 0L) return
                if (!uiState.value.isRunning && !XrayBatchPingHelper.supportsIsolatedPing()) {
                    _effects.tryEmit(
                        MainEffect.ShowToast(
                            getApplication<Application>().getString(R.string.pingNotConnected)
                        )
                    )
                    return
                }
                markPingTesting(profileId)
                _effects.tryEmit(MainEffect.RunPing)
            }
            MainAction.PingAllProfilesClicked -> {
                requestBatchPing(
                    sourceId = uiState.value.selectedTabId.takeIf { it != 0L },
                    profileIds = uiState.value.filteredProfiles.map { it.profile.id },
                )
            }
            MainAction.RefreshLinksClicked -> _effects.tryEmit(MainEffect.RefreshLinks)
            MainAction.NewProfileClicked -> _effects.tryEmit(MainEffect.OpenNewProfile)
            MainAction.ScanQrCodeClicked -> _effects.tryEmit(MainEffect.RequestQrCodeScanner)
            MainAction.ImportFromClipboardClicked -> _effects.tryEmit(MainEffect.ImportFromClipboard)
            MainAction.OpenAssetsClicked -> _effects.tryEmit(MainEffect.OpenAssets)
            MainAction.OpenLinksClicked -> _effects.tryEmit(MainEffect.OpenLinks)
            MainAction.OpenLogsClicked -> _effects.tryEmit(MainEffect.OpenLogs)
            MainAction.OpenAppsRoutingClicked -> _effects.tryEmit(MainEffect.OpenAppsRouting)
            MainAction.OpenCoreRoutingClicked -> _effects.tryEmit(MainEffect.OpenCoreRouting)
            MainAction.OpenConfigsClicked -> _effects.tryEmit(MainEffect.OpenConfigs)
            MainAction.OpenSettingsClicked -> _effects.tryEmit(MainEffect.OpenSettings)
        }
    }

    private fun fixIndex(list: List<ProfileList>) = viewModelScope.launch {
        list.forEachIndexed { index, profile ->
            if (profile.index == index) return@forEachIndexed
            profileRepository.updateIndex(index, profile.id)
        }
    }

    private fun extractServerLabel(config: String): String {
        return runCatching {
            val root = JSONObject(config)
            val outbounds = root.optJSONArray("outbounds") ?: JSONArray()
            val firstOutbound = outbounds.optJSONObject(0) ?: JSONObject()
            val settings = firstOutbound.optJSONObject("settings") ?: JSONObject()

            settings.optString("address").takeIf { it.isNotBlank() }
                ?: settings.optJSONArray("vnext")
                    ?.optJSONObject(0)
                    ?.optString("address")
                    ?.takeIf { it.isNotBlank() }
                ?: firstOutbound.optJSONObject("streamSettings")
                    ?.optJSONObject("realitySettings")
                    ?.optString("serverName")
                    ?.takeIf { it.isNotBlank() }
                ?: getApplication<Application>().getString(R.string.noValue)
        }.getOrDefault(getApplication<Application>().getString(R.string.noValue))
    }

    private fun extractProfileSummary(config: String): String {
        return runCatching {
            val root = JSONObject(config)
            val outbounds = root.optJSONArray("outbounds") ?: JSONArray()
            val firstOutbound = outbounds.optJSONObject(0) ?: JSONObject()
            val protocol = firstOutbound.optString("protocol").uppercase().takeIf { it.isNotBlank() }
            val streamSettings = firstOutbound.optJSONObject("streamSettings") ?: JSONObject()
            val network = streamSettings.optString("network").uppercase().takeIf { it.isNotBlank() }
            val security = streamSettings.optString("security").uppercase().takeIf { it.isNotBlank() }

            listOfNotNull(protocol, network, security)
                .joinToString(separator = " • ")
                .ifBlank { getApplication<Application>().getString(R.string.noValue) }
        }.getOrDefault(getApplication<Application>().getString(R.string.noValue))
    }
}
