package io.github.derundevu.yaxc.activity

import android.content.Context
import android.content.Intent
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
import io.github.derundevu.yaxc.presentation.designsystem.YaxcAppTheme
import io.github.derundevu.yaxc.presentation.designsystem.components.FeedbackKind
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcFeedbackState
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcMessageDialog
import io.github.derundevu.yaxc.presentation.profile.ProfileScreen
import io.github.derundevu.yaxc.viewmodel.ProfileEditorViewModel
import io.github.derundevu.yaxc.viewmodel.ProfileSaveResult
import kotlinx.coroutines.launch

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
            if (config.isNotEmpty()) it.putExtra(PROFILE_CONFIG, config.replace("\\/", "/"))
            linkId?.let { value -> it.putExtra(PROFILE_LINK_ID, value) }
        }
    }

    private val feedback = YaxcFeedbackState()
    private var invalidProfileMessage by mutableStateOf<String?>(null)
    private val draft: ProfileEditorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        window.navigationBarColor = Color.TRANSPARENT
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        val id = intent.getLongExtra(PROFILE_ID, 0L)
        draft.initialize(
            id = id,
            name = intent.getStringExtra(PROFILE_NAME).orEmpty(),
            text = intent.getStringExtra(PROFILE_CONFIG).orEmpty(),
            sourceId = intent.getLongExtra(PROFILE_LINK_ID, 0L).takeIf { it != 0L },
            file = intent.data.takeIf { intent.action == Intent.ACTION_VIEW },
        )

        setContent {
            YaxcAppTheme(feedback = feedback) {
                ProfileScreen(
                    title = getString(if (id == 0L) R.string.newProfile else R.string.editProfile),
                    name = draft.name,
                    selectedSourceId = draft.selectedSourceId,
                    availableSources = draft.sources,
                    isLoading = draft.isLoading,
                    isSaving = draft.isSaving,
                    editorState = draft.document,
                    onBack = ::finish,
                    onSave = ::save,
                    onNameChange = { draft.name = it },
                    onSourceChange = { draft.selectedSourceId = it },
                )
                invalidProfileMessage?.let { message ->
                    YaxcMessageDialog(
                        title = getString(R.string.invalidProfile),
                        message = message,
                        confirmLabel = getString(R.string.ignore),
                        onConfirm = {
                            invalidProfileMessage = null
                            save(false)
                        },
                        onDismiss = { invalidProfileMessage = null },
                        dismissLabel = getString(R.string.cancel),
                        kind = FeedbackKind.Error,
                    )
                }
            }
        }
    }

    private fun save(check: Boolean = true) {
        if (draft.isLoading || draft.isSaving) return
        lifecycleScope.launch {
            when (val result = draft.save(check)) {
                ProfileSaveResult.Saved -> finish()
                ProfileSaveResult.NotReady -> Unit
                is ProfileSaveResult.Invalid -> showError(result.message)
            }
        }
    }

    private fun showError(message: String) {
        invalidProfileMessage = message.ifBlank { getString(R.string.feedbackOperationFailed) }
    }
}
