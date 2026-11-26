package io.github.derundevu.yaxc.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.derundevu.yaxc.R
import io.github.derundevu.yaxc.Settings
import io.github.derundevu.yaxc.Yaxc
import io.github.derundevu.yaxc.database.Config
import io.github.derundevu.yaxc.database.Link
import io.github.derundevu.yaxc.database.Profile
import io.github.derundevu.yaxc.helper.ConfigHelper
import io.github.derundevu.yaxc.helper.XrayValidationHelper
import io.github.derundevu.yaxc.helper.InlineProfileImportHelper
import io.github.derundevu.yaxc.helper.SubscriptionRefreshHelper
import io.github.derundevu.yaxc.presentation.editor.JsonEditorState
import io.github.derundevu.yaxc.presentation.editor.JsonEditorTools
import io.github.derundevu.yaxc.presentation.profile.ProfileEditorMode
import io.github.derundevu.yaxc.presentation.profile.ProfileSourceOption
import io.github.derundevu.yaxc.presentation.profile.ProfileVisualConfig
import io.github.derundevu.yaxc.presentation.profile.ProfileVisualConfigHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface ProfileSaveResult {
    data object Saved : ProfileSaveResult
    data object NotReady : ProfileSaveResult
    data class Invalid(val message: String) : ProfileSaveResult
}

