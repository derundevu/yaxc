package io.github.derundevu.yaxc.activity

import android.content.ContentResolver
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.topjohnwu.superuser.Shell
import io.github.derundevu.yaxc.R
import io.github.derundevu.yaxc.Settings
import io.github.derundevu.yaxc.helper.AntifilterHelper
import io.github.derundevu.yaxc.helper.DownloadHelper
import io.github.derundevu.yaxc.helper.replaceFileAtomically
import io.github.derundevu.yaxc.presentation.assets.AssetCardState
import io.github.derundevu.yaxc.presentation.assets.AssetsScreen
import io.github.derundevu.yaxc.presentation.designsystem.YaxcAppTheme
import io.github.derundevu.yaxc.presentation.designsystem.components.FeedbackKind
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcFeedbackState
import io.github.derundevu.yaxc.service.TProxyService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.UUID

class AssetsActivity : AppCompatActivity() {

    private enum class AssetKind {
        GeoIp,
        GeoSite,
        Antifilter,
        XrayCore,
    }

    private val feedback = YaxcFeedbackState()
    private val settings by lazy { Settings(applicationContext) }
    private val assetJobs = mutableMapOf<AssetKind, Job>()

    private var geoIpState by mutableStateOf(AssetCardState(title = ""))
    private var geoSiteState by mutableStateOf(AssetCardState(title = ""))
    private var antifilterState by mutableStateOf(AssetCardState(title = ""))
    private var xrayCoreState by mutableStateOf(AssetCardState(title = ""))
    private var selectedGeoProvider by mutableStateOf(Settings.DEFAULT_GEO_PROVIDER)
    private var customGeoIpUrl by mutableStateOf("")
    private var customGeoSiteUrl by mutableStateOf("")

