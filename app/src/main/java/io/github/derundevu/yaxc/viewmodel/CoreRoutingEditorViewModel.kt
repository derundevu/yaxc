package io.github.derundevu.yaxc.viewmodel

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.derundevu.yaxc.R
import io.github.derundevu.yaxc.Settings
import io.github.derundevu.yaxc.Yaxc
import io.github.derundevu.yaxc.database.Config
import io.github.derundevu.yaxc.helper.CoreRoutingEditorMode
import io.github.derundevu.yaxc.helper.CoreRoutingHelper
import io.github.derundevu.yaxc.helper.CoreRoutingRule
import io.github.derundevu.yaxc.helper.CoreRoutingRuleSlot
import io.github.derundevu.yaxc.helper.ParsedCoreRouting
import io.github.derundevu.yaxc.helper.CoreRoutingImportHelper
import io.github.derundevu.yaxc.helper.CoreRoutingImportProfile
import io.github.derundevu.yaxc.helper.CoreRoutingImportPreview
import io.github.derundevu.yaxc.helper.CoreRoutingImportResult
import kotlinx.coroutines.CancellationException
import io.github.derundevu.yaxc.presentation.editor.JsonEditorState
import io.github.derundevu.yaxc.presentation.editor.JsonEditorTools
import io.github.derundevu.yaxc.service.TProxyService
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject

