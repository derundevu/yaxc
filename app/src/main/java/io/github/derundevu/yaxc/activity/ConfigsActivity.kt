package io.github.derundevu.yaxc.activity

import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import io.github.derundevu.yaxc.presentation.configs.ConfigSection
import io.github.derundevu.yaxc.presentation.configs.ConfigsScreen
import io.github.derundevu.yaxc.presentation.designsystem.YaxcAppTheme
import io.github.derundevu.yaxc.presentation.designsystem.YaxcThemeStyle
import io.github.derundevu.yaxc.viewmodel.ConfigViewModel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class ConfigsActivity : AppCompatActivity() {

    private val settings by lazy { Settings(applicationContext) }
    private val configViewModel: ConfigViewModel by viewModels()
    private val indentSpaces = 4

    private lateinit var config: Config
    private var isLoading by mutableStateOf(true)
    private var selectedSection by mutableStateOf(ConfigSection.Log)
    private var modeBySection by mutableStateOf(
        ConfigSection.entries.associateWith { Config.Mode.Disable }
    )
    private var textBySection by mutableStateOf(
        ConfigSection.entries.associateWith { if (it.isArray) "[]" else "{}" }
    )
    private var editor: TextProcessor? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            YaxcAppTheme {
                ConfigsScreen(
                    selectedSection = selectedSection,
                    currentMode = modeBySection[selectedSection] ?: Config.Mode.Disable,
                    isLoading = isLoading,
                    onBack = ::finish,
                    onSave = ::saveConfigs,
                    onSectionSelected = ::selectSection,
                    onModeSelected = ::selectMode,
                    onEditorReady = ::bindEditor,
                )
            }
        }

        lifecycleScope.launch {
            config = configViewModel.get()
            modeBySection = mapOf(
                ConfigSection.Log to config.logMode,
                ConfigSection.Dns to config.dnsMode,
                ConfigSection.Inbounds to config.inboundsMode,
                ConfigSection.Outbounds to config.outboundsMode,
                ConfigSection.Routing to config.routingMode,
            )
            textBySection = mapOf(
                ConfigSection.Log to config.log,
                ConfigSection.Dns to config.dns,
                ConfigSection.Inbounds to config.inbounds,
                ConfigSection.Outbounds to config.outbounds,
                ConfigSection.Routing to config.routing,
            )
            isLoading = false
            editor?.setTextContent(textBySection[selectedSection].orEmpty())
        }
    }

    private fun bindEditor(textProcessor: TextProcessor) {
        if (editor === textProcessor) return

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

        editor = textProcessor.apply {
            val contentPadding = (14 * resources.displayMetrics.density).toInt()
            language = JsonLanguage()
            plugins(pluginSupplier)
            setBackgroundColor(Color.TRANSPARENT)
            setTextColor(editorTextColor())
            setHintTextColor(editorHintColor())
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setLineSpacing(0f, 1.08f)
            setPadding(contentPadding, contentPadding, contentPadding, contentPadding)
            isVerticalScrollBarEnabled = true
            isHorizontalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            val content = textBySection[selectedSection].orEmpty()
            if (text.toString() != content) setTextContent(content)
        }
    }

    private fun persistCurrentEditor() {
        val currentText = editor?.text?.toString() ?: return
        textBySection = textBySection.toMutableMap().also {
            it[selectedSection] = currentText
        }
    }

    private fun selectSection(section: ConfigSection) {
        if (selectedSection == section) return
        persistCurrentEditor()
        selectedSection = section
        editor?.setTextContent(textBySection[section].orEmpty())
    }

    private fun selectMode(mode: Config.Mode) {
        modeBySection = modeBySection.toMutableMap().also {
            it[selectedSection] = mode
        }
    }

    private fun formatConfig(section: ConfigSection, json: String): String {
        return if (section.isArray) JSONArray(json).toString(indentSpaces)
        else JSONObject(json).toString(indentSpaces)
    }

    private fun saveConfigs() {
        if (isLoading || !::config.isInitialized) return
        persistCurrentEditor()

        runCatching {
            config.log = formatConfig(ConfigSection.Log, textBySection[ConfigSection.Log].orEmpty())
            config.dns = formatConfig(ConfigSection.Dns, textBySection[ConfigSection.Dns].orEmpty())
            config.inbounds = formatConfig(
                ConfigSection.Inbounds,
                textBySection[ConfigSection.Inbounds].orEmpty()
            )
            config.outbounds = formatConfig(
                ConfigSection.Outbounds,
                textBySection[ConfigSection.Outbounds].orEmpty()
            )
            config.routing = formatConfig(
                ConfigSection.Routing,
                textBySection[ConfigSection.Routing].orEmpty()
            )
            config.logMode = modeBySection[ConfigSection.Log] ?: config.logMode
            config.dnsMode = modeBySection[ConfigSection.Dns] ?: config.dnsMode
            config.inboundsMode = modeBySection[ConfigSection.Inbounds] ?: config.inboundsMode
            config.outboundsMode = modeBySection[ConfigSection.Outbounds] ?: config.outboundsMode
            config.routingMode = modeBySection[ConfigSection.Routing] ?: config.routingMode
            config
        }.onSuccess {
            configViewModel.update(it)
            finish()
        }.onFailure {
            Toast.makeText(this, getString(R.string.invalidConfig), Toast.LENGTH_SHORT).show()
        }
    }

    private fun editorTextColor(): Int {
        return if (usesLightEditorPalette()) {
            android.graphics.Color.parseColor("#18212B")
        } else {
            android.graphics.Color.parseColor("#F2F6FC")
        }
    }

    private fun editorHintColor(): Int {
        return if (usesLightEditorPalette()) {
            android.graphics.Color.parseColor("#647180")
        } else {
            android.graphics.Color.parseColor("#738399")
        }
    }

    private fun usesLightEditorPalette(): Boolean {
        return when (settings.themeStyle) {
            YaxcThemeStyle.LightSlate -> true
            YaxcThemeStyle.System -> {
                val nightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                nightMode != Configuration.UI_MODE_NIGHT_YES
            }
            else -> false
        }
    }
}
