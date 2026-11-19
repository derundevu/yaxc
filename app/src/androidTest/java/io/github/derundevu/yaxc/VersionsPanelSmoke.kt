package io.github.derundevu.yaxc

import android.app.DownloadManager
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.derundevu.yaxc.activity.MainActivity
import io.github.derundevu.yaxc.helper.AppUpdateManager
import io.github.derundevu.yaxc.service.TProxyService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class VersionsPanelSmoke {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun actualSettingsShowOfflineLocalizedNotesAndRealDownloadManagerProgress() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName == "io.github.derundevu.yaxc.regression")
        check(!TProxyService.isActive())
        val settings = Settings(context)
        val version = BuildConfig.VERSION_NAME
        val oldTheme = settings.themeStyle
        val oldLanguage = settings.languageTag
        val oldRefresh = settings.refreshLinksOnOpen
        val oldLocales = AppCompatDelegate.getApplicationLocales()
        check(settings.appUpdateDownloadId == 0L) { "Refusing to replace a pre-existing regression download" }
        val downloads = context.getSystemService(DownloadManager::class.java)
        val ids = mutableListOf<Long>()
        val apk = File(context.applicationInfo.sourceDir)
        try {
            settings.refreshLinksOnOpen = false
            settings.languageTag = "ru"
            settings.themeStyle = io.github.derundevu.yaxc.presentation.designsystem.YaxcThemeStyle.Mint
            instrumentation.runOnMainSync {
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("ru"))
            }
            ReleaseServer(apk).use { server ->
                ActivityScenario.launch(MainActivity::class.java).use { activity ->
                    var manager = updater(activity)
                    compose.waitUntil(15_000) { !manager.uiState.isChecking }
                    endpoint(manager, server.url)
                    withContext(Dispatchers.Main) { manager.refresh() }
                    compose.waitUntil(15_000) {
                        compose.onAllNodes(androidx.compose.ui.test.hasText("Настройки")).fetchSemanticsNodes().isNotEmpty()
                    }
                    compose.onNodeWithText("Настройки").performClick()
                    compose.onNodeWithTag("settings:versions").performScrollTo().assertIsDisplayed()
                    capture(activity, "versions-panel-ru")
                    compose.onNodeWithTag("settings:whats-new").performClick()
                    compose.waitUntil(10_000) {
                        compose.onAllNodes(androidx.compose.ui.test.hasText("Соединения и история")).fetchSemanticsNodes().isNotEmpty()
                    }
                    capture(activity, "versions-changelog-ru")
                    compose.onNodeWithTag("changelog:close").performClick()
                    instrumentation.runOnMainSync {
                        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en"))
                    }
                    compose.waitUntil(15_000) {
                        compose.onAllNodes(androidx.compose.ui.test.hasText("What's new in $version?")).fetchSemanticsNodes().isNotEmpty()
                    }
                    compose.onNodeWithTag("settings:whats-new").performScrollTo().performClick()
                    compose.waitUntil(10_000) {
                        compose.onAllNodes(androidx.compose.ui.test.hasText("Connections and history")).fetchSemanticsNodes().isNotEmpty()
                    }
                    capture(activity, "versions-changelog-en")
                    compose.onNodeWithTag("changelog:close").performClick()
                    instrumentation.runOnMainSync {
                        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("ru"))
                    }
                    compose.waitUntil(15_000) {
                        compose.onAllNodes(androidx.compose.ui.test.hasText("Что нового в $version?")).fetchSemanticsNodes().isNotEmpty()
                    }
                    manager = updater(activity)
                    compose.waitUntil(15_000) { !manager.uiState.isChecking }
                    endpoint(manager, server.url)
                    server.prepare("1.6.1", chunked = false)
                    withContext(Dispatchers.Main) { manager.refresh() }
                    compose.onNodeWithTag("updates:download").performScrollTo().performClick()
                    compose.waitUntil(25_000) { manager.uiState.downloadedBytes > 0L && manager.uiState.totalBytes == apk.length() }
                    ids += settings.appUpdateDownloadId
                    assertTrue(manager.uiState.isDownloading)
                    val progress = compose.onNodeWithTag("updates:progress").fetchSemanticsNode()
                        .config[SemanticsProperties.ProgressBarRangeInfo].current
                    assertTrue(progress > 0f && progress < 1f)
                    capture(activity, "versions-download-progress")
                    val admittedBytes = manager.uiState.downloadedBytes
                    activity.recreate()
                    manager = updater(activity)
                    compose.waitUntil(20_000) { manager.uiState.isDownloading && manager.uiState.downloadedBytes >= admittedBytes }
                    compose.onNodeWithTag("updates:progress").assertIsDisplayed()
                    activity.moveToState(Lifecycle.State.CREATED)
                    val stoppedBytes = manager.uiState.downloadedBytes
                    server.resumeDownload()
                    withTimeout(45_000) {
                        while (withContext(Dispatchers.IO) { status(downloads, ids.last()) } != DownloadManager.STATUS_SUCCESSFUL) delay(100)
                    }
                    assertEquals(stoppedBytes, manager.uiState.downloadedBytes)
                    assertTrue(!manager.uiState.isReadyToInstall)
                    activity.moveToState(Lifecycle.State.RESUMED)
                    compose.waitUntil(15_000) { manager.uiState.isReadyToInstall }
                    compose.onNodeWithTag("updates:install").performScrollTo().assertIsDisplayed()
                    capture(activity, "versions-update-ready")
                    assertEquals("content", withContext(Dispatchers.Main) { manager.installDownloadedUpdate() }?.data?.scheme)
                    endpoint(manager, server.url)
                    server.prepare("1.6.2", chunked = true)
                    withContext(Dispatchers.Main) {
                        manager.refresh()
                        check(manager.startDownload())
                    }
                    ids += settings.appUpdateDownloadId
                    compose.waitUntil(25_000) { manager.uiState.isDownloading && manager.uiState.downloadedBytes > 0L }
                    assertEquals(null, manager.uiState.totalBytes)
                    assertEquals(ProgressBarRangeInfo.Indeterminate,
                        compose.onNodeWithTag("updates:progress").fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo])
                    compose.onNodeWithTag("updates:percent").assertDoesNotExist()
                    capture(activity, "versions-download-unknown-size")
                    check(!TProxyService.isActive())
                }
            }
        } finally {
            withContext(Dispatchers.IO) { ids.forEach { downloads.remove(it) } }
            // The instrumentation process is killed immediately after; apply() can lose this reset.
            val preferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            check(preferences.edit()
                .putLong("appUpdateDownloadId", 0L)
                .putString("appUpdatePendingVersion", "")
                .commit())
            settings.refreshLinksOnOpen = oldRefresh
            settings.languageTag = oldLanguage
            settings.themeStyle = oldTheme
            instrumentation.runOnMainSync { AppCompatDelegate.setApplicationLocales(oldLocales) }
        }
    }

    private fun updater(activity: ActivityScenario<MainActivity>): AppUpdateManager {
        var manager: AppUpdateManager? = null
        activity.onActivity {
            val field = MainActivity::class.java.getDeclaredField("appUpdateManager\$delegate").apply { isAccessible = true }
            manager = (field.get(it) as Lazy<*>).value as AppUpdateManager
        }
        return checkNotNull(manager)
    }

    private fun endpoint(manager: AppUpdateManager, url: String) {
        AppUpdateManager::class.java.getDeclaredField("releasesUrl").apply { isAccessible = true }.set(manager, url)
    }

    private fun status(manager: DownloadManager, id: Long): Int = manager.query(DownloadManager.Query().setFilterById(id)).use {
        check(it.moveToFirst())
        it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
    }

    private fun capture(activity: ActivityScenario<MainActivity>, name: String) {
        compose.waitForIdle()
        repeat(2) {
            val frame = CountDownLatch(1)
            activity.onActivity {
                it.window.decorView.viewTreeObserver.registerFrameCommitCallback { frame.countDown() }
                it.window.decorView.invalidate()
            }
            check(frame.await(10, TimeUnit.SECONDS))
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val image = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(instrumentation.targetContext.cacheDir, "$name.png").outputStream().use {
                check(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { image.recycle() }
    }

    private class ReleaseServer(private val apk: File) : Closeable {
        private val listener = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val executor = Executors.newCachedThreadPool()
        @Volatile private var version = BuildConfig.VERSION_NAME
        @Volatile private var chunked = false
        @Volatile private var gate = CountDownLatch(0)
        val url = "http://127.0.0.1:${listener.localPort}/release"
        init {
            executor.submit {
                while (!listener.isClosed) {
                    try {
                        val socket = listener.accept()
                        executor.submit { socket.use(::respond) }
                    } catch (_: java.net.SocketException) { break }
                }
            }
        }
        fun prepare(version: String, chunked: Boolean) {
            this.version = version
            this.chunked = chunked
            gate = CountDownLatch(1)
        }
        fun resumeDownload() { gate.countDown() }
        private fun respond(socket: Socket) {
            socket.soTimeout = 10_000
            val input = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val path = input.readLine().split(' ')[1]
            var headers = 0
            while (input.readLine().isNotEmpty()) check(headers++ < 100)
            val output = socket.getOutputStream()
            if (path == "/release") {
                val body = JSONObject().put("tag_name", "v$version").put("html_url", url).put("assets", JSONArray()
                    .put(JSONObject().put("name", "yaxc-$version-x86_64.apk")
                        .put("browser_download_url", url.substringBeforeLast('/') + "/$version.apk"))).toString().toByteArray()
                output.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray())
                output.write(body)
                output.flush()
            } else {
                val unknown = chunked
                val sizeHeader = if (unknown) "Transfer-Encoding: chunked" else "Content-Length: ${apk.length()}"
                output.write(("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.android.package-archive\r\n$sizeHeader\r\nConnection: close\r\n\r\n").toByteArray())
                val activeGate = gate
                apk.inputStream().use { file ->
                    val bytes = ByteArray(65_536)
                    var written = 0L
                    var gated = false
                    while (true) {
                        val count = file.read(bytes)
                        if (count < 0) break
                        if (unknown) output.write("${count.toString(16)}\r\n".toByteArray())
                        output.write(bytes, 0, count)
                        if (unknown) output.write("\r\n".toByteArray())
                        output.flush()
                        written += count
                        if (!gated && written >= apk.length() / 3) {
                            gated = true
                            check(activeGate.await(60, TimeUnit.SECONDS))
                        }
                        Thread.sleep(5)
                    }
                }
                if (unknown) output.write("0\r\n\r\n".toByteArray())
                output.flush()
            }
        }
        override fun close() {
            gate.countDown()
            listener.close()
            executor.shutdownNow()
            check(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }
}
