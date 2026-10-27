package io.github.derundevu.yaxc.viewmodel

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.derundevu.yaxc.Yaxc
import io.github.derundevu.yaxc.database.Config
import io.github.derundevu.yaxc.presentation.configs.ConfigSection
import io.github.derundevu.yaxc.presentation.editor.JsonEditorState
import io.github.derundevu.yaxc.presentation.editor.JsonEditorTools
import io.github.derundevu.yaxc.presentation.editor.JsonRoot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ConfigsEditorViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = getApplication<Yaxc>().configRepository
    val documents = ConfigSection.entries.associateWith {
        JsonEditorState(
            initialText = if (it.isArray) "[]" else "{}",
            root = if (it.isArray) JsonRoot.Array else JsonRoot.Object,
        )
    }
    var selectedSection by mutableStateOf(ConfigSection.Log)
    var modes by mutableStateOf(ConfigSection.entries.associateWith { Config.Mode.Disable })
        private set
    var isLoading by mutableStateOf(true)
        private set
    var isSaving by mutableStateOf(false)
        private set
    private lateinit var config: Config

    init {
        viewModelScope.launch {
            config = withContext(Dispatchers.IO) { repository.get() }
            modes = mapOf(
                ConfigSection.Log to config.logMode,
                ConfigSection.Dns to config.dnsMode,
                ConfigSection.Inbounds to config.inboundsMode,
                ConfigSection.Outbounds to config.outboundsMode,
                ConfigSection.Routing to config.routingMode,
            )
            documents.getValue(ConfigSection.Log).replaceText(config.log)
            documents.getValue(ConfigSection.Dns).replaceText(config.dns)
            documents.getValue(ConfigSection.Inbounds).replaceText(config.inbounds)
            documents.getValue(ConfigSection.Outbounds).replaceText(config.outbounds)
            documents.getValue(ConfigSection.Routing).replaceText(config.routing)
            isLoading = false
        }
    }

    fun selectMode(mode: Config.Mode) {
        modes = modes + (selectedSection to mode)
    }

    suspend fun save(): Boolean {
        if (isLoading || isSaving) return false
        isSaving = true
        val texts = documents.mapValues { it.value.text }
        val selectedModes = modes
        try {
            val invalidSection = withContext(Dispatchers.Default) {
                texts.entries.firstOrNull { (section, text) ->
                    JsonEditorTools.validate(text, documents.getValue(section).root) != null
                }?.key
            }
            if (invalidSection != null) {
                selectedSection = invalidSection
                return false
            }
            val updated = config.copy(
                log = texts.getValue(ConfigSection.Log),
                dns = texts.getValue(ConfigSection.Dns),
                inbounds = texts.getValue(ConfigSection.Inbounds),
                outbounds = texts.getValue(ConfigSection.Outbounds),
                routing = texts.getValue(ConfigSection.Routing),
                logMode = selectedModes.getValue(ConfigSection.Log),
                dnsMode = selectedModes.getValue(ConfigSection.Dns),
                inboundsMode = selectedModes.getValue(ConfigSection.Inbounds),
                outboundsMode = selectedModes.getValue(ConfigSection.Outbounds),
                routingMode = selectedModes.getValue(ConfigSection.Routing),
            )
            withContext(Dispatchers.IO) { repository.update(updated) }
            config = updated
            return true
        } finally {
            isSaving = false
        }
    }
}
