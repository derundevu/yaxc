package io.github.derundevu.yaxc.activity

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.blacksquircle.ui.editorkit.plugin.autoindent.autoIndentation
import com.blacksquircle.ui.editorkit.plugin.base.PluginSupplier
import com.blacksquircle.ui.editorkit.plugin.delimiters.highlightDelimiters
import com.blacksquircle.ui.editorkit.plugin.linenumbers.lineNumbers
import com.blacksquircle.ui.editorkit.widget.TextProcessor
import com.blacksquircle.ui.language.json.JsonLanguage
import io.github.derundevu.yaxc.R
import io.github.derundevu.yaxc.Settings
import io.github.derundevu.yaxc.database.Config
import io.github.derundevu.yaxc.helper.CoreRoutingEditorMode
import io.github.derundevu.yaxc.helper.CoreRoutingHelper
import io.github.derundevu.yaxc.helper.CoreRoutingRule
import io.github.derundevu.yaxc.helper.TransparentProxyHelper
import io.github.derundevu.yaxc.presentation.designsystem.YaxcTheme
import io.github.derundevu.yaxc.presentation.designsystem.YaxcThemeStyle
import io.github.derundevu.yaxc.presentation.routing.CoreRoutingScreen
import io.github.derundevu.yaxc.service.TProxyService
import io.github.derundevu.yaxc.viewmodel.ConfigViewModel
import kotlinx.coroutines.launch
import org.json.JSONObject

class CoreRoutingActivity : AppCompatActivity() {

    private val settings by lazy { Settings(applicationContext) }
    private val transparentProxyHelper by lazy { TransparentProxyHelper(this, settings) }
    private val configViewModel: ConfigViewModel by viewModels()

    private lateinit var config: Config
    private var coreEditorMode by mutableStateOf(CoreRoutingEditorMode.Visual)
    private var coreDomainStrategy by mutableStateOf("IPIfNonMatch")
    private var coreRules by mutableStateOf<List<CoreRoutingRule>>(emptyList())
    private var coreUnsupportedRuleCount by mutableStateOf(0)
    private var isCoreLoading by mutableStateOf(true)

    private var coreRoutingJson = "{}"
    private var preservedRoutingRules: List<JSONObject> = emptyList()
    private var preservedRoutingTopLevel = JSONObject()
    private var routingEditor: TextProcessor? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.navigationBarColor = Color.TRANSPARENT
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        setContent {
            YaxcTheme(style = YaxcThemeStyle.MidnightBlue) {
                CoreRoutingScreen(
                    editorMode = coreEditorMode,
                    domainStrategy = coreDomainStrategy,
                    rules = coreRules,
                    unsupportedRuleCount = coreUnsupportedRuleCount,
                    isLoading = isCoreLoading,
                    onBack = ::finish,
                    onSave = ::saveCoreRouting,
                    onEditorModeChange = ::switchCoreEditorMode,
                    onDomainStrategyChange = { coreDomainStrategy = it },
                    onRuleChange = ::updateCoreRule,
                    onAddRule = ::addCoreRule,
                    onDeleteRule = ::deleteCoreRule,
                    onEditorReady = ::bindRoutingEditor,
                )
            }
        }

