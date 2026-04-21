package io.github.derundevu.yaxc.activity

import XrayCore.XrayCore
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.View
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.derundevu.yaxc.R
import io.github.derundevu.yaxc.Settings
import io.github.derundevu.yaxc.Yaxc
import io.github.derundevu.yaxc.database.Config
import io.github.derundevu.yaxc.database.Link
import io.github.derundevu.yaxc.database.Profile
import io.github.derundevu.yaxc.helper.ConfigHelper
import io.github.derundevu.yaxc.helper.FileHelper
import io.github.derundevu.yaxc.presentation.designsystem.YaxcAppTheme
import io.github.derundevu.yaxc.presentation.designsystem.YaxcThemeStyle
import io.github.derundevu.yaxc.presentation.profile.ProfileSourceOption
import io.github.derundevu.yaxc.presentation.profile.ProfileScreen
import io.github.derundevu.yaxc.viewmodel.ConfigViewModel
import io.github.derundevu.yaxc.viewmodel.ProfileViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

class ProfileActivity : AppCompatActivity() {

    companion object {
        private const val PROFILE_ID = "id"
        private const val PROFILE_NAME = "name"
        private const val PROFILE_CONFIG = "config"
        private const val PROFILE_LINK_ID = "link_id"

        fun getIntent(
            context: Context,
            id: Long = 0L,
            name: String = "",
            config: String = "",
            linkId: Long? = null,
        ) = Intent(context, ProfileActivity::class.java).also {
            it.putExtra(PROFILE_ID, id)
            if (name.isNotEmpty()) it.putExtra(PROFILE_NAME, name)
            if (config.isNotEmpty()) it.putExtra(
                PROFILE_CONFIG,
                config.replace("\\/", "/")
            )
            linkId?.let { value -> it.putExtra(PROFILE_LINK_ID, value) }
        }
    }

    private val settings by lazy { Settings(applicationContext) }
    private val linkRepository by lazy { (application as Yaxc).linkRepository }
    private val configViewModel: ConfigViewModel by viewModels()
    private val profileViewModel: ProfileViewModel by viewModels()

    private lateinit var config: Config
    private lateinit var profile: Profile
    private var id: Long = 0L
    private var isLoading by mutableStateOf(true)
    private var profileName by mutableStateOf("")
    private var profileConfigText by mutableStateOf("")
    private var availableSources by mutableStateOf<List<ProfileSourceOption>>(emptyList())
    private var selectedSourceId by mutableStateOf<Long?>(null)
    private var editor: TextProcessor? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        id = intent.getLongExtra(PROFILE_ID, 0L)

        setContent {
            YaxcAppTheme {
                ProfileScreen(
                    title = if (isNew()) getString(R.string.newProfile) else getString(R.string.editProfile),
                    name = profileName,
                    selectedSourceId = selectedSourceId,
                    availableSources = availableSources,
                    isLoading = isLoading,
                    onBack = ::finish,
                    onSave = ::save,
                    onNameChange = { profileName = it },
                    onSourceChange = { selectedSourceId = it },
                    onEditorReady = ::bindEditor,
                )
            }
        }

        lifecycleScope.launch {
            config = configViewModel.get()
            val resolvedProfile = resolveInitialProfileModel()
            val (sources, selectedGroupId) = prepareSourceOptions(resolvedProfile)

            withContext(Dispatchers.Main) {
                profile = resolvedProfile
                profileName = profile.name
                profileConfigText = profile.config
                availableSources = sources
                selectedSourceId = selectedGroupId
                editor?.setTextContent(profile.config)
                isLoading = false
            }
        }
    }

    private fun isNew() = id == 0L

    private suspend fun resolveInitialProfileModel(): Profile {
        val requestedLinkId = intent.getLongExtra(PROFILE_LINK_ID, 0L).takeIf { it != 0L }
        val jsonUri = intent.data
        return when {
            Intent.ACTION_VIEW == intent.action && jsonUri != null -> {
                Profile().also {
                    it.linkId = requestedLinkId
                    it.config = readJsonFile(jsonUri)
                }
            }

            isNew() -> {
                Profile().also {
                    it.linkId = requestedLinkId
                    it.name = intent.getStringExtra(PROFILE_NAME) ?: ""
                    it.config = intent.getStringExtra(PROFILE_CONFIG) ?: ""
                }
            }

            else -> profileViewModel.find(id)
        }
    }

    private fun readJsonFile(uri: Uri): String {
        val content = StringBuilder()
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                BufferedReader(InputStreamReader(input)).forEachLine { content.append("$it\n") }
            }
        } catch (_: Exception) {
        }
        return content.toString()
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
            if (text.toString() != profileConfigText) {
                setTextContent(profileConfigText)
            }
        }
    }

    private fun save(check: Boolean = true) {
        if (isLoading || !::config.isInitialized) return

        profile.linkId = selectedSourceId
        profile.name = profileName
        profile.config = editor?.text?.toString() ?: profileConfigText

        lifecycleScope.launch {
            val configHelper = runCatching { ConfigHelper(settings, config, profile.config) }
            val error = if (configHelper.isSuccess) {
                isValid(configHelper.getOrNull().toString())
            } else {
                configHelper.exceptionOrNull()?.message ?: getString(R.string.invalidProfile)
            }
            if (check && error.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    showError(error)
                }
                return@launch
            }
            if (profile.id == 0L) {
                profileViewModel.create(profile)
            } else {
                profileViewModel.update(profile)
            }
            withContext(Dispatchers.Main) {
                finish()
            }
        }
    }

    private suspend fun loadSources(): List<Link> {
        return withContext(Dispatchers.IO) {
            linkRepository.all.first()
        }
    }

    private suspend fun prepareSourceOptions(profile: Profile): Pair<List<ProfileSourceOption>, Long?> {
        var links = loadSources()
        var manualLinks = links.filter { it.address.isBlank() }

        if (manualLinks.isEmpty()) {
            val defaultGroup = Link(
                name = getString(R.string.defaultManualGroupName),
                address = "",
                type = Link.Type.Json,
                isActive = true,
            )
            defaultGroup.id = linkRepository.insertAndGetId(defaultGroup)
            links = links + defaultGroup
            manualLinks = listOf(defaultGroup)
        }

        val currentLink = links.firstOrNull { it.id == profile.linkId }
        val optionLinks = buildList {
            addAll(manualLinks)
            if (
                !isNew() &&
                currentLink != null &&
                currentLink.address.isNotBlank() &&
                none { it.id == currentLink.id }
            ) {
                add(currentLink)
            }
        }

        val selectedGroupId = when {
            currentLink != null && optionLinks.any { it.id == currentLink.id } -> currentLink.id
            else -> optionLinks.firstOrNull()?.id
        }

        return optionLinks.map { link ->
            ProfileSourceOption(
                linkId = link.id,
                name = link.name.ifBlank { getString(R.string.defaultManualGroupName) },
            )
        } to selectedGroupId
    }

    private suspend fun isValid(json: String): String {
        return withContext(Dispatchers.IO) {
            val pwd = filesDir.absolutePath
            val testConfig = settings.testConfig()
            FileHelper.createOrUpdate(testConfig, json)
            XrayCore.test(pwd, testConfig.absolutePath)
        }
    }

    private fun showError(message: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.invalidProfile))
            .setMessage(message)
            .setNegativeButton(getString(R.string.cancel)) { _, _ -> }
            .setPositiveButton(getString(R.string.ignore)) { _, _ -> save(false) }
            .show()
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
