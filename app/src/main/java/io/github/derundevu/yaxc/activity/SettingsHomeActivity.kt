package io.github.derundevu.yaxc.activity

import XrayCore.XrayCore
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import io.github.derundevu.yaxc.BuildConfig
import io.github.derundevu.yaxc.R
import io.github.derundevu.yaxc.presentation.designsystem.YaxcTheme
import io.github.derundevu.yaxc.presentation.designsystem.YaxcThemeStyle
import io.github.derundevu.yaxc.presentation.root.RootDestination
import io.github.derundevu.yaxc.presentation.root.SettingsHomeScreen

class SettingsHomeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        setContent {
            YaxcTheme(style = YaxcThemeStyle.MidnightBlue) {
                SettingsHomeScreen(
                    appVersion = BuildConfig.VERSION_NAME,
                    xrayVersion = XrayCore.version(),
                    tun2socksVersion = getString(R.string.tun2socksVersion),
                    onOpenAssets = { startActivity(Intent(applicationContext, AssetsActivity::class.java)) },
                    onOpenLinks = { startActivity(Intent(applicationContext, LinksActivity::class.java)) },
                    onOpenLogs = { startActivity(Intent(applicationContext, LogsActivity::class.java)) },
                    onOpenConfigs = { startActivity(Intent(applicationContext, ConfigsActivity::class.java)) },
                    onOpenSettings = { startActivity(Intent(applicationContext, SettingsActivity::class.java)) },
                    onSelectDestination = ::navigateRoot,
                )
            }
        }
    }

    private fun navigateRoot(destination: RootDestination) {
        when (destination) {
            RootDestination.Connect -> startActivity(rootIntent(applicationContext, MainActivity::class.java))
            RootDestination.Routing -> startActivity(rootIntent(applicationContext, RoutingActivity::class.java))
            RootDestination.Settings -> Unit
        }
    }

    companion object {
        fun rootIntent(context: Context): Intent = rootIntent(context, SettingsHomeActivity::class.java)

        private fun rootIntent(context: Context, clazz: Class<out AppCompatActivity>): Intent {
            return Intent(context, clazz).apply {
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            }
        }
    }
}
