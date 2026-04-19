package io.github.derundevu.yaxc.activity

import XrayCore.XrayCore
import android.graphics.Color
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
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
import io.github.derundevu.yaxc.database.Config
import io.github.derundevu.yaxc.database.Profile
import io.github.derundevu.yaxc.helper.ConfigHelper
import io.github.derundevu.yaxc.helper.FileHelper
import io.github.derundevu.yaxc.presentation.designsystem.YaxcAppTheme
import io.github.derundevu.yaxc.presentation.profile.ProfileScreen
import io.github.derundevu.yaxc.viewmodel.ConfigViewModel
import io.github.derundevu.yaxc.viewmodel.ProfileViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

class ProfileActivity : AppCompatActivity() {

    companion object {
        private const val PROFILE_ID = "id"
        private const val PROFILE_NAME = "name"
        private const val PROFILE_CONFIG = "config"

        fun getIntent(
            context: Context, id: Long = 0L, name: String = "", config: String = ""
        ) = Intent(context, ProfileActivity::class.java).also {
            it.putExtra(PROFILE_ID, id)
            if (name.isNotEmpty()) it.putExtra(PROFILE_NAME, name)
            if (config.isNotEmpty()) it.putExtra(
                PROFILE_CONFIG,
                config.replace("\\/", "/")
            )
        }
    }

    private val settings by lazy { Settings(applicationContext) }
    private val configViewModel: ConfigViewModel by viewModels()
    private val profileViewModel: ProfileViewModel by viewModels()

    private lateinit var config: Config
    private lateinit var profile: Profile
    private var id: Long = 0L
    private var isLoading by mutableStateOf(true)
    private var profileName by mutableStateOf("")
    private var profileConfigText by mutableStateOf("")
    private var editor: TextProcessor? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        id = intent.getLongExtra(PROFILE_ID, 0L)

        setContent {
            YaxcAppTheme {
                ProfileScreen(
                    title = if (isNew()) getString(R.string.newProfile) else getString(R.string.editProfile),
                    name = profileName,
                    isLoading = isLoading,
                    onBack = ::finish,
                    onSave = ::save,
                    onNameChange = { profileName = it },
                    onEditorReady = ::bindEditor,
                )
            }
        }

        lifecycleScope.launch {
            config = configViewModel.get()
            resolveInitialProfile()
        }
    }

    private fun isNew() = id == 0L

    private suspend fun resolveInitialProfile() {
        val jsonUri = intent.data
        val resolvedProfile = when {
            Intent.ACTION_VIEW == intent.action && jsonUri != null -> {
                Profile().also { it.config = readJsonFile(jsonUri) }
            }

            isNew() -> {
                Profile().also {
                    it.name = intent.getStringExtra(PROFILE_NAME) ?: ""
                    it.config = intent.getStringExtra(PROFILE_CONFIG) ?: ""
                }
            }

            else -> profileViewModel.find(id)
        }

        withContext(Dispatchers.Main) {
            profile = resolvedProfile
            profileName = profile.name
            profileConfigText = profile.config
            editor?.setTextContent(profile.config)
            isLoading = false
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
            setTextColor(android.graphics.Color.parseColor("#F2F6FC"))
            setHintTextColor(android.graphics.Color.parseColor("#738399"))
            setPadding(contentPadding, contentPadding, contentPadding, contentPadding)
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            if (text.toString() != profileConfigText) {
                setTextContent(profileConfigText)
            }
        }
    }

    private fun save(check: Boolean = true) {
        if (isLoading || !::config.isInitialized) return

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
}
