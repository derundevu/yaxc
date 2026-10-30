package io.github.derundevu.yaxc.activity

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import io.github.derundevu.yaxc.R
import io.github.derundevu.yaxc.Settings
import io.github.derundevu.yaxc.helper.CoreRoutingEditorMode
import io.github.derundevu.yaxc.presentation.designsystem.YaxcAppTheme
import io.github.derundevu.yaxc.presentation.designsystem.components.FeedbackKind
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcFeedbackState
import io.github.derundevu.yaxc.presentation.routing.CoreRoutingScreen
import io.github.derundevu.yaxc.service.TProxyService
import io.github.derundevu.yaxc.viewmodel.CoreRoutingEditorViewModel
import kotlinx.coroutines.launch

class CoreRoutingActivity : AppCompatActivity() {
    private val feedback = YaxcFeedbackState()
    private val settings by lazy { Settings(applicationContext) }
    private val clipboardManager by lazy { getSystemService(ClipboardManager::class.java) }
    private val draft: CoreRoutingEditorViewModel by viewModels()
    private var antifilterEnabled by mutableStateOf(false)
    private var antifilterInstalled by mutableStateOf(false)
    private var antifilterRouteCount by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        window.navigationBarColor = Color.TRANSPARENT
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        syncAntifilterState()
        setContent {
            YaxcAppTheme(feedback = feedback) {
                CoreRoutingScreen(
                    editorMode = draft.editorMode,
                    domainStrategy = draft.domainStrategy,
                    rules = draft.rules,
                    unsupportedRuleCount = draft.unsupportedRuleCount,
                    isLoading = draft.isLoading,
                    isBusy = draft.isBusy,
                    savedRevision = draft.savedRevision,
                    editorState = draft.document,
                    antifilterEnabled = antifilterEnabled,
                    antifilterInstalled = antifilterInstalled,
                    antifilterRouteCount = antifilterRouteCount,
                    onBack = ::finish,
                    onSave = ::saveCoreRouting,
                    onAntifilterEnabledChange = ::updateAntifilterEnabled,
                    onImportFromClipboard = ::importRulesFromClipboard,
                    onExportJson = ::exportRulesJson,
                    onEditorModeChange = ::switchCoreEditorMode,
                    onDomainStrategyChange = draft::updateDomainStrategy,
                    onRuleChange = draft::updateRule,
                    onAddRule = draft::addRule,
                    onMoveRule = draft::moveRule,
                    onDeleteRule = draft::deleteRule,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        syncAntifilterState()
    }

    private fun switchCoreEditorMode(mode: CoreRoutingEditorMode) {
        if (draft.isLoading || draft.isBusy) return
        lifecycleScope.launch {
            if (!draft.switchMode(mode)) showMessage(R.string.invalidConfig, FeedbackKind.Error)
        }
    }

    private fun saveCoreRouting() {
        if (draft.isLoading || draft.isBusy) return
        lifecycleScope.launch {
            if (draft.save()) showMessage(R.string.routingSaved, FeedbackKind.Success)
            else showMessage(R.string.invalidConfig, FeedbackKind.Error)
        }
    }

    private fun importRulesFromClipboard() {
        if (draft.isLoading || draft.isBusy) return
        val raw = runCatching {
            clipboardManager.primaryClip?.getItemAt(0)?.text?.toString()?.trim().orEmpty()
        }.getOrDefault("")
        if (raw.isBlank()) {
            showMessage(R.string.routingClipboardEmpty, FeedbackKind.Warning)
            return
        }
        lifecycleScope.launch {
            val count = draft.importRules(raw)
            if (count == null) showMessage(R.string.routingImportFailed, FeedbackKind.Error)
            else feedback.show(getString(R.string.routingImportedCount, count), FeedbackKind.Success)
        }
    }

    private fun exportRulesJson() {
        if (draft.isLoading || draft.isBusy) return
        lifecycleScope.launch {
            val json = draft.exportRules()
            if (json == null) {
                showMessage(R.string.routingExportFailed, FeedbackKind.Error)
            } else {
                clipboardManager.setPrimaryClip(ClipData.newPlainText(null, json))
                showMessage(R.string.routingExported, FeedbackKind.Success)
            }
        }
    }

    private fun updateAntifilterEnabled(enabled: Boolean) {
        if (enabled && !settings.antifilterFile().exists()) {
            showMessage(R.string.installAssetsFirst, FeedbackKind.Warning)
            syncAntifilterState()
            return
        }
        val changed = settings.antifilterEnabled != enabled
        settings.antifilterEnabled = enabled
        syncAntifilterState()
        if (changed && TProxyService.isActive()) TProxyService.restart(this)
    }

    private fun syncAntifilterState() {
        antifilterEnabled = settings.antifilterEnabled
        antifilterInstalled = settings.antifilterFile().exists()
        antifilterRouteCount = if (antifilterInstalled) settings.installedAntifilterRouteCount else 0
    }

    private fun showMessage(message: Int, kind: FeedbackKind) {
        feedback.show(getString(message), kind)
    }
}
