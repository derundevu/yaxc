package io.github.derundevu.yaxc.activity

import XrayCore.XrayCore
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.widget.Toast
import android.widget.EditText
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.derundevu.yaxc.BuildConfig
import io.github.derundevu.yaxc.R
import io.github.derundevu.yaxc.Settings
import io.github.derundevu.yaxc.Yaxc
import io.github.derundevu.yaxc.database.Link
import io.github.derundevu.yaxc.dto.ProfileList
import io.github.derundevu.yaxc.helper.HttpHelper
import io.github.derundevu.yaxc.helper.LinkHelper
import io.github.derundevu.yaxc.helper.TransparentProxyHelper
import io.github.derundevu.yaxc.helper.XrayBatchPingHelper
import io.github.derundevu.yaxc.presentation.main.MainAction
import io.github.derundevu.yaxc.presentation.main.MainEffect
import io.github.derundevu.yaxc.presentation.designsystem.YaxcTheme
import io.github.derundevu.yaxc.presentation.designsystem.YaxcThemeStyle
import io.github.derundevu.yaxc.presentation.main.MainScreen
import io.github.derundevu.yaxc.service.TProxyService
import io.github.derundevu.yaxc.viewmodel.MainViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.URI
import kotlin.coroutines.resume
import kotlin.reflect.cast

class MainActivity : AppCompatActivity() {

    private val clipboardManager by lazy { getSystemService(ClipboardManager::class.java) }
    private val settings by lazy { Settings(applicationContext) }
    private val transparentProxyHelper by lazy { TransparentProxyHelper(this, settings) }
    private val mainViewModel: MainViewModel by viewModels()
    private val configRepository by lazy { Yaxc::class.cast(application).configRepository }
    private val profileRepository by lazy { Yaxc::class.cast(application).profileRepository }
    private var batchPingJob: Job? = null

    private var cameraPermission = registerForActivityResult(RequestPermission()) {
        if (!it) return@registerForActivityResult
        scannerLauncher.launch(Intent(applicationContext, ScannerActivity::class.java))
    }
    private val notificationPermission = registerForActivityResult(RequestPermission()) {
        handleToggleVpnRequest()
    }
    private val linksManager = registerForActivityResult(StartActivityForResult()) {
        if (it.resultCode != RESULT_OK) return@registerForActivityResult
        refreshLinks()
    }
    private var scannerLauncher = registerForActivityResult(StartActivityForResult()) {
        val link = it.data?.getStringExtra("link")
        if (it.resultCode != RESULT_OK || link == null) return@registerForActivityResult
        processLink(link)
    }
    private val vpnLauncher = registerForActivityResult(StartActivityForResult()) {
        if (it.resultCode != RESULT_OK) return@registerForActivityResult
        toggleVpnService()
    }

