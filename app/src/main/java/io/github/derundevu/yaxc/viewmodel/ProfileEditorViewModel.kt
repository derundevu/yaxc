package io.github.derundevu.yaxc.viewmodel

import android.app.Application
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
import io.github.derundevu.yaxc.presentation.editor.JsonEditorState
import io.github.derundevu.yaxc.presentation.profile.ProfileSourceOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Owns the raw document and metadata, never the editor View or an Activity. */
class ProfileEditorViewModel(application: Application) : AndroidViewModel(application) {
    private val app = getApplication<Yaxc>()
    private val settings = Settings(application)
    val document = JsonEditorState()
    var name by mutableStateOf("")
    var selectedSourceId by mutableStateOf<Long?>(null)
    var sources by mutableStateOf<List<ProfileSourceOption>>(emptyList())
        private set
    var isLoading by mutableStateOf(true)
        private set
    var isSaving by mutableStateOf(false)
        private set
    private var initialized = false
    private lateinit var profile: Profile
    private lateinit var config: Config

    fun initialize(id: Long, name: String, text: String, sourceId: Long?, file: Uri?) {
        if (initialized) return
        initialized = true
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val config = app.configRepository.get()
                val profile = when {
                    file != null -> Profile(linkId = sourceId, config = readFile(file))
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
                )
            }
            profile = loaded.profile
            config = loaded.config
            this@ProfileEditorViewModel.name = profile.name
            selectedSourceId = loaded.sourceId
            sources = loaded.sources
            document.replaceText(profile.config)
            isLoading = false
        }
    }

    /** Returns the real Xray validation error; null means the draft was persisted. */
    suspend fun save(check: Boolean): String? {
        if (isLoading || isSaving) return null
        isSaving = true
        val snapshot = profile.copy(name = name, linkId = selectedSourceId, config = document.text)
        try {
            return withContext(Dispatchers.IO) {
                val error = if (check) {
                    try {
                        val merged = ConfigHelper(settings, config, snapshot.config).toString()
                        XrayValidationHelper.validate(app, merged)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        error.message ?: app.getString(R.string.invalidProfile)
                    }
                } else {
                    ""
                }
                if (check && error.isNotEmpty()) {
                    error
                } else {
                    if (snapshot.id == 0L) app.profileRepository.create(snapshot)
                    else app.profileRepository.update(snapshot)
                    null
                }
            }
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
    )
}
