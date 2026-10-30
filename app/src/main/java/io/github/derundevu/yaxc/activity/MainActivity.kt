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
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.derundevu.yaxc.BuildConfig
import io.github.derundevu.yaxc.R
import io.github.derundevu.yaxc.Settings
import io.github.derundevu.yaxc.Yaxc
import io.github.derundevu.yaxc.database.Link
import io.github.derundevu.yaxc.dto.ProfileList
import io.github.derundevu.yaxc.helper.AppUpdateManager
import io.github.derundevu.yaxc.helper.LinkHelper
import io.github.derundevu.yaxc.helper.SubscriptionRefreshHelper
import io.github.derundevu.yaxc.helper.TransparentProxyHelper
import io.github.derundevu.yaxc.helper.XrayBatchPingHelper
import io.github.derundevu.yaxc.presentation.main.MainAction
import io.github.derundevu.yaxc.presentation.main.MainEffect
import io.github.derundevu.yaxc.presentation.designsystem.YaxcAppTheme
import io.github.derundevu.yaxc.presentation.designsystem.YaxcTheme
import io.github.derundevu.yaxc.presentation.designsystem.components.FeedbackKind
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcFeedbackState
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcMessageDialog
import io.github.derundevu.yaxc.presentation.main.MainScreen
import io.github.derundevu.yaxc.service.TProxyService
import io.github.derundevu.yaxc.viewmodel.MainViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI
import kotlin.reflect.cast

class MainActivity : AppCompatActivity() {

    private val clipboardManager by lazy { getSystemService(ClipboardManager::class.java) }
    private val settings by lazy { Settings(applicationContext) }
    private val appUpdateManager by lazy { AppUpdateManager(applicationContext, settings) }
    private val transparentProxyHelper by lazy { TransparentProxyHelper(this, settings) }
    private val mainViewModel: MainViewModel by viewModels()
    private val configRepository by lazy { Yaxc::class.cast(application).configRepository }
    private val profileRepository by lazy { Yaxc::class.cast(application).profileRepository }
    private var pingJob: Job? = null
    private var subscriptionRefreshJob: Job? = null
    private val feedback = YaxcFeedbackState()
    private var linksOperationActive = false
    private var pendingDialog by mutableStateOf<MainDialog?>(null)

    private sealed interface MainDialog {
        data class DeleteProfile(val profile: ProfileList) : MainDialog
        data class RenameSource(val source: Link) : MainDialog
        data class DeleteSource(val source: Link) : MainDialog
    }

