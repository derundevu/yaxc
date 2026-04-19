package io.github.derundevu.yaxc.activity

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import io.github.derundevu.yaxc.presentation.designsystem.YaxcAppTheme
import io.github.derundevu.yaxc.presentation.root.RootDestination
import io.github.derundevu.yaxc.presentation.root.RoutingHomeScreen

class RoutingActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        setContent {
            YaxcAppTheme {
                RoutingHomeScreen(
                    onOpenAppsRouting = {
                        startActivity(Intent(applicationContext, AppsRoutingActivity::class.java))
                    },
                    onOpenCoreRouting = {
                        startActivity(Intent(applicationContext, CoreRoutingActivity::class.java))
                    },
                    onSelectDestination = ::navigateRoot,
                )
            }
        }
    }

    private fun navigateRoot(destination: RootDestination) {
        when (destination) {
            RootDestination.Connect -> startActivity(rootIntent(applicationContext, MainActivity::class.java))
            RootDestination.Routing -> Unit
            RootDestination.Settings -> startActivity(rootIntent(applicationContext, SettingsHomeActivity::class.java))
        }
    }

    companion object {
        fun rootIntent(context: Context): Intent = rootIntent(context, RoutingActivity::class.java)

        private fun rootIntent(context: Context, clazz: Class<out AppCompatActivity>): Intent {
            return Intent(context, clazz).apply {
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            }
        }
    }
}
