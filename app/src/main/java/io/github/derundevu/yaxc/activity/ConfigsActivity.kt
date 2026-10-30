package io.github.derundevu.yaxc.activity

import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import io.github.derundevu.yaxc.R
import io.github.derundevu.yaxc.presentation.configs.ConfigsScreen
import io.github.derundevu.yaxc.presentation.designsystem.YaxcAppTheme
import io.github.derundevu.yaxc.presentation.designsystem.components.FeedbackKind
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcFeedbackState
import io.github.derundevu.yaxc.viewmodel.ConfigsEditorViewModel
import kotlinx.coroutines.launch

class ConfigsActivity : AppCompatActivity() {
    private val feedback = YaxcFeedbackState()
    private val draft: ConfigsEditorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        window.navigationBarColor = Color.TRANSPARENT
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        setContent {
            YaxcAppTheme(feedback = feedback) {
                ConfigsScreen(
                    selectedSection = draft.selectedSection,
                    currentMode = draft.modes.getValue(draft.selectedSection),
                    isLoading = draft.isLoading,
                    isSaving = draft.isSaving,
                    editorState = draft.documents.getValue(draft.selectedSection),
                    onBack = ::finish,
                    onSave = ::saveConfigs,
                    onSectionSelected = { draft.selectedSection = it },
                    onModeSelected = draft::selectMode,
                )
            }
        }
    }

    private fun saveConfigs() {
        if (draft.isLoading || draft.isSaving) return
        lifecycleScope.launch {
            if (draft.save()) finish()
            else feedback.show(getString(R.string.invalidConfig), FeedbackKind.Error)
        }
    }
}