    private val vpnServiceEventReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (context == null || intent == null) return
            when (intent.action) {
                TProxyService.START_VPN_SERVICE_ACTION_NAME -> {
                    mainViewModel.onAction(MainAction.UpdateVpnStatus(true))
                }
                TProxyService.STOP_VPN_SERVICE_ACTION_NAME -> {
                    mainViewModel.onAction(MainAction.UpdateVpnStatus(false))
                }
                TProxyService.STATUS_VPN_SERVICE_ACTION_NAME -> {
                    intent.getBooleanExtra("isRunning", false).let { running ->
                        mainViewModel.onAction(MainAction.UpdateVpnStatus(running))
                    }
                }
            }
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
            val uiState = mainViewModel.uiState.collectAsStateWithLifecycle().value
            YaxcTheme(style = YaxcThemeStyle.MidnightBlue) {
                MainScreen(
                    tabs = uiState.tabs,
                    selectedTabId = uiState.selectedTabId,
                    isRunning = uiState.isRunning,
                    selectedSourceName = uiState.selectedSourceName,
                    selectedProfileName = uiState.selectedProfileName,
                    selectedServerLabel = uiState.selectedServerLabel,
                    pingState = uiState.pingState,
                    profiles = uiState.filteredProfiles,
                    selectedProfileId = uiState.selectedProfileId,
                    profilesCount = uiState.profilesCount,
                    activeBatchPingSourceId = uiState.activeBatchPingSourceId,
                    appVersion = BuildConfig.VERSION_NAME,
                    xrayVersion = XrayCore.version(),
                    tun2socksVersion = getString(R.string.tun2socksVersion),
                    onAction = mainViewModel::onAction,
                )
            }
        }

        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                mainViewModel.effects.collectLatest(::handleEffect)
            }
        }

        intent?.data?.let { deepLink ->
            val pathSegments = deepLink.pathSegments
            if (pathSegments.isNotEmpty()) processLink(pathSegments[0])
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            if (settings.transparentProxy) transparentProxyHelper.install()
        }
    }

    override fun onStart() {
        super.onStart()
        IntentFilter().also {
            it.addAction(TProxyService.START_VPN_SERVICE_ACTION_NAME)
            it.addAction(TProxyService.STOP_VPN_SERVICE_ACTION_NAME)
            it.addAction(TProxyService.STATUS_VPN_SERVICE_ACTION_NAME)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(vpnServiceEventReceiver, it, RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(vpnServiceEventReceiver, it)
            }
        }
        Intent(this, TProxyService::class.java).also {
            it.action = TProxyService.STATUS_VPN_SERVICE_ACTION_NAME
            startService(it)
        }
        if (settings.refreshLinksOnOpen) {
            val interval = (settings.refreshLinksInterval * 60 * 1000).toLong()
            val diff = System.currentTimeMillis() - settings.lastRefreshLinks
            if (diff >= interval) refreshLinks()
        }
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(vpnServiceEventReceiver)
    }

    private fun handleToggleVpnRequest() {
        if (!settings.tun2socks || settings.transparentProxy) {
            toggleVpnService()
            return
        }

        if (!hasPostNotification()) return
        VpnService.prepare(this).also {
            if (it == null) {
                toggleVpnService()
                return
            }
            vpnLauncher.launch(it)
        }
    }

    private fun handleEffect(effect: MainEffect) {
        when (effect) {
            MainEffect.HandleToggleVpn -> handleToggleVpnRequest()
            MainEffect.RunPing -> runPingMeasurement()
            is MainEffect.RunBatchPing -> runBatchPing(effect)
            MainEffect.RefreshLinks -> refreshLinks()
            is MainEffect.RefreshSelectedSource -> refreshSource(effect.linkId)
            MainEffect.OpenNewProfile -> {
                startActivity(ProfileActivity.getIntent(applicationContext))
            }
            MainEffect.RequestQrCodeScanner -> {
                cameraPermission.launch(android.Manifest.permission.CAMERA)
            }
            MainEffect.ImportFromClipboard -> importFromClipboard()
            MainEffect.OpenAssets -> {
                startActivity(Intent(applicationContext, AssetsActivity::class.java))
            }
            MainEffect.OpenLinks -> {
                startActivity(Intent(applicationContext, LinksActivity::class.java))
            }
            MainEffect.OpenLogs -> {
                startActivity(Intent(applicationContext, LogsActivity::class.java))
            }
            MainEffect.OpenAppsRouting -> {
                startActivity(Intent(applicationContext, AppsRoutingActivity::class.java))
            }
            MainEffect.OpenCoreRouting -> {
                startActivity(Intent(applicationContext, CoreRoutingActivity::class.java))
            }
            MainEffect.OpenConfigs -> {
                startActivity(Intent(applicationContext, ConfigsActivity::class.java))
            }
            MainEffect.OpenSettings -> {
                startActivity(Intent(applicationContext, SettingsActivity::class.java))
            }
            MainEffect.ReloadCurrentConfig -> TProxyService.newConfig(applicationContext)
            is MainEffect.OpenProfileEditor -> {
                startActivity(ProfileActivity.getIntent(applicationContext, effect.profileId))
            }
            is MainEffect.ConfirmDeleteProfile -> showDeleteProfileDialog(effect.profile)
            is MainEffect.ShowRenameSourceDialog -> showRenameSourceDialog(effect.source)
            is MainEffect.ConfirmDeleteSourceDialog -> showDeleteSourceDialog(effect.source)
            is MainEffect.ShowToast -> {
                Toast.makeText(this, effect.message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun toggleVpnService() {
        if (mainViewModel.uiState.value.isRunning) {
            TProxyService.stop(applicationContext)
            return
        }
        TProxyService.start(applicationContext, false)
    }

    private fun showDeleteProfileDialog(profile: ProfileList) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.deleteProfileDialogTitle, profile.index + 1))
            .setMessage(getString(R.string.deleteProfileDialogMessage, profile.name))
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton(getString(R.string.deleteProfile)) { _, _ ->
                mainViewModel.onAction(MainAction.ConfirmDeleteProfile(profile))
            }.show()
    }

    private fun processLink(link: String) {
        val uri = runCatching { URI(link) }.getOrNull() ?: return
        if (uri.scheme == "http") {
            Toast.makeText(
                applicationContext,
                getString(R.string.forbiddenHttp),
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        if (uri.scheme == "https") {
            openLink(uri)
            return
        }
        val linkHelper = LinkHelper(settings, link)
        if (!linkHelper.isValid()) {
            Toast.makeText(
                applicationContext,
                getString(R.string.invalidLink),
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        val json = linkHelper.json()
        val name = linkHelper.remark()
        startActivity(ProfileActivity.getIntent(applicationContext, name = name, config = json))
    }

    private fun importFromClipboard() {
        runCatching {
            clipboardManager.primaryClip!!.getItemAt(0).text.toString().trim()
        }.getOrNull()?.let { processLink(it) }
    }

    private fun refreshLinks() {
        startActivity(LinksManagerActivity.refreshLinks(applicationContext))
    }

    private fun refreshSource(linkId: Long) {
        startActivity(LinksManagerActivity.refreshLink(applicationContext, linkId))
    }

    private fun showRenameSourceDialog(source: Link) {
        val input = EditText(this).apply {
            setText(source.name)
            setSelection(source.name.length)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.renameSource))
            .setView(input)
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton(getString(R.string.save)) { _, _ ->
                mainViewModel.onAction(
                    MainAction.ConfirmRenameSource(
                        sourceId = source.id,
                        name = input.text?.toString().orEmpty(),
                    )
                )
            }
            .show()
    }

    private fun showDeleteSourceDialog(source: Link) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.deleteSource))
            .setMessage(getString(R.string.deleteSourceDialogMessage, source.name))
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton(getString(R.string.deleteSource)) { _, _ ->
                mainViewModel.onAction(MainAction.ConfirmDeleteSource(source.id))
            }
            .show()
    }

    private fun openLink(uri: URI) {
        val link = Link()
        link.name = LinkHelper.remark(uri, getString(R.string.newLink))
        link.address = uri.toString()
        val intent = LinksManagerActivity.openLink(applicationContext, link)
        linksManager.launch(intent)
    }

    private fun runPingMeasurement() {
        val profileId = mainViewModel.uiState.value.selectedProfileId
        if (profileId == 0L) return

        if (XrayBatchPingHelper.supportsIsolatedPing()) {
            lifecycleScope.launch {
                val result = try {
                    val profile = profileRepository.find(profileId)
                    val globalConfig = configRepository.get()
                    XrayBatchPingHelper.measureProfileDelay(
                        context = applicationContext,
                        settings = settings,
                        globalConfig = globalConfig,
                        profile = profile,
                    )
                } catch (error: Exception) {
                    error.message ?: getString(R.string.pingFailedGeneric)
                }
                mainViewModel.onAction(MainAction.PingResultReceived(result))
            }
            return
        }

        HttpHelper(lifecycleScope, settings).measureDelay(!settings.transparentProxy) {
            mainViewModel.onAction(MainAction.PingResultReceived(it))
        }
    }

    private fun runBatchPing(effect: MainEffect.RunBatchPing) {
        batchPingJob?.cancel()
        batchPingJob = lifecycleScope.launch {
            val restoreProfileId = effect.restoreProfileId
            val supportsIsolatedPing = XrayBatchPingHelper.supportsIsolatedPing()
            try {
                if (supportsIsolatedPing) {
                    runIsolatedBatchPing(effect.profileIds)
                } else {
                    runLegacyBatchPing(effect.profileIds)
                }
            } finally {
                if (!supportsIsolatedPing && settings.selectedProfile != restoreProfileId) {
                    settings.selectedProfile = restoreProfileId
                    if (restoreProfileId != 0L && mainViewModel.uiState.value.isRunning) {
                        TProxyService.newConfig(applicationContext)
                    }
                }
                mainViewModel.onAction(MainAction.SetBatchPingSource(null))
            }
        }
    }

    private suspend fun runIsolatedBatchPing(profileIds: List<Long>) {
        val globalConfig = configRepository.get()
        val profiles = buildList {
            profileIds.forEach { profileId ->
                val profile = try {
                    profileRepository.find(profileId)
                } catch (_: Exception) {
                    null
                }
                if (profile != null) add(profile)
            }
        }
        if (profiles.isEmpty()) return

        val maxWorkers = minOf(20, profiles.size)
        val semaphore = Semaphore(maxWorkers)

        supervisorScope {
            profiles.map { profile ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        if (!isActive) return@withPermit
                        val result = try {
                            XrayBatchPingHelper.measureProfileDelay(
                                context = applicationContext,
                                settings = settings,
                                globalConfig = globalConfig,
                                profile = profile,
                            )
                        } catch (error: Exception) {
                            error.message ?: getString(R.string.pingFailedGeneric)
                        }
                        if (isActive) {
                            mainViewModel.onAction(MainAction.ProfilePingUpdated(profile.id, result))
                        }
                    }
                }
            }.awaitAll()
        }
    }

    private suspend fun runLegacyBatchPing(profileIds: List<Long>) {
        profileIds.forEach { profileId ->
            mainViewModel.onAction(
                MainAction.SetProfilePingState(
                    profileId,
                    io.github.derundevu.yaxc.presentation.main.MainPingState.Loading,
                )
            )
            if (settings.selectedProfile != profileId) {
                settings.selectedProfile = profileId
                TProxyService.newConfig(applicationContext)
                delay(650)
            }
            val result = measureDelaySuspend()
            mainViewModel.onAction(MainAction.ProfilePingUpdated(profileId, result))
        }
    }

    private suspend fun measureDelaySuspend(): String {
        return suspendCancellableCoroutine { continuation ->
            HttpHelper(lifecycleScope, settings).measureDelay(!settings.transparentProxy) { result ->
                if (continuation.isActive) {
                    continuation.resume(result)
                }
            }
        }
    }

    private fun hasPostNotification(): Boolean {
        val sharedPref = getSharedPreferences("app", MODE_PRIVATE)
        val key = "request_notification_permission"
        val askedBefore = sharedPref.getBoolean(key, false)
        if (askedBefore) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            sharedPref.edit().putBoolean(key, true).apply()
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            return false
        }
        return true
    }
}
