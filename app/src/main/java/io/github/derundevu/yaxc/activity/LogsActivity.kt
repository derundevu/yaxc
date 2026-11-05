package io.github.derundevu.yaxc.activity

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.derundevu.yaxc.R
import io.github.derundevu.yaxc.presentation.designsystem.YaxcAppTheme
import io.github.derundevu.yaxc.presentation.designsystem.yaxcIsLightTheme
import io.github.derundevu.yaxc.presentation.designsystem.components.FeedbackKind
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcFeedbackState
import io.github.derundevu.yaxc.presentation.logs.LogEntry
import io.github.derundevu.yaxc.presentation.logs.LogsScreen
import io.github.derundevu.yaxc.viewmodel.LogsViewModel
import io.github.derundevu.yaxc.viewmodel.logsClipboardText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LogsActivity : AppCompatActivity() {
    private val feedback = YaxcFeedbackState()
    private val model: LogsViewModel by viewModels()
    private var copyJob: Job? = null
    private val exportDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) {
        model.completeExport(it)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.isNavigationBarContrastEnforced = false

        setContent {
            val state by model.uiState.collectAsStateWithLifecycle()
            YaxcAppTheme(feedback = feedback) {
                val light = yaxcIsLightTheme()
                SideEffect {
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = light
                        isAppearanceLightNavigationBars = light
                    }
                }
                LogsScreen(
                    state = state,
                    onBack = ::finish,
                    onQueryChange = model::setQuery,
                    onLevelChange = model::setLevel,
                    onSourceChange = model::setSource,
                    onTogglePause = model::togglePause,
                    onClear = model::clear,
                    onCopyEntry = { copyEntries(listOf(it)) },
                    onCopyVisible = { copyEntries(model.uiState.value.entries) },
                    onExportVisible = ::exportVisible,
                )
            }
        }
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.exportResults.collect { success ->
                    feedback.show(
                        getString(if (success) R.string.logsExported else R.string.logsExportFailed),
                        if (success) FeedbackKind.Success else FeedbackKind.Error,
                    )
                }
            }
        }
    }

    private fun copyEntries(entries: List<LogEntry>) {
        if (entries.isEmpty() || copyJob?.isActive == true) return
        copyJob = lifecycleScope.launch {
            try {
                val text = withContext(Dispatchers.Default) { logsClipboardText(entries) }
                if (text == null) {
                    feedback.show(getString(R.string.logsClipboardTooLarge), FeedbackKind.Warning)
                    return@launch
                }
                val clip = ClipData.newPlainText(getString(R.string.logs), text)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    clip.description.extras = PersistableBundle().apply {
                        putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                    }
                }
                getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
                feedback.show(getString(R.string.logsCopied), FeedbackKind.Success)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                feedback.show(getString(R.string.logsCopyFailed), FeedbackKind.Error)
            }
        }
    }

    private fun exportVisible() {
        if (!model.prepareExport()) return
        try {
            exportDocument.launch("yaxc-logs-${System.currentTimeMillis()}.txt")
        } catch (_: Exception) {
            model.completeExport(null)
            feedback.show(getString(R.string.logsExportFailed), FeedbackKind.Error)
        }
    }
}
