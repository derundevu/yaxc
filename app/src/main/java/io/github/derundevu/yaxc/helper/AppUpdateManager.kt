package io.github.derundevu.yaxc.helper

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.derundevu.yaxc.BuildConfig
import io.github.derundevu.yaxc.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class AppUpdateAsset(
    val name: String,
    val downloadUrl: String,
)

data class AppUpdateRelease(
    val versionName: String,
    val htmlUrl: String,
    val asset: AppUpdateAsset,
)

data class AppUpdateUiState(
    val isChecking: Boolean = true,
    val isUpToDate: Boolean = false,
    val availableRelease: AppUpdateRelease? = null,
    val pendingVersion: String? = null,
    val isDownloading: Boolean = false,
    val isReadyToInstall: Boolean = false,
    val errorMessage: String? = null,
)

class AppUpdateManager(
    private val context: Context,
    private val settings: Settings,
) {

    companion object {
        private const val RELEASES_LATEST_URL =
            "https://api.github.com/repos/derundevu/yaxc/releases/latest"
        private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    }

    private val downloadManager by lazy { context.getSystemService(DownloadManager::class.java) }

    var uiState by mutableStateOf(AppUpdateUiState())
        private set

    suspend fun refresh() {
        syncPendingDownloadState()
        uiState = uiState.copy(isChecking = true, errorMessage = null)

        val release = runCatching {
            fetchLatestRelease()
        }.getOrElse { error ->
            uiState = uiState.copy(
                isChecking = false,
                isUpToDate = false,
                availableRelease = null,
                errorMessage = error.message ?: "Update check failed",
            )
            return
        }

        syncPendingDownloadState()

        uiState = uiState.copy(
            isChecking = false,
            isUpToDate = release == null,
            availableRelease = release,
            errorMessage = if (release == null && uiState.errorMessage != null) null else uiState.errorMessage,
        )
    }

    fun startDownload(): Boolean {
        val release = uiState.availableRelease ?: return false
        val request = DownloadManager.Request(Uri.parse(release.asset.downloadUrl))
            .setTitle("yaxc ${release.versionName}")
            .setDescription(release.asset.name)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
            .setMimeType(APK_MIME_TYPE)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(
                context,
                Environment.DIRECTORY_DOWNLOADS,
                release.asset.name,
            )

        val downloadId = downloadManager.enqueue(request)
        settings.appUpdateDownloadId = downloadId
        settings.appUpdatePendingVersion = release.versionName
        uiState = uiState.copy(
            pendingVersion = release.versionName,
            isDownloading = true,
            isReadyToInstall = false,
            errorMessage = null,
        )
        return true
    }

    fun syncPendingDownloadState() {
        val pendingVersion = settings.appUpdatePendingVersion.takeIf { it.isNotBlank() }
        if (pendingVersion != null && !isVersionNewer(pendingVersion, BuildConfig.VERSION_NAME)) {
            clearPendingDownload()
        }

        val downloadId = settings.appUpdateDownloadId
        if (downloadId == 0L) {
            uiState = uiState.copy(
                pendingVersion = null,
                isDownloading = false,
                isReadyToInstall = false,
            )
            return
        }

        when (queryDownloadState(downloadId)) {
            DownloadState.Running -> {
                uiState = uiState.copy(
                    pendingVersion = settings.appUpdatePendingVersion.takeIf { it.isNotBlank() },
                    isDownloading = true,
                    isReadyToInstall = false,
                )
            }

            DownloadState.Successful -> {
                uiState = uiState.copy(
                    pendingVersion = settings.appUpdatePendingVersion.takeIf { it.isNotBlank() },
                    isDownloading = false,
                    isReadyToInstall = true,
                )
            }

            DownloadState.Missing,
            DownloadState.Failed,
            -> {
                clearPendingDownload()
                uiState = uiState.copy(
                    pendingVersion = null,
                    isDownloading = false,
                    isReadyToInstall = false,
                )
            }
        }
    }

    fun handleDownloadComplete(downloadId: Long): Intent? {
        if (downloadId == 0L || downloadId != settings.appUpdateDownloadId) return null
        syncPendingDownloadState()
        return installDownloadedUpdate()
    }

    fun installDownloadedUpdate(): Intent? {
        val downloadId = settings.appUpdateDownloadId
        if (downloadId == 0L) return null
        val uri = downloadManager.getUriForDownloadedFile(downloadId) ?: return null
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME_TYPE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private suspend fun fetchLatestRelease(): AppUpdateRelease? = withContext(Dispatchers.IO) {
        val connection = URL(RELEASES_LATEST_URL).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "yaxc/${BuildConfig.VERSION_NAME}")
        connection.setRequestProperty("Connection", "close")

        try {
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                throw IllegalStateException("Update check failed: HTTP $code")
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val payload = JSONObject(body)
            val versionName = payload.optString("tag_name")
                .ifBlank { payload.optString("name") }
                .trim()
                .removePrefix("v")

            if (versionName.isBlank() || !isVersionNewer(versionName, BuildConfig.VERSION_NAME)) {
                return@withContext null
            }

            val asset = selectBestAsset(payload.optJSONArray("assets"))
                ?: throw IllegalStateException("No compatible APK found in the latest release")

            AppUpdateRelease(
                versionName = versionName,
                htmlUrl = payload.optString("html_url"),
                asset = asset,
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun selectBestAsset(assets: JSONArray?): AppUpdateAsset? {
        if (assets == null) return null
        val assetList = buildList {
            for (index in 0 until assets.length()) {
                val asset = assets.optJSONObject(index) ?: continue
                val name = asset.optString("name").trim()
                val url = asset.optString("browser_download_url").trim()
                if (name.endsWith(".apk", ignoreCase = true) && url.isNotBlank()) {
                    add(AppUpdateAsset(name = name, downloadUrl = url))
                }
            }
        }
        if (assetList.isEmpty()) return null

        val preferredAbis = Build.SUPPORTED_ABIS.mapNotNull(::normalizeAbi)
        preferredAbis.forEach { abi ->
            assetList.firstOrNull { it.name.contains(abi, ignoreCase = true) }?.let { return it }
        }
        return null
    }

    private fun queryDownloadState(downloadId: Long): DownloadState {
        val cursor = downloadManager.query(
            DownloadManager.Query().setFilterById(downloadId)
        ) ?: return DownloadState.Missing

        cursor.use {
            if (!it.moveToFirst()) return DownloadState.Missing
            return when (it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                DownloadManager.STATUS_PENDING,
                DownloadManager.STATUS_RUNNING,
                DownloadManager.STATUS_PAUSED,
                -> DownloadState.Running

                DownloadManager.STATUS_SUCCESSFUL -> DownloadState.Successful
                DownloadManager.STATUS_FAILED -> DownloadState.Failed
                else -> DownloadState.Missing
            }
        }
    }

    private fun clearPendingDownload() {
        settings.appUpdateDownloadId = 0L
        settings.appUpdatePendingVersion = ""
    }

    private fun normalizeAbi(abi: String): String? {
        return when {
            abi.contains("arm64", ignoreCase = true) -> "arm64-v8a"
            abi.contains("armeabi", ignoreCase = true) -> "armeabi-v7a"
            abi.contains("x86_64", ignoreCase = true) -> "x86_64"
            abi.equals("x86", ignoreCase = true) -> "x86"
            else -> null
        }
    }

    private enum class DownloadState {
        Running,
        Successful,
        Failed,
        Missing,
    }
}

internal fun isVersionNewer(candidate: String, current: String): Boolean {
    val left = parseVersionParts(candidate)
    val right = parseVersionParts(current)
    val maxSize = maxOf(left.size, right.size)
    for (index in 0 until maxSize) {
        val leftPart = left.getOrElse(index) { 0 }
        val rightPart = right.getOrElse(index) { 0 }
        if (leftPart != rightPart) return leftPart > rightPart
    }
    return false
}

private fun parseVersionParts(version: String): List<Int> {
    return version
        .trim()
        .removePrefix("v")
        .split('.')
        .mapNotNull { part ->
            part.takeWhile(Char::isDigit).takeIf(String::isNotBlank)?.toIntOrNull()
        }
}