        getCoreRouting()
    }

    private fun getCoreRouting() {
        lifecycleScope.launch {
            config = configViewModel.get()
            val storedUiRules = CoreRoutingHelper.parseUiRules(settings.coreRoutingUiRules)
            val parsed = runCatching {
                CoreRoutingHelper.parseRoutingJson(config.routing, storedUiRules)
            }.getOrElse {
                CoreRoutingHelper.parseRoutingJson("{}", storedUiRules)
            }

            coreDomainStrategy = parsed.domainStrategy
            coreRules = parsed.rules
            coreUnsupportedRuleCount = parsed.unsupportedRuleCount
            preservedRoutingRules = parsed.preservedRules
            preservedRoutingTopLevel = parsed.preservedTopLevel
            coreRoutingJson = normalizeRoutingJson(config.routing)
            isCoreLoading = false
            syncEditorContent()
        }
    }

    private fun bindRoutingEditor(textProcessor: TextProcessor) {
        if (routingEditor === textProcessor) return

        val pluginSupplier = PluginSupplier.create {
            lineNumbers {
                lineNumbers = true
                highlightCurrentLine = true
            }
            highlightDelimiters()
            autoIndentation {
                autoIndentLines = true
                autoCloseBrackets = true
                autoCloseQuotes = true
            }
        }

        routingEditor = textProcessor.apply {
            val contentPadding = (14 * resources.displayMetrics.density).toInt()
            language = JsonLanguage()
            plugins(pluginSupplier)
            setBackgroundColor(Color.TRANSPARENT)
            setTextColor(android.graphics.Color.parseColor("#F2F6FC"))
            setHintTextColor(android.graphics.Color.parseColor("#738399"))
            setPadding(contentPadding, contentPadding, contentPadding, contentPadding)
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            val content = coreRoutingJson
            if (text.toString() != content) setTextContent(content)
        }
    }

    private fun switchCoreEditorMode(mode: CoreRoutingEditorMode) {
        if (coreEditorMode == mode) return
        if (mode == CoreRoutingEditorMode.Json) {
            persistVisualRulesToJson()
            coreEditorMode = mode
            syncEditorContent()
            return
        }

        persistRoutingEditor()
        val parsed = runCatching {
            CoreRoutingHelper.parseRoutingJson(coreRoutingJson, coreRules)
        }.getOrElse {
            Toast.makeText(this, getString(R.string.invalidConfig), Toast.LENGTH_SHORT).show()
            return
        }

        coreDomainStrategy = parsed.domainStrategy
        coreRules = parsed.rules
        coreUnsupportedRuleCount = parsed.unsupportedRuleCount
        preservedRoutingRules = parsed.preservedRules
        preservedRoutingTopLevel = parsed.preservedTopLevel
        coreEditorMode = mode
    }

    private fun updateCoreRule(rule: CoreRoutingRule) {
        coreRules = coreRules.map { current ->
            if (current.id == rule.id) rule else current
        }
    }

    private fun addCoreRule() {
        coreRules = coreRules + CoreRoutingHelper.defaultRule(coreRules.size + 1)
    }

    private fun deleteCoreRule(ruleId: String) {
        coreRules = coreRules.filterNot { it.id == ruleId }
    }

    private fun persistRoutingEditor() {
        coreRoutingJson = routingEditor?.text?.toString() ?: coreRoutingJson
    }

    private fun persistVisualRulesToJson() {
        coreRoutingJson = CoreRoutingHelper.buildRoutingJson(
            domainStrategy = coreDomainStrategy,
            rules = coreRules,
            preservedRules = preservedRoutingRules,
            preservedTopLevel = preservedRoutingTopLevel,
        )
    }

    private fun syncEditorContent() {
        val editor = routingEditor ?: return
        if (editor.text.toString() != coreRoutingJson) {
            editor.setTextContent(coreRoutingJson)
        }
    }

    private fun saveCoreRouting(): Boolean {
        if (isCoreLoading || !::config.isInitialized) return false

        val finalRoutingJson: String
        val finalUiRules: List<CoreRoutingRule>
        val finalDomainStrategy: String
        val finalUnsupportedCount: Int
        val finalPreservedRules: List<JSONObject>
        val finalPreservedTopLevel: JSONObject

        if (coreEditorMode == CoreRoutingEditorMode.Json) {
            persistRoutingEditor()
            val parsed = runCatching {
                CoreRoutingHelper.parseRoutingJson(coreRoutingJson, coreRules)
            }.getOrElse {
                Toast.makeText(this, getString(R.string.invalidConfig), Toast.LENGTH_SHORT).show()
                return false
            }
            finalRoutingJson = CoreRoutingHelper.buildRoutingJson(
                domainStrategy = parsed.domainStrategy,
                rules = parsed.rules,
                preservedRules = parsed.preservedRules,
                preservedTopLevel = parsed.preservedTopLevel,
            )
            finalUiRules = parsed.rules
            finalDomainStrategy = parsed.domainStrategy
            finalUnsupportedCount = parsed.unsupportedRuleCount
            finalPreservedRules = parsed.preservedRules
            finalPreservedTopLevel = parsed.preservedTopLevel
        } else {
            finalRoutingJson = CoreRoutingHelper.buildRoutingJson(
                domainStrategy = coreDomainStrategy,
                rules = coreRules,
                preservedRules = preservedRoutingRules,
                preservedTopLevel = preservedRoutingTopLevel,
            )
            finalUiRules = coreRules
            finalDomainStrategy = coreDomainStrategy
            finalUnsupportedCount = coreUnsupportedRuleCount
            finalPreservedRules = preservedRoutingRules
            finalPreservedTopLevel = preservedRoutingTopLevel
        }

        val finalRoutingMode = if (CoreRoutingHelper.isMeaningfulRoutingJson(finalRoutingJson)) {
            Config.Mode.Merge
        } else {
            Config.Mode.Disable
        }

        val routingChanged = config.routing != (if (finalRoutingMode == Config.Mode.Disable) "{}" else finalRoutingJson) ||
                config.routingMode != finalRoutingMode
        val stopService = TProxyService.isActive() && routingChanged

        lifecycleScope.launch {
            if (stopService && settings.transparentProxy) {
                transparentProxyHelper.kill()
            }

            settings.coreRoutingUiRules = CoreRoutingHelper.encodeUiRules(finalUiRules)
            config.routing = if (finalRoutingMode == Config.Mode.Disable) "{}" else finalRoutingJson
            config.routingMode = finalRoutingMode
            configViewModel.update(config)

            coreRoutingJson = finalRoutingJson
            coreRules = finalUiRules
            coreDomainStrategy = finalDomainStrategy
            coreUnsupportedRuleCount = finalUnsupportedCount
            preservedRoutingRules = finalPreservedRules
            preservedRoutingTopLevel = finalPreservedTopLevel

            if (stopService) {
                TProxyService.stop(this@CoreRoutingActivity)
            }
            Toast.makeText(
                this@CoreRoutingActivity,
                getString(R.string.routingSaved),
                Toast.LENGTH_SHORT,
            ).show()
        }
        return true
    }

    private fun normalizeRoutingJson(rawJson: String): String {
        if (rawJson.isBlank()) return "{}"
        return runCatching { JSONObject(rawJson).toString(4) }.getOrElse { "{}" }
    }
}
