package io.github.derundevu.yaxc.activity

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import io.github.derundevu.yaxc.Settings
import io.github.derundevu.yaxc.dto.AppList
import io.github.derundevu.yaxc.presentation.designsystem.YaxcAppTheme
import io.github.derundevu.yaxc.presentation.routing.AppsRoutingScreen
import io.github.derundevu.yaxc.service.TProxyService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppsRoutingActivity : AppCompatActivity() {

    private val settings by lazy { Settings(applicationContext) }

    private var apps by mutableStateOf<List<AppList>>(emptyList())
    private var selectedPackages by mutableStateOf<Set<String>>(emptySet())
    private var appsRoutingMode by mutableStateOf(true)
    private var isLoading by mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.navigationBarColor = Color.TRANSPARENT
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        selectedPackages = selectedPackagesFromSettings()
        appsRoutingMode = settings.appsRoutingMode

        setContent {
            YaxcAppTheme {
                AppsRoutingScreen(
                    apps = apps,
                    isLoading = isLoading,
                    selectedPackages = selectedPackages,
                    appsRoutingMode = appsRoutingMode,
                    onBack = ::finish,
                    onModeChange = { appsRoutingMode = it },
                    onTogglePackage = { packageName ->
                        selectedPackages = selectedPackages.toMutableSet().also { packages ->
                            if (!packages.add(packageName)) packages.remove(packageName)
                        }.toSet()
                    },
                    onSave = ::saveAppsRouting,
                )
            }
        }

        getApps()
    }

    private fun getApps() {
        lifecycleScope.launch {
            val selectedPackages = selectedPackagesFromSettings()
            val apps = withContext(Dispatchers.IO) {
                val selected = ArrayList<AppList>()
                val unselected = ArrayList<AppList>()

                packageManager.getInstalledPackages(PackageManager.GET_PERMISSIONS).forEach {
                    val permissions = it.requestedPermissions
                    if (permissions == null || !permissions.contains(Manifest.permission.INTERNET)) {
                        return@forEach
                    }
                    if (it.packageName == packageName) return@forEach

                    val appIcon = it.applicationInfo!!.loadIcon(packageManager)
                    val appName = it.applicationInfo!!.loadLabel(packageManager).toString()
                    val packageName = it.packageName
                    val app = AppList(appIcon, appName, packageName)

                    if (selectedPackages.contains(packageName)) selected.add(app) else unselected.add(app)
                }

                selected + unselected
            }

            this@AppsRoutingActivity.apps = apps
            this@AppsRoutingActivity.selectedPackages = selectedPackages
            isLoading = false
        }
    }

    private fun saveAppsRouting() {
        val appsRoutingMode = this.appsRoutingMode
        val appsRouting = selectedPackages
            .asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .sorted()
            .joinToString("\n")

        lifecycleScope.launch {
            val tproxySettingsChanged = settings.appsRoutingMode != appsRoutingMode ||
                    settings.appsRouting != appsRouting
            val restartService = tproxySettingsChanged && TProxyService.isActive()

            withContext(Dispatchers.Main) {
                settings.appsRoutingMode = appsRoutingMode
                settings.appsRouting = appsRouting
                if (restartService) TProxyService.restart(this@AppsRoutingActivity)
                finish()
            }
        }
    }

    private fun selectedPackagesFromSettings(): Set<String> {
        return settings.appsRouting
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .filter { it != packageName }
            .toSet()
    }
}