/** Owns the raw document and metadata, never the editor View or an Activity. */
class ProfileEditorViewModel internal constructor(
    application: Application,
    private val validate: suspend (Context, String) -> String,
) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, XrayValidationHelper::validate)

    private val app = getApplication<Yaxc>()
    private val settings = Settings(application)
    val document = JsonEditorState()
    var name by mutableStateOf("")
    var selectedSourceId by mutableStateOf<Long?>(null)
    var sources by mutableStateOf<List<ProfileSourceOption>>(emptyList())
        private set
    var editorMode by mutableStateOf(ProfileEditorMode.Visual)
        private set
    var visualConfig by mutableStateOf(ProfileVisualConfig())
        private set
    var isSwitchingMode by mutableStateOf(false)
        private set
    var isLoading by mutableStateOf(true)
        private set
    var isSaving by mutableStateOf(false)
        private set
    var initialized = false
        private set
    var fileImportPending = false
        private set
    var importReport by mutableStateOf<SubscriptionRefreshHelper.RefreshResult?>(null)
        private set
    var importReportAcknowledged by mutableStateOf(false)
        private set
    private var visualDirty = false
    private lateinit var profile: Profile
    private lateinit var config: Config

    fun initialize(id: Long, name: String, text: String, sourceId: Long?, file: Uri?) {
        if (initialized) return
        initialized = true
        fileImportPending = file != null
        viewModelScope.launch {
            val fileContents = file?.let { withContext(Dispatchers.IO) { readFile(it) } }
            var importedFileProfile: Profile? = null
            if (file != null) {
                val parsed = try {
                    SubscriptionRefreshHelper(app, settings).parseProfiles(Link(), checkNotNull(fileContents))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                if (parsed != null) {
                    if (parsed.profiles.size == 1 && parsed.skippedProfiles.isEmpty()) {
                        importedFileProfile = parsed.profiles.single()
                    } else {
                        importReport = if (parsed.profiles.isEmpty()) {
                            SubscriptionRefreshHelper.RefreshResult(1, 0, 1,
                                warnings = listOf(SubscriptionRefreshHelper.SourceWarning(
                                    0, app.getString(R.string.profileImportTitle), parsed.skippedProfiles,
                                )))
                        } else {
                            try {
                                InlineProfileImportHelper.append(app, settings, parsed, sourceId)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                SubscriptionRefreshHelper.RefreshResult(1, 0, 1)
                            }
                        }
                        return@launch
                    }
                }
            }
            fileImportPending = false
            val fileProfile = importedFileProfile
            val loaded = withContext(Dispatchers.IO) {
                val config = app.configRepository.get()
                val profile = when {
                    fileProfile != null -> fileProfile.copy(linkId = sourceId)
                    file != null -> Profile(linkId = sourceId, config = checkNotNull(fileContents))
                    id == 0L -> Profile(name = name, config = text, linkId = sourceId)
                    else -> app.profileRepository.find(id)
                }
                var links = app.linkRepository.all.first()
                var manualLinks = links.filter { it.address.isBlank() }
                if (manualLinks.isEmpty()) {
                    val group = Link(
                        name = app.getString(R.string.defaultManualGroupName),
                        address = "",
                        type = Link.Type.Json,
                        isActive = true,
                    )
                    group.id = app.linkRepository.insertAndGetId(group)
                    links = links + group
                    manualLinks = listOf(group)
                }
                val current = links.firstOrNull { it.id == profile.linkId }
                val options = buildList {
                    addAll(manualLinks)
                    if (id != 0L && current != null && current.address.isNotBlank() && none { it.id == current.id }) {
                        add(current)
                    }
                }
                val source = current?.id?.takeIf { value -> options.any { it.id == value } }
                    ?: options.firstOrNull()?.id
                LoadedProfile(
                    profile = profile,
                    config = config,
                    sources = options.map {
                        ProfileSourceOption(it.id, it.name.ifBlank { app.getString(R.string.defaultManualGroupName) })
                    },
                    sourceId = source,
                    visualConfig = ProfileVisualConfigHelper.parse(profile.config),
                )
            }
            profile = loaded.profile
            config = loaded.config
            this@ProfileEditorViewModel.name = profile.name
            selectedSourceId = loaded.sourceId
            sources = loaded.sources
            document.replaceText(profile.config)
            if (loaded.visualConfig != null) {
                visualConfig = loaded.visualConfig
                editorMode = ProfileEditorMode.Visual
            } else {
                visualConfig = ProfileVisualConfig()
                editorMode = ProfileEditorMode.Json
            }
            visualDirty = false
            isLoading = false
        }
    }

    suspend fun switchMode(mode: ProfileEditorMode): Boolean {
        if (isLoading || isSaving || isSwitchingMode || editorMode == mode) return true
        isSwitchingMode = true
        try {
            if (mode == ProfileEditorMode.Json) {
                if (visualDirty) {
                    val snapshot = visualConfig
                    val json = withContext(Dispatchers.Default) {
                        ProfileVisualConfigHelper.buildJson(snapshot)
                    }
                    document.replaceText(json)
                    visualDirty = false
                }
            } else {
                val raw = document.text
                val parsed = withContext(Dispatchers.Default) {
                    ProfileVisualConfigHelper.parse(raw)
                } ?: return false
                if (document.text != raw) return false
                visualConfig = parsed
                visualDirty = false
            }
            editorMode = mode
            return true
        } finally {
            isSwitchingMode = false
        }
    }

    fun updateVisualConfig(transform: (ProfileVisualConfig) -> ProfileVisualConfig) {
        if (isLoading || isSaving || isSwitchingMode) return
        visualConfig = transform(visualConfig)
        visualDirty = true
    }

    suspend fun acknowledgeImportReport() = withContext(NonCancellable) {
        val report = importReport ?: return@withContext
        withContext(Dispatchers.IO) {
            settings.acknowledgeSubscriptionWarnings(report.warnings)
        }
        importReportAcknowledged = true
    }


    /** A save is successful only after the captured draft has been persisted. */
    suspend fun save(check: Boolean): ProfileSaveResult {
        if (isLoading || isSaving || isSwitchingMode) return ProfileSaveResult.NotReady
        isSaving = true
        val mode = editorMode
        val dirty = visualDirty
        val visualSnapshot = visualConfig
        val rawText = document.text
        val capturedName = name
        val capturedSourceId = selectedSourceId
        try {
            val (savedConfig, result) = withContext(Dispatchers.IO) {
                val effectiveConfig = if (mode == ProfileEditorMode.Visual && dirty) {
                    ProfileVisualConfigHelper.buildJson(visualSnapshot)
                } else {
                    rawText
                }
                val snapshot = profile.copy(
                    name = capturedName,
                    linkId = capturedSourceId,
                    config = effectiveConfig,
                )
                val error = if (check) {
                    try {
                        val merged = ConfigHelper(settings, config, snapshot.config).toString()
                        validate(app, merged)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        error.message ?: app.getString(R.string.invalidProfile)
                    }
                } else {
                    ""
                }
                if (check && error.isNotEmpty()) {
                    null to ProfileSaveResult.Invalid(error)
                } else {
                    if (snapshot.id == 0L) {
                        snapshot.id = app.profileRepository.create(snapshot)
                        profile = snapshot
                    } else {
                        if (app.profileRepository.update(snapshot) == 0) {
                            return@withContext null to ProfileSaveResult.Invalid(app.getString(R.string.invalidProfile))
                        }
                        profile = snapshot
                    }
                    effectiveConfig to ProfileSaveResult.Saved
                }
            }
            if (result is ProfileSaveResult.Saved && savedConfig != null &&
                mode == ProfileEditorMode.Visual && dirty &&
                visualConfig == visualSnapshot && document.text == rawText
            ) {
                document.replaceText(savedConfig)
                visualDirty = false
            }
            return result
        } finally {
            isSaving = false
        }
    }

    private fun readFile(uri: Uri): String = runCatching {
        app.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
    }.getOrDefault("")

    private data class LoadedProfile(
        val profile: Profile,
        val config: Config,
        val sources: List<ProfileSourceOption>,
        val sourceId: Long?,
        val visualConfig: ProfileVisualConfig?,
    )
}