    private val geoIpLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) {
        writeToFile(it, geoIpFile(), AssetKind.GeoIp) {
            settings.installedGeoIpSourceLabel = getString(R.string.assetsSourceLocalFile)
            settings.installedGeoIpSourceUrl = LOCAL_ASSET_SOURCE_URL
            setAssetStatus()
        }
    }
    private val geoSiteLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) {
        writeToFile(it, geoSiteFile(), AssetKind.GeoSite) {
            settings.installedGeoSiteSourceLabel = getString(R.string.assetsSourceLocalFile)
            settings.installedGeoSiteSourceUrl = LOCAL_ASSET_SOURCE_URL
            setAssetStatus()
        }
    }
    private val antifilterLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) {
        installAntifilterFromUri(it)
    }
    private val xrayCoreLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) {
        val file = settings.xrayCoreFile()
        writeToFile(it, file, AssetKind.XrayCore) {
            makeExeFile(file)
            setAssetStatus()
        }
    }

    private fun geoIpFile(): File = File(applicationContext.filesDir, "geoip.dat")
    private fun geoSiteFile(): File = File(applicationContext.filesDir, "geosite.dat")
    private fun antifilterFile(): File = settings.antifilterFile()
    private fun newAntifilterTempFile(): File =
        File(cacheDir, "antifilter-${UUID.randomUUID()}.lst")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        selectedGeoProvider = settings.currentGeoResourcesProvider()
        customGeoIpUrl = settings.customGeoIpAddress
        customGeoSiteUrl = settings.customGeoSiteAddress

        setContent {
            YaxcAppTheme(feedback = feedback) {
                AssetsScreen(
                    geoIpState = geoIpState,
                    geoSiteState = geoSiteState,
                    antifilterState = antifilterState,
                    xrayCoreState = xrayCoreState,
                    selectedGeoProvider = selectedGeoProvider,
                    customGeoIpUrl = customGeoIpUrl,
                    customGeoSiteUrl = customGeoSiteUrl,
                    focusedAssetFilename = intent.getStringExtra(EXTRA_FOCUS_ASSET),
                    onBack = ::finish,
                    onGeoProviderSelected = { provider ->
                        selectedGeoProvider = provider
                        settings.setGeoResourcesProvider(provider)
                        customGeoIpUrl = settings.customGeoIpAddress
                        customGeoSiteUrl = settings.customGeoSiteAddress
                        setAssetStatus()
                    },
                    onCustomGeoIpUrlChange = { customGeoIpUrl = it },
                    onCustomGeoSiteUrlChange = { customGeoSiteUrl = it },
                    onApplyCustomGeoUrls = {
                        val geoIpUrl = customGeoIpUrl.trim()
                        val geoSiteUrl = customGeoSiteUrl.trim()
                        if (geoIpUrl.isBlank() || geoSiteUrl.isBlank()) {
                            feedback.show(getString(R.string.assetsUrlEmpty), FeedbackKind.Warning)
                        } else {
                            settings.updateCustomGeoResources(
                                geoIpAddress = geoIpUrl,
                                geoSiteAddress = geoSiteUrl,
                            )
                            selectedGeoProvider = settings.currentGeoResourcesProvider()
                            setAssetStatus()
                        }
                    },
                    onGeoIpDownload = {
                        download(
                            AssetKind.GeoIp,
                            settings.geoIpAddress.trim(),
                            geoIpFile(),
                        )
                    },
                    onGeoIpPick = { geoIpLauncher.launch(MIME_TYPE_OCTET_STREAM) },
                    onGeoIpDelete = {
                        settings.installedGeoIpSourceLabel = ""
                        settings.installedGeoIpSourceUrl = ""
                        delete(geoIpFile())
                    },
                    onGeoSiteDownload = {
                        download(
                            AssetKind.GeoSite,
                            settings.geoSiteAddress.trim(),
                            geoSiteFile(),
                        )
                    },
                    onGeoSitePick = { geoSiteLauncher.launch(MIME_TYPE_OCTET_STREAM) },
                    onGeoSiteDelete = {
                        settings.installedGeoSiteSourceLabel = ""
                        settings.installedGeoSiteSourceUrl = ""
                        delete(geoSiteFile())
                    },
                    onAntifilterDownload = {
                        download(
                            AssetKind.Antifilter,
                            settings.antifilterAddress.trim(),
                            newAntifilterTempFile(),
                        )
                    },
                    onAntifilterPick = { antifilterLauncher.launch(MIME_TYPE_TEXT) },
                    onAntifilterDelete = {
                        settings.installedAntifilterSourceLabel = ""
                        settings.installedAntifilterSourceUrl = ""
                        settings.installedAntifilterRouteCount = 0
                        delete(antifilterFile()) {
                            restartIfAntifilterEnabled()
                        }
                    },
                    onXrayCorePick = { runAsRoot { xrayCoreLauncher.launch(MIME_TYPE_OCTET_STREAM) } },
                    onXrayCoreDelete = { delete(settings.xrayCoreFile()) },
                )
            }
        }

        setAssetStatus()
    }

    private fun getFileDate(file: File): String {
        return if (file.exists()) {
            val date = Date(file.lastModified())
            @Suppress("SimpleDateFormat")
            SimpleDateFormat("yyyy/MM/dd HH:mm:ss").format(date)
        } else {
            getString(R.string.noValue)
        }
    }

    private fun getXrayCoreVersion(file: File): String {
        return getExeVersion(file, "${file.absolutePath} version")
    }

    private fun getExeVersion(file: File, cmd: String): String {
        val exists = file.exists()
        val invalid = {
            delete(file)
            getString(R.string.invalid)
        }
        return if (exists) {
            val result = Shell.cmd(cmd).exec()
            if (result.isSuccess) {
                val txt = result.out.first()
                val match = "Xray (.*?) ".toRegex().find(txt)
                match?.groups?.get(1)?.value ?: invalid()
            } else invalid()
        } else getString(R.string.noValue)
    }

    private fun setAssetStatus() {
        val geoIp = geoIpFile()
        val geoIpExists = geoIp.exists()
        val selectedGeoIpUrl = settings.geoIpAddress.trim()
        val installedGeoIpLabel = if (geoIpExists) {
            settings.installedGeoIpSourceLabel.ifBlank { getString(R.string.noValue) }
        } else {
            getString(R.string.noValue)
        }
        val geoIpNeedsUpdate = geoIpExists &&
            settings.installedGeoIpSourceUrl.isNotBlank() &&
            settings.installedGeoIpSourceUrl.trim() != selectedGeoIpUrl
        geoIpState = geoIpState.copy(
            title = getString(R.string.geoIp),
            value = getString(R.string.assetsUpdatedAt, getFileDate(geoIp)),
            details = getString(R.string.assetsInstalledSource, installedGeoIpLabel),
            note = if (geoIpNeedsUpdate) getString(R.string.assetsUpdateRequired) else null,
            isInstalled = geoIpExists,
            isLoading = geoIpState.isLoading,
            progress = if (geoIpState.isLoading) geoIpState.progress else 0,
        )

        val geoSite = geoSiteFile()
        val geoSiteExists = geoSite.exists()
        val selectedGeoSiteUrl = settings.geoSiteAddress.trim()
        val installedGeoSiteLabel = if (geoSiteExists) {
            settings.installedGeoSiteSourceLabel.ifBlank { getString(R.string.noValue) }
        } else {
            getString(R.string.noValue)
        }
        val geoSiteNeedsUpdate = geoSiteExists &&
            settings.installedGeoSiteSourceUrl.isNotBlank() &&
            settings.installedGeoSiteSourceUrl.trim() != selectedGeoSiteUrl
        geoSiteState = geoSiteState.copy(
            title = getString(R.string.geoSite),
            value = getString(R.string.assetsUpdatedAt, getFileDate(geoSite)),
            details = getString(R.string.assetsInstalledSource, installedGeoSiteLabel),
            note = if (geoSiteNeedsUpdate) getString(R.string.assetsUpdateRequired) else null,
            isInstalled = geoSiteExists,
            isLoading = geoSiteState.isLoading,
            progress = if (geoSiteState.isLoading) geoSiteState.progress else 0,
        )

        val antifilter = antifilterFile()
        val antifilterExists = antifilter.exists()
        val selectedAntifilterUrl = settings.antifilterAddress.trim()
        val installedAntifilterLabel = if (antifilterExists) {
            settings.installedAntifilterSourceLabel.ifBlank { getString(R.string.noValue) }
        } else {
            getString(R.string.noValue)
        }
        val installedAntifilterUrl = settings.installedAntifilterSourceUrl.trim()
        val antifilterNeedsUpdate = antifilterExists &&
            installedAntifilterUrl.isNotBlank() &&
            installedAntifilterUrl != LOCAL_ASSET_SOURCE_URL &&
            installedAntifilterUrl != selectedAntifilterUrl
        antifilterState = antifilterState.copy(
            title = getString(R.string.antifilter),
            value = getString(R.string.assetsUpdatedAt, getFileDate(antifilter)),
            details = getString(
                R.string.assetsInstalledSourceWithRules,
                installedAntifilterLabel,
                if (antifilterExists) settings.installedAntifilterRouteCount else 0,
            ),
            note = if (antifilterNeedsUpdate) getString(R.string.assetsUpdateRequired) else null,
            isInstalled = antifilterExists,
            isLoading = antifilterState.isLoading,
            progress = if (antifilterState.isLoading) antifilterState.progress else 0,
        )

        val xrayCore = settings.xrayCoreFile()
        val xrayCoreExists = xrayCore.exists()
        xrayCoreState = xrayCoreState.copy(
            title = getString(R.string.xrayLabel),
            value = getXrayCoreVersion(xrayCore),
            isInstalled = xrayCoreExists,
            isLoading = false,
            progress = 0,
        )
    }

    private fun download(kind: AssetKind, url: String, file: File) {
        if (isDownloadActive(kind)) {
            feedback.show(getString(R.string.anotherDownloadRunning), FeedbackKind.Info)
            return
        }

        if (url.isBlank()) {
            feedback.show(getString(R.string.assetsUrlEmpty), FeedbackKind.Warning)
            return
        }

        setDownloadState(kind, isLoading = true, progress = 0)

        val job = DownloadHelper(lifecycleScope, url, file, object : DownloadHelper.DownloadListener {
            override fun onProgress(progress: Int) {
                updateProgress(kind, progress)
            }

            override fun onError(exception: Exception) {
                setDownloadState(kind, isLoading = false, progress = 0)
                if (kind == AssetKind.Antifilter) {
                    file.delete()
                }
                feedback.show(
                    exception.message?.takeIf(String::isNotBlank) ?: getString(R.string.feedbackOperationFailed),
                    FeedbackKind.Error,
                )
                setAssetStatus()
            }

            override fun onComplete() {
                if (kind != AssetKind.Antifilter) {
                    setDownloadState(kind, isLoading = false, progress = 0)
                }
                when (kind) {
                    AssetKind.GeoIp -> {
                        settings.installedGeoIpSourceLabel = currentGeoProviderLabel()
                        settings.installedGeoIpSourceUrl = url.trim()
                    }

                    AssetKind.GeoSite -> {
                        settings.installedGeoSiteSourceLabel = currentGeoProviderLabel()
                        settings.installedGeoSiteSourceUrl = url.trim()
                    }

                    AssetKind.Antifilter -> {
                        installAntifilterTemp(
                            file = file,
                            sourceLabel = getString(R.string.assetsAntifilterSource),
                            sourceUrl = url.trim(),
                        )
                        return
                    }

                    AssetKind.XrayCore -> Unit
                }
                setAssetStatus()
            }
        }).start()
        assetJobs[kind] = job
        if (kind == AssetKind.Antifilter) {
            job.invokeOnCompletion { cause ->
                if (cause != null) file.delete()
            }
        }
    }

    private fun updateProgress(kind: AssetKind, progress: Int) {
        setDownloadState(kind, isLoading = true, progress = progress)
    }

    private fun setDownloadState(kind: AssetKind, isLoading: Boolean, progress: Int) {
        when (kind) {
            AssetKind.GeoIp -> geoIpState = geoIpState.copy(isLoading = isLoading, progress = progress)
            AssetKind.GeoSite -> geoSiteState = geoSiteState.copy(isLoading = isLoading, progress = progress)
            AssetKind.Antifilter -> antifilterState = antifilterState.copy(isLoading = isLoading, progress = progress)
            AssetKind.XrayCore -> xrayCoreState = xrayCoreState.copy(isLoading = isLoading, progress = progress)
        }
    }

    private fun isDownloadActive(kind: AssetKind): Boolean {
        return when (kind) {
            AssetKind.GeoIp -> geoIpState.isLoading
            AssetKind.GeoSite -> geoSiteState.isLoading
            AssetKind.Antifilter -> antifilterState.isLoading
            AssetKind.XrayCore -> xrayCoreState.isLoading
        }
    }

    private fun writeToFile(uri: Uri?, file: File, kind: AssetKind, cb: (() -> Unit)? = null) {
        if (uri == null || isDownloadActive(kind)) return
        setDownloadState(kind, isLoading = true, progress = 0)
        assetJobs[kind] = lifecycleScope.launch {
            try {
                importAssetFromUri(contentResolver, uri, file)
                cb?.invoke()
                setAssetStatus()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                feedback.show(
                    error.message?.takeIf(String::isNotBlank) ?: getString(R.string.feedbackOperationFailed),
                    FeedbackKind.Error,
                )
            } finally {
                if (assetJobs[kind] === currentCoroutineContext().job) {
                    setDownloadState(kind, isLoading = false, progress = 0)
                }
            }
        }
    }

    private fun installAntifilterFromUri(uri: Uri?) {
        if (uri == null) return
        if (isDownloadActive(AssetKind.Antifilter)) return
        setDownloadState(AssetKind.Antifilter, isLoading = true, progress = 0)
        val tempFile = newAntifilterTempFile()
        assetJobs[AssetKind.Antifilter] = lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    try {
                        importAssetFromUri(contentResolver, uri, tempFile)
                        val job = currentCoroutineContext().job
                        Result.success(AntifilterHelper.installValidated(tempFile, antifilterFile()) {
                            job.ensureActive()
                        })
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        Result.failure(error)
                    } finally {
                        tempFile.delete()
                    }
                }
                handleAntifilterInstallResult(
                    result = result,
                    sourceLabel = getString(R.string.assetsSourceLocalFile),
                    sourceUrl = LOCAL_ASSET_SOURCE_URL,
                )
            } finally {
                if (assetJobs[AssetKind.Antifilter] === currentCoroutineContext().job) {
                    setDownloadState(AssetKind.Antifilter, isLoading = false, progress = 0)
                }
            }
        }
    }

    private fun installAntifilterTemp(file: File, sourceLabel: String, sourceUrl: String) {
        assetJobs[AssetKind.Antifilter] = lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    try {
                        val job = currentCoroutineContext().job
                        Result.success(AntifilterHelper.installValidated(file, antifilterFile()) {
                            job.ensureActive()
                        })
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        Result.failure(error)
                    } finally {
                        file.delete()
                    }
                }
                handleAntifilterInstallResult(result, sourceLabel, sourceUrl)
            } finally {
                if (assetJobs[AssetKind.Antifilter] === currentCoroutineContext().job) {
                    setDownloadState(AssetKind.Antifilter, isLoading = false, progress = 0)
                }
            }
        }
    }

    private fun handleAntifilterInstallResult(
        result: Result<Int>,
        sourceLabel: String,
        sourceUrl: String,
    ) {
        result.onSuccess { count ->
            settings.installedAntifilterSourceLabel = sourceLabel
            settings.installedAntifilterSourceUrl = sourceUrl
            settings.installedAntifilterRouteCount = count
            restartIfAntifilterEnabled()
        }.onFailure { error ->
            feedback.show(
                error.message?.takeIf(String::isNotBlank) ?: getString(R.string.feedbackOperationFailed),
                FeedbackKind.Error,
            )
        }
        setAssetStatus()
    }

    private fun makeExeFile(file: File) {
        Shell.cmd("chown root:root ${file.absolutePath}").exec()
        Shell.cmd("chmod +x ${file.absolutePath}").exec()
    }

    private fun delete(file: File, cb: (() -> Unit)? = null) {
        val kind = when (file) {
            geoIpFile() -> AssetKind.GeoIp
            geoSiteFile() -> AssetKind.GeoSite
            antifilterFile() -> AssetKind.Antifilter
            else -> AssetKind.XrayCore
        }
        assetJobs.remove(kind)?.cancel()
        setDownloadState(kind, isLoading = false, progress = 0)
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { file.delete() }
            cb?.invoke()
            setAssetStatus()
        }
    }

    private fun restartIfAntifilterEnabled() {
        if (settings.antifilterEnabled && TProxyService.isActive()) {
            TProxyService.restart(this)
        }
    }

    private fun runAsRoot(cb: () -> Unit) {
        val result = Shell.cmd("whoami").exec()
        if (result.isSuccess && result.out.first() == "root") {
            cb()
            return
        }
        feedback.show(getString(R.string.rootRequired), FeedbackKind.Warning)
    }

    private fun currentGeoProviderLabel(): String {
        return when (settings.currentGeoResourcesProvider()) {
            Settings.GeoResourcesProvider.RunetFreedom -> getString(R.string.assetsGeoProviderRunetFreedom)
            Settings.GeoResourcesProvider.LoyalSoldier -> getString(R.string.assetsGeoProviderLoyalSoldier)
            Settings.GeoResourcesProvider.Custom -> getString(R.string.assetsGeoProviderCustom)
        }
    }

    companion object {
        const val EXTRA_FOCUS_ASSET = "io.github.derundevu.yaxc.extra.FOCUS_ASSET"
        private const val MIME_TYPE_OCTET_STREAM = "application/octet-stream"
        private const val MIME_TYPE_TEXT = "text/*"
        private const val LOCAL_ASSET_SOURCE_URL = "file://local"
    }
}

/** Shared by all local resource pickers; provider IO and commit never run on the main thread. */
internal suspend fun importAssetFromUri(resolver: ContentResolver, uri: Uri, target: File) {
    withContext(Dispatchers.IO) {
        replaceFileAtomically(target) { staged ->
            resolver.openInputStream(uri).use { input ->
                if (input == null) throw IOException("Could not open file")
                staged.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count == -1) break
                        output.write(buffer, 0, count)
                    }
                }
            }
            currentCoroutineContext().ensureActive()
        }
    }
}
