package io.github.derundevu.yaxc.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.derundevu.yaxc.R
import io.github.derundevu.yaxc.Settings
import io.github.derundevu.yaxc.Yaxc
import io.github.derundevu.yaxc.database.Link
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
        val pingState: MainPingState,
    )

    private val settings = Settings(application)
    private val linkRepository by lazy { getApplication<Yaxc>().linkRepository }
    private val profileRepository by lazy { getApplication<Yaxc>().profileRepository }

    private val selectedTabId = MutableStateFlow(settings.selectedLink)
    private val selectedProfileId = MutableStateFlow(settings.selectedProfile)
    private val isRunning = MutableStateFlow(false)
    private val pingState = MutableStateFlow<MainPingState>(MainPingState.Idle)
    private val _effects = MutableSharedFlow<MainEffect>(extraBufferCapacity = 16)

    private val tabs = linkRepository.tabs.flowOn(Dispatchers.IO)
    private val profiles = profileRepository.all.flowOn(Dispatchers.IO)
    private val selection = combine(selectedTabId, selectedProfileId) { selectedTabId, selectedProfileId ->
        SelectionState(
            selectedTabId = selectedTabId,
            selectedProfileId = selectedProfileId,
        )
    }
    private val runtime = combine(isRunning, pingState) { isRunning, pingState ->
        RuntimeState(
            isRunning = isRunning,
            pingState = pingState,
        )
    }

    val uiState = combine(
        tabs,
        profiles,
        selection,
        runtime,
    ) { tabs: List<Link>, profiles: List<ProfileList>, selection: SelectionState, runtime: RuntimeState ->
        val resolvedTabId = selection.selectedTabId.takeIf { selected ->
            selected == 0L || tabs.any { it.id == selected }
        } ?: 0L
        val selectedSourceName = tabs.firstOrNull { it.id == resolvedTabId }?.name
            ?: application.getString(R.string.mainNoSourceSelected)
        val filteredProfiles = profiles
            .filter { resolvedTabId == 0L || it.link == resolvedTabId }
            .map { profile ->
                MainProfileItem(
                    profile = profile,
                    summary = extractProfileSummary(profile.config),
                )
            }
        val selectedProfile = profiles.firstOrNull { it.id == selection.selectedProfileId }
        val selectedProfileName = selectedProfile?.name.orEmpty()
        val selectedServerLabel = selectedProfile
            ?.config
            ?.let(::extractServerLabel)
            ?: application.getString(R.string.noValue)

        MainUiState(
            tabs = tabs,
            selectedTabId = resolvedTabId,
            selectedSourceName = selectedSourceName,
            filteredProfiles = filteredProfiles,
            selectedProfileId = selection.selectedProfileId,
            selectedProfileName = selectedProfileName,
            selectedServerLabel = selectedServerLabel,
            profilesCount = filteredProfiles.size,
            isRunning = runtime.isRunning,
            pingState = runtime.pingState,
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
                if (selectedTabId.value != 0L && tabs.none { it.id == selectedTabId.value }) {
                    selectTab(0L)
                }
            }
        }
        viewModelScope.launch {
            profiles.collect { profiles ->
                fixIndex(profiles)
                if (selectedProfileId.value != 0L && profiles.none { it.id == selectedProfileId.value }) {
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
        if (!running) pingState.value = MainPingState.Idle
    }

    fun markPingTesting() {
        if (!isRunning.value) return
        pingState.value = MainPingState.Loading
    }

    fun updatePingResult(result: String) {
        val pingLabel = "(\\d+)\\s*ms".toRegex()
            .find(result)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { "$it ms" }
        pingState.value = if (pingLabel == null) MainPingState.Idle else MainPingState.Success(pingLabel)
    }

    fun onAction(action: MainAction) {
        when (action) {
            is MainAction.SelectTab -> selectTab(action.linkId)
            is MainAction.UpdateVpnStatus -> updateVpnRunning(action.isRunning)
            is MainAction.PingResultReceived -> updatePingResult(action.result)
            is MainAction.SelectProfile -> {
                if (selectedProfileId.value == action.profileId) return
                selectProfile(action.profileId)
                if (uiState.value.isRunning) {
                    _effects.tryEmit(MainEffect.ReloadCurrentConfig)
                }
            }
            is MainAction.PingSourceClicked -> {
                _effects.tryEmit(
                    MainEffect.ShowToast(
                        getApplication<Application>().getString(R.string.mainBatchPingPending)
                    )
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
            MainAction.ToggleVpnClicked -> _effects.tryEmit(MainEffect.HandleToggleVpn)
            MainAction.PingClicked -> {
                if (!uiState.value.isRunning) return
                markPingTesting()
                _effects.tryEmit(MainEffect.RunPing)
            }
            MainAction.PingAllProfilesClicked -> {
                _effects.tryEmit(
                    MainEffect.ShowToast(
                        getApplication<Application>().getString(R.string.mainBatchPingPending)
                    )
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
