package io.github.derundevu.yaxc.helper

import android.app.Application
import android.app.DownloadManager
import android.content.Intent
import android.net.Uri
import io.github.derundevu.yaxc.BuildConfig
import io.github.derundevu.yaxc.Settings
import io.github.derundevu.yaxc.testing.LoopbackHttpServer
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBuild
import org.robolectric.shadows.ShadowDownloadManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class AppUpdateManagerTest : HelperSettingsTest() {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val downloads: DownloadManager get() = app.getSystemService(DownloadManager::class.java)
    private val shadowDownloads: ShadowDownloadManager get() = shadowOf(downloads)

    @Before
    fun setDeviceAbi() {
        ShadowBuild.setSupportedAbis(arrayOf("arm64-v8a", "armeabi-v7a"))
        // Real DownloadManager IDs are positive; Robolectric starts its synthetic counter at zero.
        val reserved = downloads.enqueue(DownloadManager.Request(Uri.parse("http://127.0.0.1/unused.apk")))
        downloads.remove(reserved)
    }

    @Test
    fun refreshParsesReleaseJsonAndPrefersDeviceAbiOrderRatherThanAssetOrder() = runBlocking {
        LoopbackHttpServer().use { server ->
            server.enqueue(release(server, "v999.1.0", "yaxc-armeabi-v7a.apk", "yaxc-arm64-v8a.apk"))
            val manager = manager(server)
            manager.refresh()
            val release = requireNotNull(manager.uiState.availableRelease)
            assertEquals("999.1.0", release.versionName)
            assertEquals("yaxc-arm64-v8a.apk", release.asset.name)
            assertEquals("${server.url}/yaxc-arm64-v8a.apk", release.asset.downloadUrl)
            assertEquals("${server.url}/release", release.htmlUrl)
            assertFalse(manager.uiState.isChecking)
            assertFalse(manager.uiState.isUpToDate)
            assertNull(manager.uiState.errorMessage)
            val request = server.requests.poll(5, TimeUnit.SECONDS)!!
            assertTrue(request.any { it.equals("Accept: application/vnd.github+json", ignoreCase = true) })
            assertTrue(request.any { it.equals("User-Agent: yaxc/${BuildConfig.VERSION_NAME}", ignoreCase = true) })
        }
    }

    @Test
    fun x86DeviceDoesNotDownloadAnX8664ApkAndUnsupportedAbiDoesNotFallback() = runBlocking {
        LoopbackHttpServer().use { server ->
            ShadowBuild.setSupportedAbis(arrayOf("x86"))
            server.enqueue(release(server, "999.1", "yaxc-x86_64.apk", "yaxc-x86.apk"))
            val manager = manager(server)
            manager.refresh()
            assertEquals("yaxc-x86.apk", manager.uiState.availableRelease!!.asset.name)
            server.enqueue(release(server, "999.1", "yaxc-x86_64.apk", "yaxc-arm64-v8a.apk"))
            manager.refresh()
            assertNull(manager.uiState.availableRelease)
            assertNotNull(manager.uiState.errorMessage)
            assertFalse(manager.startDownload())
            assertEquals(0, shadowDownloads.requestCount)
        }
    }

    @Test
    fun releaseNameFallbackAndAbiFallbackIgnoreNonApkAndBlankUrls() = runBlocking {
        LoopbackHttpServer().use { server ->
            val payload = JSONObject(String(release(server, "", "yaxc-armeabi-v7a.APK")))
                .put("name", " v999.2.0 ")
            payload.getJSONArray("assets")
                .put(JSONObject().put("name", "yaxc-arm64-v8a.apk").put("browser_download_url", " "))
                .put(JSONObject().put("name", "yaxc-arm64-v8a.apk.sha256").put("browser_download_url", server.url))
            server.enqueue(payload.toString().toByteArray())
            val manager = manager(server)
            manager.refresh()
            assertEquals("999.2.0", manager.uiState.availableRelease!!.versionName)
            assertEquals("yaxc-armeabi-v7a.APK", manager.uiState.availableRelease!!.asset.name)
        }
    }

    @Test
    fun equalAndOlderReleaseNeedNoCompatibleApkAndAreUpToDate() = runBlocking {
        LoopbackHttpServer().use { server ->
            val manager = manager(server)
            listOf(BuildConfig.VERSION_NAME, "0.0.1").forEach { version ->
                server.enqueue(release(server, version))
                manager.refresh()
                assertTrue(manager.uiState.isUpToDate)
                assertFalse(manager.uiState.isChecking)
                assertNull(manager.uiState.availableRelease)
                assertNull(manager.uiState.errorMessage)
                assertFalse(manager.startDownload())
            }
        }
    }

    @Test
    fun httpMalformedAndNoCompatibleAssetFailuresClearStaleAvailableRelease() = runBlocking {
        LoopbackHttpServer().use { server ->
            val manager = manager(server)
            server.enqueue(release(server, "999.1", "yaxc-arm64-v8a.apk"))
            manager.refresh()
            assertNotNull(manager.uiState.availableRelease)
            val errors = listOf(
                Pair("unavailable".toByteArray(), 503),
                Pair("{bad json".toByteArray(), 200),
                Pair(release(server, "999.2", "notes.txt"), 200),
            )
            errors.forEach { (body, status) ->
                server.enqueue(body, status)
                manager.refresh()
                assertFalse(manager.uiState.isChecking)
                assertFalse(manager.uiState.isUpToDate)
                assertNull(manager.uiState.availableRelease)
                assertNotNull(manager.uiState.errorMessage)
            }
        }
    }

    @Test
    fun enqueueUsesSelectedIdentityAndRepeatedStartDoesNotDuplicatePendingDownload() = runBlocking {
        LoopbackHttpServer().use { server ->
            val manager = availableManager(server)
            assertTrue(manager.startDownload())
            val id = settings.appUpdateDownloadId
            val request = request(id)
            assertTrue(id != 0L)
            assertEquals("999.1.0", settings.appUpdatePendingVersion)
            assertEquals("${server.url}/yaxc-arm64-v8a.apk", request.uri.toString())
            assertTrue(request.destination.toString().endsWith("/Download/yaxc-arm64-v8a.apk"))
            assertEquals("application/vnd.android.package-archive", request.mimeType.toString())
            assertTrue(request.allowedOverMetered)
            assertTrue(request.allowedOverRoaming)
            request.status = DownloadManager.STATUS_PENDING
            assertTrue(manager.startDownload())
            assertEquals(id, settings.appUpdateDownloadId)
            assertEquals(1, shadowDownloads.requestCount)
        }
    }

    @Test
    fun pendingRunningAndPausedSurviveRecreationAndCannotBeInstalled() = runBlocking {
        LoopbackHttpServer().use { server ->
            val manager = availableManager(server)
            manager.startDownload()
            val id = settings.appUpdateDownloadId
            listOf(DownloadManager.STATUS_PENDING, DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PAUSED)
                .forEach { status ->
                    request(id).status = status
                    val restored = AppUpdateManager(app, Settings(app), server.url)
                    restored.syncPendingDownloadState()
                    assertEquals("999.1.0", restored.uiState.pendingVersion)
                    assertTrue(restored.uiState.isDownloading)
                    assertFalse(restored.uiState.isReadyToInstall)
                    assertNull(restored.installDownloadedUpdate())
                    assertEquals(id, settings.appUpdateDownloadId)
                    assertEquals(1, shadowDownloads.requestCount)
                }
        }
    }

    @Test
    fun missingAndFailedDownloadsClearTrackingAndFailedRequestIsRemoved() = runBlocking {
        LoopbackHttpServer().use { server ->
            val manager = availableManager(server)
            repeat(2) { attempt ->
                manager.startDownload()
                val id = settings.appUpdateDownloadId
                if (attempt == 0) downloads.remove(id) else request(id).status = DownloadManager.STATUS_FAILED
                manager.syncPendingDownloadState()
                assertEquals(0L, settings.appUpdateDownloadId)
                assertEquals("", settings.appUpdatePendingVersion)
                assertNull(manager.uiState.pendingVersion)
                assertFalse(manager.uiState.isDownloading)
                assertFalse(manager.uiState.isReadyToInstall)
                assertNull(manager.installDownloadedUpdate())
                assertNull(shadowDownloads.getRequest(id))
            }
        }
    }

    @Test
    fun successfulCompletionTracksOnlyOwnedIdAndRecreationRetainsInstallUri() = runBlocking {
        LoopbackHttpServer().use { server ->
            val manager = availableManager(server)
            manager.startDownload()
            val id = settings.appUpdateDownloadId
            request(id).status = DownloadManager.STATUS_RUNNING
            manager.syncPendingDownloadState()
            request(id).status = DownloadManager.STATUS_SUCCESSFUL
            manager.handleDownloadComplete(id + 100)
            assertTrue(manager.uiState.isDownloading)
            assertFalse(manager.uiState.isReadyToInstall)
            manager.handleDownloadComplete(id)
            assertFalse(manager.uiState.isDownloading)
            assertTrue(manager.uiState.isReadyToInstall)
            val recreated = AppUpdateManager(app, Settings(app), server.url)
            recreated.syncPendingDownloadState()
            val install = requireNotNull(recreated.installDownloadedUpdate())
            assertEquals(Intent.ACTION_VIEW, install.action)
            assertEquals("content", install.data!!.scheme)
            assertEquals(id.toString(), install.data!!.lastPathSegment)
            assertEquals("application/vnd.android.package-archive", install.type)
            assertTrue(install.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
            assertTrue(install.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertEquals(id, settings.appUpdateDownloadId)
        }
    }

    @Test
    fun newerReleaseDoesNotRelabelPendingApkOrChangeItsInstallUriUntilExplicitDownload() = runBlocking {
        LoopbackHttpServer().use { server ->
            val manager = availableManager(server)
            manager.startDownload()
            val oldId = settings.appUpdateDownloadId
            request(oldId).status = DownloadManager.STATUS_SUCCESSFUL
            manager.handleDownloadComplete(oldId)
            val oldUri = manager.installDownloadedUpdate()!!.data
            server.enqueue(release(server, "999.2.0", "yaxc-999.2.0-arm64-v8a.apk"))
            manager.refresh()
            assertEquals("999.2.0", manager.uiState.availableRelease!!.versionName)
            assertEquals("999.1.0", manager.uiState.pendingVersion)
            assertTrue(manager.uiState.isReadyToInstall)
            assertEquals(oldUri, manager.installDownloadedUpdate()!!.data)
            assertTrue(manager.startDownload())
            val newId = settings.appUpdateDownloadId
            assertNotEquals(oldId, newId)
            assertEquals("999.2.0", settings.appUpdatePendingVersion)
            assertNull(shadowDownloads.getRequest(oldId))
            assertTrue(request(newId).uri.toString().endsWith("yaxc-999.2.0-arm64-v8a.apk"))
            assertFalse(manager.uiState.isReadyToInstall)
            assertNull(manager.installDownloadedUpdate())
        }
    }

    @Test
    fun obsoleteAndOrphanedTrackingIsClearedRatherThanReinstalled() = runBlocking {
        LoopbackHttpServer().use { server ->
            val manager = availableManager(server)
            manager.startDownload()
            val id = settings.appUpdateDownloadId
            request(id).status = DownloadManager.STATUS_SUCCESSFUL
            settings.appUpdatePendingVersion = BuildConfig.VERSION_NAME
            manager.syncPendingDownloadState()
            assertEquals(0L, settings.appUpdateDownloadId)
            assertEquals("", settings.appUpdatePendingVersion)
            assertNull(shadowDownloads.getRequest(id))
            assertNull(manager.installDownloadedUpdate())
            settings.appUpdatePendingVersion = "999.1"
            manager.syncPendingDownloadState()
            assertEquals("", settings.appUpdatePendingVersion)
            assertNull(manager.uiState.pendingVersion)
        }
    }

    @Test
    fun cancelledRefreshPropagatesCancellationWithoutPublishingReleaseOrError() = runBlocking {
        LoopbackHttpServer().use { server ->
            val entered = CountDownLatch(1)
            val allowResponse = CountDownLatch(1)
            server.enqueue { output ->
                LoopbackHttpServer.writeHeaders(output)
                entered.countDown()
                check(allowResponse.await(5, TimeUnit.SECONDS))
                output.write(release(server, "999.1", "yaxc-arm64-v8a.apk"))
            }
            val manager = manager(server)
            val checkJob = launch(start = CoroutineStart.UNDISPATCHED) { manager.refresh() }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                checkJob.cancel()
            } finally {
                allowResponse.countDown()
            }
            checkJob.join()
            assertTrue(checkJob.isCancelled)
            assertFalse(manager.uiState.isChecking)
            assertNull(manager.uiState.availableRelease)
            assertNull(manager.uiState.errorMessage)
            assertEquals(0L, settings.appUpdateDownloadId)
        }
    }

    private fun manager(server: LoopbackHttpServer) = AppUpdateManager(app, settings, server.url)

    private suspend fun availableManager(server: LoopbackHttpServer): AppUpdateManager {
        server.enqueue(release(server, "999.1.0", "yaxc-arm64-v8a.apk"))
        return manager(server).also { it.refresh() }
    }

    private fun request(id: Long): ShadowDownloadManager.ShadowRequest =
        Shadow.extract(shadowDownloads.getRequest(id))

    private fun release(server: LoopbackHttpServer, version: String, vararg names: String): ByteArray =
        JSONObject().put("tag_name", version).put("html_url", "${server.url}/release")
            .put("assets", JSONArray().apply {
                names.forEach { name ->
                    put(JSONObject().put("name", name).put("browser_download_url", "${server.url}/$name"))
                }
            }).toString().toByteArray()
}