class CoreRoutingEditorViewModel internal constructor(
    application: Application,
    private val computationDispatcher: CoroutineDispatcher,
) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, Dispatchers.Default)

    private val app = getApplication<Yaxc>()
    private val settings = Settings(application)
    val document = JsonEditorState("{}")
    var editorMode by mutableStateOf(CoreRoutingEditorMode.Visual)
        private set
    var domainStrategy by mutableStateOf("IPIfNonMatch")
        private set
    var rules by mutableStateOf<List<CoreRoutingRule>>(emptyList())
        private set
    var unsupportedRuleCount by mutableIntStateOf(0)
        private set
    var isLoading by mutableStateOf(true)
        private set
    var isBusy by mutableStateOf(false)
        private set
    var savedRevision by mutableIntStateOf(0)
        private set
    var routingMode by mutableStateOf(Config.Mode.Merge)
        private set
    var importProfiles by mutableStateOf<List<CoreRoutingImportProfile>>(emptyList())
        private set
    var importPreview by mutableStateOf<CoreRoutingImportPreview?>(null)
        private set
    var importVisible by mutableStateOf(false)
        private set
    var importError by mutableStateOf<String?>(null)
        private set
    val runtimeInboundTag: String get() = if (settings.transparentProxy) "all-in" else "socks"
    private lateinit var config: Config
    private var ruleOrder: List<CoreRoutingRuleSlot> = emptyList()
    private var preservedTopLevel = JSONObject()
    private var visualDirty = false
    private val defaultRuleName: (Int) -> String = { app.getString(R.string.routingRuleDefaultName, it) }

    init {
        viewModelScope.launch {
            val (loaded, parsed) = withContext(Dispatchers.IO) {
                val loaded = app.configRepository.get()
                val stored = CoreRoutingHelper.parseUiRules(settings.coreRoutingUiRules)
                val parsed = runCatching {
                    CoreRoutingHelper.parseRoutingJson(loaded.routing, stored, defaultRuleName)
                }.getOrElse {
                    CoreRoutingHelper.parseRoutingJson("{}", stored, defaultRuleName)
                }
                loaded to parsed
            }
            config = loaded
            routingMode = loaded.routingMode
            applyParsed(parsed)
            document.replaceText(loaded.routing)
            isLoading = false
        }
    }

    suspend fun switchMode(mode: CoreRoutingEditorMode): Boolean {
        if (isLoading || isBusy || editorMode == mode) return true
        isBusy = true
        try {
            if (mode == CoreRoutingEditorMode.Json) {
                if (visualDirty) {
                    val snapshot = visualSnapshot()
                    val json = withContext(computationDispatcher) { buildJson(snapshot) }
                    document.replaceText(json)
                    visualDirty = false
                }
            } else {
                val raw = document.text
                val stored = rules
                val parsed = withContext(computationDispatcher) {
                    if (JsonEditorTools.validate(raw, document.root) != null) null
                    else runCatching { CoreRoutingHelper.parseRoutingJson(raw, stored, defaultRuleName) }.getOrNull()
                } ?: return false
                if (document.text != raw) return false
                applyParsed(parsed)
                visualDirty = false
            }
            editorMode = mode
            return true
        } finally {
            isBusy = false
        }
    }

    fun updateDomainStrategy(value: String) {
        if (isLoading || isBusy || domainStrategy == value) return
        domainStrategy = value
        visualDirty = true
    }

    fun updateRule(rule: CoreRoutingRule) {
        if (isLoading || isBusy) return
        rules = rules.map { if (it.id == rule.id) rule else it }
        visualDirty = true
    }

    fun addRule() {
        if (isLoading || isBusy) return
        rules = rules + CoreRoutingHelper.defaultRule(rules.size + 1, defaultRuleName(rules.size + 1))
        visualDirty = true
    }

    fun deleteRule(id: String) {
        if (isLoading || isBusy) return
        rules = rules.filterNot { it.id == id }
        visualDirty = true
    }

    fun moveRule(from: Int, to: Int) {
        if (isLoading || isBusy || from == to || from !in rules.indices || to !in rules.indices) return
        rules = rules.toMutableList().apply { add(to, removeAt(from)) }
        visualDirty = true
    }

    suspend fun save(): Boolean {
        if (isLoading || isBusy) return false
        isBusy = true
        val mode = editorMode
        val raw = document.text
        val snapshot = visualSnapshot()
        try {
            val prepared = withContext(computationDispatcher) {
                runCatching {
                    if (mode == CoreRoutingEditorMode.Json) {
                        JsonEditorTools.validate(raw, document.root)?.let { return@runCatching null }
                    }
                    val parsed = if (mode == CoreRoutingEditorMode.Json) {
                        CoreRoutingHelper.parseRoutingJson(raw, snapshot.rules, defaultRuleName)
                    } else snapshot
                    val json = if (mode == CoreRoutingEditorMode.Json) raw else buildJson(parsed)
                    val updated = config.copy(routing = json, routingMode = routingMode)
                    PreparedRouting(parsed, json, CoreRoutingHelper.encodeUiRules(parsed.rules, parsed.ruleOrder), updated)
                }.getOrNull()
            } ?: return false
            val changed = config.routing != prepared.config.routing || config.routingMode != prepared.config.routingMode
            withContext(Dispatchers.IO) {
                app.configRepository.update(prepared.config)
                settings.coreRoutingUiRules = prepared.uiRules
            }
            config = prepared.config
            // A JSON save validates/persists a snapshot; only Format rewrites the visible source.
            if (mode == CoreRoutingEditorMode.Visual) document.replaceText(prepared.json)
            applyParsed(prepared.parsed)
            visualDirty = false
            savedRevision += 1
            if (changed && TProxyService.isActive()) TProxyService.restart(app)
            return true
        } finally {
            isBusy = false
        }
    }
    fun updateRoutingMode(value: Config.Mode) {
        if (!isLoading && !isBusy) routingMode = value
    }

    suspend fun openProfileImport() {
        if (isLoading || isBusy) return
        isBusy = true
        importError = null
        try {
            importProfiles = withContext(Dispatchers.IO) {
                val visible = app.profileRepository.all.first().map {
                    CoreRoutingImportProfile(it.id, it.name, it.config)
                }
                val inactive = app.linkRepository.all.first().filterNot { it.isActive }.flatMap { link ->
                    app.profileRepository.linkProfiles(link.id).map {
                        CoreRoutingImportProfile(it.id, it.name, it.config)
                    }
                }
                (visible + inactive).distinctBy { it.id }
            }
            importPreview = null
            importVisible = true
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            importError = error.message ?: app.getString(R.string.routingImportFailed)
            importVisible = true
        } finally {
            isBusy = false
        }
    }

    fun closeProfileImport() {
        if (!isBusy) {
            importVisible = false
            importPreview = null
            importError = null
        }
    }

    suspend fun previewProfile(id: Long) {
        if (isLoading || isBusy) return
        isBusy = true
        importError = null
        try {
            // Refresh the selected row; subscription refreshes may have changed it since opening the chooser.
            val profile = withContext(Dispatchers.IO) { app.profileRepository.find(id) }
            importPreview = withContext(computationDispatcher) {
                CoreRoutingImportHelper.preview(CoreRoutingImportProfile(profile.id, profile.name, profile.config))
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            importPreview = null
            importError = error.message ?: app.getString(R.string.routingImportFailed)
        } finally {
            isBusy = false
        }
    }

    suspend fun importProfileRules(
        selected: Set<Int>,
        outboundMappings: Map<String, String>,
        inboundMappings: Map<String, String>,
        replace: Boolean,
        importDomainStrategy: Boolean,
    ): CoreRoutingImportResult? {
        if (isLoading || isBusy) return null
        val preview = importPreview ?: return null
        val raw = if (editorMode == CoreRoutingEditorMode.Json) document.text else buildJson(visualSnapshot())
        isBusy = true
        importError = null
        try {
            val result = withContext(computationDispatcher) {
                require(JsonEditorTools.validate(raw, document.root) == null) { app.getString(R.string.invalidConfig) }
                CoreRoutingImportHelper.importIntoDraft(
                    preview, raw, selected, outboundMappings, inboundMappings, runtimeInboundTag,
                    replace, importDomainStrategy,
                )
            }
            val parsed = withContext(computationDispatcher) {
                CoreRoutingHelper.parseRoutingJson(result.routing, if (replace) emptyList() else rules, defaultRuleName,
                    preserveImportedStructure = true)
            }
            require(editorMode != CoreRoutingEditorMode.Json || document.text == raw) {
                app.getString(R.string.coreImportDraftChanged)
            }
            applyParsed(parsed)
            // Keep complete source rule fields verbatim in the JSON draft; Save remains the only persistence step.
            document.replaceText(result.routing)
            editorMode = CoreRoutingEditorMode.Json
            visualDirty = false
            importVisible = false
            importPreview = null
            return result
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            importError = error.message ?: app.getString(R.string.routingImportFailed)
            return null
        } finally {
            isBusy = false
        }
    }


    suspend fun importRules(raw: String): Int? {
        if (isLoading || isBusy) return null
        isBusy = true
        val fallback = domainStrategy
        try {
            val imported = withContext(computationDispatcher) {
                runCatching {
                    val parsed = CoreRoutingHelper.parseImportedRules(raw, fallback, defaultRuleName)
                    parsed to buildJson(parsed)
                }.getOrNull()
            } ?: return null
            applyParsed(imported.first)
            document.replaceText(imported.second)
            editorMode = CoreRoutingEditorMode.Visual
            visualDirty = false
            return imported.first.rules.size
        } finally {
            isBusy = false
        }
    }

    suspend fun exportRules(): String? {
        if (isLoading || isBusy) return null
        isBusy = true
        val mode = editorMode
        val raw = document.text
        val snapshot = visualSnapshot()
        try {
            return withContext(computationDispatcher) {
                runCatching {
                    val parsed = if (mode == CoreRoutingEditorMode.Json) {
                        if (JsonEditorTools.validate(raw, document.root) != null) return@runCatching null
                        CoreRoutingHelper.parseRoutingJson(raw, snapshot.rules, defaultRuleName)
                    } else snapshot
                    CoreRoutingHelper.buildRulesExchangeJson(
                        parsed.rules,
                        app.getString(R.string.routingRuleDefaultNameShort),
                        parsed.ruleOrder,
                    )
                }.getOrNull()
            }
        } finally {
            isBusy = false
        }
    }

    private fun applyParsed(parsed: ParsedCoreRouting) {
        domainStrategy = parsed.domainStrategy
        rules = parsed.rules
        unsupportedRuleCount = parsed.unsupportedRuleCount
        ruleOrder = parsed.ruleOrder
        preservedTopLevel = parsed.preservedTopLevel
    }

    private fun visualSnapshot() = ParsedCoreRouting(
        domainStrategy = domainStrategy,
        rules = rules,
        ruleOrder = ruleOrder,
        preservedTopLevel = preservedTopLevel,
        unsupportedRuleCount = unsupportedRuleCount,
    )

    private fun buildJson(parsed: ParsedCoreRouting): String = CoreRoutingHelper.buildRoutingJson(
        domainStrategy = parsed.domainStrategy,
        rules = parsed.rules,
        ruleOrder = parsed.ruleOrder,
        preservedTopLevel = parsed.preservedTopLevel,
    )

    private data class PreparedRouting(
        val parsed: ParsedCoreRouting,
        val json: String,
        val uiRules: String,
        val config: Config,
    )
}
