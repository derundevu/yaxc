package io.github.derundevu.yaxc.activity

import XrayCore.XrayCore
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.derundevu.yaxc.BuildConfig
import io.github.derundevu.yaxc.R
import io.github.derundevu.yaxc.Settings
import io.github.derundevu.yaxc.helper.AppUpdateManager
import io.github.derundevu.yaxc.presentation.designsystem.YaxcAppTheme
import io.github.derundevu.yaxc.presentation.root.RootDestination
import io.github.derundevu.yaxc.presentation.root.SettingsHomeScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class SettingsHomeActivity : AppCompatActivity() {

    private val settings by lazy { Settings(applicationContext) }
    private val appUpdateManager by lazy { AppUpdateManager(applicationContext, settings) }

    private val appUpdateDownloadReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val downloadId = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, 0L) ?: 0L
            appUpdateManager.handleDownloadComplete(downloadId)
        }
    }

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
                SettingsHomeScreen(
                    appVersion = BuildConfig.VERSION_NAME,
                    xrayVersion = XrayCore.version(),
                    tun2socksVersion = getString(R.string.tun2socksVersion),
                    appUpdateState = appUpdateManager.uiState,
                    onOpenAssets = { startActivity(Intent(applicationContext, AssetsActivity::class.java)) },
                    onOpenLinks = { startActivity(Intent(applicationContext, LinksActivity::class.java)) },
                    onOpenLogs = { startActivity(Intent(applicationContext, LogsActivity::class.java)) },
                    onOpenSettings = { startActivity(Intent(applicationContext, SettingsActivity::class.java)) },
                    onCheckAppUpdate = ::checkAppUpdate,
                    onDownloadAppUpdate = ::downloadAppUpdate,
                    onInstallAppUpdate = ::installAppUpdate,
                    onSelectDestination = ::navigateRoot,
                )
            }
        }

        lifecycleScope.launch {
            appUpdateManager.refresh()
        }
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    appUpdateManager.syncPendingDownloadState()
                    delay(APP_UPDATE_POLL_INTERVAL_MS)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        appUpdateManager.syncPendingDownloadState()
    }

    override fun onStart() {
        super.onStart()
        IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE).also {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(appUpdateDownloadReceiver, it, RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(appUpdateDownloadReceiver, it)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(appUpdateDownloadReceiver)
    }

    private fun navigateRoot(destination: RootDestination) {
        when (destination) {
            RootDestination.Connect -> startActivity(rootIntent(applicationContext, MainActivity::class.java))
            RootDestination.Routing -> startActivity(rootIntent(applicationContext, RoutingActivity::class.java))
            RootDestination.Settings -> Unit
        }
    }

    private fun downloadAppUpdate() {
        appUpdateManager.startDownload()
    }

    private fun checkAppUpdate() {
        lifecycleScope.launch {
            appUpdateManager.refresh()
        }
    }

    private fun installAppUpdate() {
        appUpdateManager.installDownloadedUpdate()?.let(::startInstallerIntent)
    }

    private fun startInstallerIntent(intent: Intent) {
        runCatching { startActivity(intent) }
            .onFailure {
                Toast.makeText(
                    applicationContext,
                    getString(R.string.appUpdateInstallUnavailable),
                    Toast.LENGTH_SHORT,
                ).show()
            }
    }

    companion object {
        private const val APP_UPDATE_POLL_INTERVAL_MS = 1_500L

        fun rootIntent(context: Context): Intent = rootIntent(context, SettingsHomeActivity::class.java)

        private fun rootIntent(context: Context, clazz: Class<out AppCompatActivity>): Intent {
            return Intent(context, clazz).apply {
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            }
        }
    }
}
