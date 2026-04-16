package io.github.derundevu.yaxc.activity

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
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
import io.github.derundevu.yaxc.helper.DownloadHelper
import io.github.derundevu.yaxc.presentation.assets.AssetCardState
import io.github.derundevu.yaxc.presentation.assets.AssetsScreen
import io.github.derundevu.yaxc.presentation.designsystem.YaxcTheme
import io.github.derundevu.yaxc.presentation.designsystem.YaxcThemeStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.text.toRegex

class AssetsActivity : AppCompatActivity() {

    private enum class AssetKind {
        GeoIp,
        GeoSite,
        XrayCore,
    }

    private var downloading: Boolean = false
    private var activeDownload: AssetKind? = null

    private val settings by lazy { Settings(applicationContext) }

    private var geoIpState by mutableStateOf(AssetCardState(title = ""))
    private var geoSiteState by mutableStateOf(AssetCardState(title = ""))
    private var xrayCoreState by mutableStateOf(AssetCardState(title = ""))

    private val geoIpLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) {
        writeToFile(it, geoIpFile()) { setAssetStatus() }
    }
    private val geoSiteLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) {
        writeToFile(it, geoSiteFile()) { setAssetStatus() }
    }
    private val xrayCoreLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) {
        val file = settings.xrayCoreFile()
        writeToFile(it, file) {
            makeExeFile(file)
            setAssetStatus()
        }
    }

    private fun geoIpFile(): File = File(applicationContext.filesDir, "geoip.dat")
    private fun geoSiteFile(): File = File(applicationContext.filesDir, "geosite.dat")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            YaxcTheme(style = YaxcThemeStyle.MidnightBlue) {
                AssetsScreen(
                    geoIpState = geoIpState,
                    geoSiteState = geoSiteState,
                    xrayCoreState = xrayCoreState,
                    onBack = ::finish,
                    onGeoIpDownload = {
                        download(
                            AssetKind.GeoIp,
                            settings.geoIpAddress,
                            geoIpFile(),
                        )
                    },
                    onGeoIpPick = { geoIpLauncher.launch(MIME_TYPE_OCTET_STREAM) },
                    onGeoIpDelete = { delete(geoIpFile()) },
                    onGeoSiteDownload = {
                        download(
                            AssetKind.GeoSite,
                            settings.geoSiteAddress,
                            geoSiteFile(),
                        )
                    },
                    onGeoSitePick = { geoSiteLauncher.launch(MIME_TYPE_OCTET_STREAM) },
                    onGeoSiteDelete = { delete(geoSiteFile()) },
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
        geoIpState = geoIpState.copy(
            title = getString(R.string.geoIp),
            value = getFileDate(geoIp),
            isInstalled = geoIpExists,
            isLoading = activeDownload == AssetKind.GeoIp,
            progress = if (activeDownload == AssetKind.GeoIp) geoIpState.progress else 0,
        )

        val geoSite = geoSiteFile()
        val geoSiteExists = geoSite.exists()
        geoSiteState = geoSiteState.copy(
            title = getString(R.string.geoSite),
            value = getFileDate(geoSite),
            isInstalled = geoSiteExists,
            isLoading = activeDownload == AssetKind.GeoSite,
            progress = if (activeDownload == AssetKind.GeoSite) geoSiteState.progress else 0,
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
        if (downloading) {
            Toast.makeText(
                applicationContext,
                getString(R.string.anotherDownloadRunning),
                Toast.LENGTH_SHORT,
            ).show()
            return
        }

        downloading = true
        activeDownload = kind
        updateProgress(kind, 0)

        DownloadHelper(lifecycleScope, url, file, object : DownloadHelper.DownloadListener {
            override fun onProgress(progress: Int) {
                updateProgress(kind, progress)
            }

            override fun onError(exception: Exception) {
                downloading = false
                activeDownload = null
                Toast.makeText(applicationContext, exception.message, Toast.LENGTH_SHORT).show()
                setAssetStatus()
            }

            override fun onComplete() {
                downloading = false
                activeDownload = null
                setAssetStatus()
            }
        }).start()
    }

    private fun updateProgress(kind: AssetKind, progress: Int) {
        when (kind) {
            AssetKind.GeoIp -> geoIpState = geoIpState.copy(isLoading = true, progress = progress)
            AssetKind.GeoSite -> geoSiteState = geoSiteState.copy(isLoading = true, progress = progress)
            AssetKind.XrayCore -> Unit
        }
    }

    private fun writeToFile(uri: Uri?, file: File, cb: (() -> Unit)? = null) {
        if (uri == null) return
        lifecycleScope.launch {
            contentResolver.openInputStream(uri).use { input ->
                FileOutputStream(file).use { output ->
                    input?.copyTo(output)
                }
            }
            cb?.invoke()
            withContext(Dispatchers.Main) {
                setAssetStatus()
            }
        }
    }

    private fun makeExeFile(file: File) {
        Shell.cmd("chown root:root ${file.absolutePath}").exec()
        Shell.cmd("chmod +x ${file.absolutePath}").exec()
    }

    private fun delete(file: File) {
        lifecycleScope.launch {
            file.delete()
            withContext(Dispatchers.Main) {
                setAssetStatus()
            }
        }
    }

    private fun runAsRoot(cb: () -> Unit) {
        val result = Shell.cmd("whoami").exec()
        if (result.isSuccess && result.out.first() == "root") {
            cb()
            return
        }
        Toast.makeText(this, getString(R.string.rootRequired), Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val MIME_TYPE_OCTET_STREAM = "application/octet-stream"
    }
}