    private var cameraPermission = registerForActivityResult(RequestPermission()) {
        if (!it) {
            feedback.show(getString(R.string.scannerCameraPermissionDenied), FeedbackKind.Error)
            return@registerForActivityResult
        }
        scannerLauncher.launch(Intent(applicationContext, ScannerActivity::class.java))
    }
    private val notificationPermission = registerForActivityResult(RequestPermission()) {
        requestVpnStart()
    }
    private val linksManager = registerForActivityResult(StartActivityForResult()) {
        linksOperationActive = false
        LinksManagerActivity.readFeedback(it.data)?.let { message ->
            feedback.show(message.message, message.kind)
        }
    }
    private var scannerLauncher = registerForActivityResult(StartActivityForResult()) {
        if (it.resultCode != RESULT_OK) return@registerForActivityResult
        processLink(it.data?.getStringExtra("link").orEmpty())
    }
    private val vpnLauncher = registerForActivityResult(StartActivityForResult()) {
        if (it.resultCode != RESULT_OK) {
            feedback.show(getString(R.string.connectionPermissionRequired), FeedbackKind.Error)
            return@registerForActivityResult
        }
        startVpnService()
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

    private val appUpdateDownloadReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val downloadId = intent?.getLongExtra(android.app.DownloadManager.EXTRA_DOWNLOAD_ID, 0L) ?: 0L
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
            val uiState = mainViewModel.uiState.collectAsStateWithLifecycle().value
            YaxcAppTheme(feedback = feedback) {
                MainScreen(
                    tabs = uiState.tabs,
                    selectedTabId = uiState.selectedTabId,
                    selectedSourceId = uiState.selectedSourceId,
                    isRunning = uiState.isRunning,
                    connectionFailure = uiState.connectionFailure,
                    selectedSourceName = uiState.selectedSourceName,
                    selectedSourceMetadata = uiState.selectedSourceMetadata,
                    selectedSourceLastRefreshedAt = uiState.selectedSourceLastRefreshedAt,
                    selectedProfileName = uiState.selectedProfileName,
                    selectedServerLabel = uiState.selectedServerLabel,
                    socksAddress = uiState.socksAddress,
                    socksPort = uiState.socksPort,
                    socksUsername = uiState.socksUsername,
                    socksPassword = uiState.socksPassword,
                    pingAddress = uiState.pingAddress,
                    pingState = uiState.pingState,
                    profiles = uiState.filteredProfiles,
                    selectedProfileId = uiState.selectedProfileId,
                    profilesCount = uiState.profilesCount,
                    activeBatchPingSourceId = uiState.activeBatchPingSourceId,
                    batchPingCompleted = uiState.batchPingCompleted,
                    batchPingTotal = uiState.batchPingTotal,
                    appVersion = BuildConfig.VERSION_NAME,
                    xrayVersion = XrayCore.version(),
                    tun2socksVersion = getString(R.string.tun2socksVersion),
                    appUpdateState = appUpdateManager.uiState,
                    onCheckAppUpdate = ::checkAppUpdate,
                    onDownloadAppUpdate = ::downloadAppUpdate,
                    onInstallAppUpdate = ::installAppUpdate,
                    onAction = mainViewModel::onAction,
                )
                MainDialogs()
            }
        }

        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                mainViewModel.effects.collectLatest(::handleEffect)
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

