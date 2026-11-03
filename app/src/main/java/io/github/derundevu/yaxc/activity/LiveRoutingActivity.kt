package io.github.derundevu.yaxc.activity

import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.derundevu.yaxc.helper.ConnectionFailureState
import io.github.derundevu.yaxc.presentation.designsystem.YaxcAppTheme
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcFeedbackState
import io.github.derundevu.yaxc.presentation.routing.LiveRoutingScreen
import io.github.derundevu.yaxc.viewmodel.LiveRoutingViewModel

class LiveRoutingActivity : AppCompatActivity() {
    private val draft: LiveRoutingViewModel by viewModels()
    private val feedback = YaxcFeedbackState()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        setContent {
            val failure by ConnectionFailureState.failure.collectAsStateWithLifecycle()
            val notice = draft.notice
            LaunchedEffect(notice) {
                if (notice != null) {
                    feedback.show(getString(notice.message), notice.kind)
                    draft.consumeNotice(notice)
                }
            }
            YaxcAppTheme(feedback = feedback) {
                LiveRoutingScreen(
                    draft = draft,
                    connectionFailed = failure != null,
                    onBack = ::finish,
                    onOpenConnection = {
                        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        draft.refreshEnvironment()
    }
}
