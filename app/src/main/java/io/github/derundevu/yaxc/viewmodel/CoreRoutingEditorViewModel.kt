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
import io.github.derundevu.yaxc.presentation.editor.JsonEditorState
import io.github.derundevu.yaxc.presentation.editor.JsonEditorTools
import io.github.derundevu.yaxc.service.TProxyService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class CoreRoutingEditorViewModel(application: Application) : AndroidViewModel(application) {
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
                    val json = withContext(Dispatchers.Default) { buildJson(snapshot) }
                    document.replaceText(json)
                    visualDirty = false
                }
            } else {
                val raw = document.text
                val stored = rules
                val parsed = withContext(Dispatchers.Default) {
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
            val prepared = withContext(Dispatchers.Default) {
                runCatching {
                    if (mode == CoreRoutingEditorMode.Json) {
                        JsonEditorTools.validate(raw, document.root)?.let { return@runCatching null }
                    }
                    val parsed = if (mode == CoreRoutingEditorMode.Json) {
                        CoreRoutingHelper.parseRoutingJson(raw, snapshot.rules, defaultRuleName)
                    } else snapshot
                    val json = if (mode == CoreRoutingEditorMode.Json) raw else buildJson(parsed)
                    val routingMode = if (CoreRoutingHelper.isMeaningfulRoutingJson(json)) {
                        Config.Mode.Merge
                    } else Config.Mode.Disable
                    val updated = config.copy(
                        routing = if (routingMode == Config.Mode.Disable) "{}" else json,
                        routingMode = routingMode,
                    )
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

    suspend fun importRules(raw: String): Int? {
        if (isLoading || isBusy) return null
        isBusy = true
        val fallback = domainStrategy
        try {
            val imported = withContext(Dispatchers.Default) {
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
            return withContext(Dispatchers.Default) {
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