        intent?.data?.let { deepLink ->
            val pathSegments = deepLink.pathSegments
            if (pathSegments.isNotEmpty()) processLink(pathSegments[0])
        }
    }

    override fun onResume() {
        super.onResume()
        appUpdateManager.syncPendingDownloadState()
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
        IntentFilter(android.app.DownloadManager.ACTION_DOWNLOAD_COMPLETE).also {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(appUpdateDownloadReceiver, it, RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(appUpdateDownloadReceiver, it)
            }
        }
        if (settings.refreshLinksOnOpen) refreshDueSubscriptions()
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(vpnServiceEventReceiver)
        unregisterReceiver(appUpdateDownloadReceiver)
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
                feedback.show(getString(R.string.appUpdateInstallUnavailable), FeedbackKind.Error)
            }
    }

    private fun handleToggleVpnRequest() {
        if (mainViewModel.uiState.value.isRunning) {
            TProxyService.stop(applicationContext)
        } else {
            requestVpnStart()
        }
    }

    private fun requestVpnStart() {
        if (!settings.tun2socks || settings.transparentProxy) {
            startVpnService()
            return
        }

        if (!hasPostNotification()) return
        val permission = VpnService.prepare(this)
        if (permission == null) {
            startVpnService()
        } else {
            vpnLauncher.launch(permission)
        }
    }

    private fun handleEffect(effect: MainEffect) {
        when (effect) {
            MainEffect.HandleToggleVpn -> handleToggleVpnRequest()
            MainEffect.RetryConnection -> {
                if (TProxyService.isActive()) {
                    TProxyService.newConfig(applicationContext)
                } else {
                    requestVpnStart()
                }
            }
            is MainEffect.OpenConnectionResources -> {
                startActivity(
                    Intent(applicationContext, AssetsActivity::class.java)
                        .putExtra(AssetsActivity.EXTRA_FOCUS_ASSET, effect.filename)
                )
            }
            is MainEffect.RunPing -> runPing(effect.operationId, listOf(effect.profileId))
            is MainEffect.RunBatchPing -> runPing(effect.operationId, effect.profileIds)
            MainEffect.RefreshLinks -> refreshLinks()
            is MainEffect.RefreshSelectedSource -> refreshSource(effect.linkId)
            MainEffect.OpenNewProfile -> {
                startActivity(
                    ProfileActivity.getIntent(
                        context = applicationContext,
                        linkId = mainViewModel.uiState.value.selectedTabId.takeIf { it != 0L },
                    )
                )
            }
            MainEffect.OpenNewSource -> {
                openLinksManager(LinksManagerActivity.openLink(applicationContext))
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
            MainEffect.OpenConnectionInfo -> {
                openConnectionInfo()
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
            is MainEffect.ConfirmDeleteProfile -> pendingDialog = MainDialog.DeleteProfile(effect.profile)
            is MainEffect.ShowRenameSourceDialog -> pendingDialog = MainDialog.RenameSource(effect.source)
            is MainEffect.ConfirmDeleteSourceDialog -> pendingDialog = MainDialog.DeleteSource(effect.source)
            is MainEffect.ShowMessage -> feedback.show(effect.message)
        }
    }

    private fun openConnectionInfo() {
        val uiState = mainViewModel.uiState.value
        startActivity(
            ConnectionInfoActivity.getIntent(
                context = applicationContext,
                selectedSourceName = uiState.selectedSourceName,
                selectedProfileName = uiState.selectedProfileName,
                selectedSourceMetadataJson = uiState.selectedSourceMetadata?.toJsonString(),
                selectedServerLabel = uiState.selectedServerLabel,
                socksAddress = uiState.socksAddress,
                socksPort = uiState.socksPort,
                socksUsername = uiState.socksUsername,
                socksPassword = uiState.socksPassword,
                pingAddress = uiState.pingAddress,
            )
        )
    }

    private fun startVpnService() {
        TProxyService.start(applicationContext, false)
    }


    private fun processLink(link: String) {
        val normalizedLink = link.trim()
        // Profile imports also accept JSON and base64, so a URI is not required.
        val uri = runCatching { URI(normalizedLink) }.getOrNull()
        if (uri?.scheme == "http") {
            feedback.show(getString(R.string.forbiddenHttp), FeedbackKind.Error)
            return
        }
        if (uri?.scheme == "https") {
            openLink(uri)
            return
        }
        val linkId = mainViewModel.uiState.value.selectedTabId.takeIf { it != 0L }
        lifecycleScope.launch {
            val parsedProfile = withContext(Dispatchers.Default) {
                try {
                    val linkHelper = LinkHelper(settings, normalizedLink)
                    if (linkHelper.isValid()) {
                        linkHelper.json() to linkHelper.remark()
                    } else {
                        null
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    null
                }
            }
            if (parsedProfile == null) {
                feedback.show(getString(R.string.invalidLink), FeedbackKind.Error)
                return@launch
            }
            val (json, name) = parsedProfile
            startActivity(
                ProfileActivity.getIntent(
                    context = applicationContext,
                    name = name,
                    config = json,
                    linkId = linkId,
                )
            )
        }
    }

    private fun importFromClipboard() {
        runCatching {
            clipboardManager.primaryClip!!.getItemAt(0).text.toString()
        }.getOrNull()?.let { processLink(it) }
    }

    private fun refreshLinks() {
        openLinksManager(LinksManagerActivity.refreshLinks(applicationContext))
    }

    private fun openLinksManager(intent: Intent) {
        if (linksOperationActive) return
        linksOperationActive = true
        try {
            linksManager.launch(intent)
        } catch (error: Exception) {
            linksOperationActive = false
            throw error
        }
    }

    private fun refreshDueSubscriptions() {
        if (subscriptionRefreshJob?.isActive == true) return
        subscriptionRefreshJob = lifecycleScope.launch {
            runCatching {
                SubscriptionRefreshHelper(applicationContext, settings).refreshDueLinks()
            }
        }
    }

    private fun refreshSource(linkId: Long) {
        openLinksManager(LinksManagerActivity.refreshLink(applicationContext, linkId))
    }

    @Composable
    private fun MainDialogs() {
        when (val dialog = pendingDialog) {
            is MainDialog.DeleteProfile -> YaxcMessageDialog(
                title = getString(R.string.deleteProfileDialogTitle, dialog.profile.index + 1),
                message = getString(R.string.deleteProfileDialogMessage, dialog.profile.name),
                confirmLabel = getString(R.string.deleteProfile),
                dismissLabel = getString(R.string.cancel),
                kind = FeedbackKind.Warning,
                onDismiss = { pendingDialog = null },
                onConfirm = {
                    pendingDialog = null
                    mainViewModel.onAction(MainAction.ConfirmDeleteProfile(dialog.profile))
                },
            )
            is MainDialog.DeleteSource -> YaxcMessageDialog(
                title = getString(R.string.deleteSource),
                message = getString(R.string.deleteSourceDialogMessage, dialog.source.name),
                confirmLabel = getString(R.string.deleteSource),
                dismissLabel = getString(R.string.cancel),
                kind = FeedbackKind.Warning,
                onDismiss = { pendingDialog = null },
                onConfirm = {
                    pendingDialog = null
                    mainViewModel.onAction(MainAction.ConfirmDeleteSource(dialog.source.id))
                },
            )
            is MainDialog.RenameSource -> {
                var name by rememberSaveable(dialog.source.id) { mutableStateOf(dialog.source.name) }
                AlertDialog(
                    onDismissRequest = { pendingDialog = null },
                    shape = YaxcTheme.shapes.extraLarge,
                    containerColor = MaterialTheme.colorScheme.surface,
                    title = { Text(getString(R.string.renameSource)) },
                    text = {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text(getString(R.string.profileName)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    },
                    confirmButton = {
                        TextButton(
                            enabled = name.isNotBlank(),
                            onClick = {
                                pendingDialog = null
                                mainViewModel.onAction(MainAction.ConfirmRenameSource(dialog.source.id, name))
                            },
                        ) { Text(getString(R.string.save)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { pendingDialog = null }) { Text(getString(R.string.cancel)) }
                    },
                )
            }
            null -> Unit
        }
    }

    private fun openLink(uri: URI) {
        val link = Link()
        link.name = LinkHelper.remark(uri, getString(R.string.newLink))
        link.address = uri.toString()
        val intent = LinksManagerActivity.openLink(applicationContext, link)
        openLinksManager(intent)
    }

    private fun runPing(operationId: Long, profileIds: List<Long>) {
        if (!mainViewModel.isCurrentPingOperation(operationId)) return
        val previous = pingJob
        previous?.cancel()
        pingJob = lifecycleScope.launch {
            try {
                // Cancellation keeps the worker bound until its native core has actually closed.
                previous?.join()
                val options = XrayBatchPingHelper.Options.from(settings)
                val globalConfig = withContext(Dispatchers.IO) { configRepository.get() }
                coroutineScope {
                    // Only five long-lived loops, not one coroutine per profile. The iterator and
                    // ViewModel updates stay on Main; lookup, config and native work run on IO.
                    val remaining = profileIds.iterator()
                    repeat(minOf(XrayBatchPingHelper.workerCount, profileIds.size)) {
                        launch {
                            while (remaining.hasNext()) {
                                ensureActive()
                                val profileId = remaining.next()
                                val result = try {
                                    withContext(Dispatchers.IO) {
                                        XrayBatchPingHelper.measureProfileDelay(
                                            context = applicationContext,
                                            settings = settings,
                                            globalConfig = globalConfig,
                                            profile = profileRepository.find(profileId),
                                            options = options,
                                        )
                                    }
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (error: Exception) {
                                    error.message ?: getString(R.string.pingFailedGeneric)
                                }
                                ensureActive()
                                mainViewModel.onAction(MainAction.PingResultReceived(operationId, profileId, result))
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val result = error.message ?: getString(R.string.pingFailedGeneric)
                profileIds.forEach { profileId ->
                    mainViewModel.onAction(MainAction.PingResultReceived(operationId, profileId, result))
                }
            } finally {
                mainViewModel.onAction(MainAction.PingFinished(operationId))
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

    private companion object {
        const val APP_UPDATE_POLL_INTERVAL_MS = 1_500L
    }
}
