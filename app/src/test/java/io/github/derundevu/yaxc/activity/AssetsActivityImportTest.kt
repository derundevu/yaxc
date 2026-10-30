package io.github.derundevu.yaxc.activity

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.derundevu.yaxc.helper.HelperSettingsTest
import io.github.derundevu.yaxc.testing.RegressionApplication
import io.github.derundevu.yaxc.testing.waitForAsyncState
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(application = RegressionApplication::class, sdk = [34], qualifiers = "w400dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AssetsActivityImportTest : HelperSettingsTest() {
    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = RuntimeEnvironment.getApplication()
    private val target get() = File(app.filesDir, "geoip.dat")
    private val uri = Uri.parse("content://regression.resources/picked-geoip")
    private var controller: ActivityController<AssetsActivity>? = null
    private lateinit var activity: AssetsActivity

    @Before
    fun seedInstalledResource() {
        target.writeText("installed")
        settings.installedGeoIpSourceLabel = "Synthetic installed source"
        settings.installedGeoIpSourceUrl = "http://127.0.0.1/previous-resource"
    }

    @After
    fun closeActivityAndFiles() {
        controller?.pause()?.stop()?.destroy()
        target.delete()
    }

    @Test
    fun pickerSuccessCommitsBytesAndOnlyThenUpdatesInstalledSourceMetadata() {
        val bytes = ByteArray(10001) { (it * 19).toByte() }
        val closed = AtomicBoolean()
        shadowOf(app.contentResolver).registerInputStream(uri, object : ByteArrayInputStream(bytes) {
            override fun close() {
                assertEquals("installed", target.readText())
                assertEquals("Synthetic installed source", settings.installedGeoIpSourceLabel)
                closed.set(true)
            }
        })
        openActivity()
        deliverPickedUri()
        compose.waitForAsyncState(5000) { settings.installedGeoIpSourceUrl == "file://local" }
        assertTrue(closed.get())
        assertArrayEquals(bytes, target.readBytes())
        assertTrue(settings.installedGeoIpSourceLabel.isNotBlank())
        assertFalse(settings.installedGeoIpSourceLabel == "Synthetic installed source")
        assertNoResourceStagingFiles()
    }

    @Test
    fun pickerProviderFailurePreservesBytesAndInstalledSourceInsteadOfClaimingLocalInstall() {
        val closed = AtomicBoolean()
        shadowOf(app.contentResolver).registerInputStream(uri, object : InputStream() {
            private var reads = 0
            override fun read(): Int = throw IOException("Synthetic provider failure")
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (reads++ > 0) throw IOException("Synthetic provider failure")
                buffer[offset] = 42
                return 1
            }
            override fun close() { closed.set(true) }
        })
        openActivity()
        deliverPickedUri()
        compose.waitForAsyncState(5000) { closed.get() && noResourceStagingFiles() }
        assertTrue(closed.get())
        assertEquals("installed", target.readText())
        assertOriginalSourceMetadata()
        assertNoResourceStagingFiles()
    }

    @Test
    fun destroyingActivityCancelsInFlightPickerWithoutPublishingOrChangingMetadata() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = AtomicBoolean()
        registerBlockedProvider(entered, release, closed)
        openActivity()
        deliverPickedUri()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        try {
            controller!!.pause().stop().destroy()
            controller = null
        } finally {
            release.countDown()
        }
        compose.waitForAsyncState(5000) { closed.get() && noResourceStagingFiles() }
        assertEquals("installed", target.readText())
        assertOriginalSourceMetadata()
    }

    @Test
    fun deletingResourceDuringPickerCancelsImportAndCannotRecreateDeletedFile() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = AtomicBoolean()
        registerBlockedProvider(entered, release, closed)
        openActivity()
        deliverPickedUri()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            compose.onNode(
                hasTestTag("asset:delete") and hasAnyAncestor(hasTestTag("asset:geoip.dat")),
                useUnmergedTree = true,
            ).performClick()
        } finally {
            release.countDown()
        }
        compose.waitForAsyncState(5000) { closed.get() && !target.exists() && noResourceStagingFiles() }
        assertFalse(target.exists())
        assertEquals("", settings.installedGeoIpSourceLabel)
        assertEquals("", settings.installedGeoIpSourceUrl)
    }

    private fun openActivity() {
        controller = Robolectric.buildActivity(AssetsActivity::class.java).setup()
        activity = controller!!.get()
    }

    private fun deliverPickedUri() {
        compose.onNode(
            hasTestTag("asset:pick") and hasAnyAncestor(hasTestTag("asset:geoip.dat")),
            useUnmergedTree = true,
        ).performScrollTo().performClick()
        val launch = shadowOf(activity).nextStartedActivityForResult
        assertEquals(Intent.ACTION_GET_CONTENT, launch.intent.action)
        shadowOf(activity).receiveResult(launch.intent, Activity.RESULT_OK, Intent().setData(uri))
    }

    private fun registerBlockedProvider(entered: CountDownLatch, release: CountDownLatch, closed: AtomicBoolean) {
        shadowOf(app.contentResolver).registerInputStream(uri, object : InputStream() {
            override fun read(): Int = error("Bulk reads expected")
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                return -1
            }
            override fun close() { closed.set(true) }
        })
    }

    private fun assertOriginalSourceMetadata() {
        assertEquals("Synthetic installed source", settings.installedGeoIpSourceLabel)
        assertEquals("http://127.0.0.1/previous-resource", settings.installedGeoIpSourceUrl)
    }

    private fun noResourceStagingFiles() = app.filesDir.listFiles().orEmpty().none {
        it.name.startsWith(".geoip.dat.")
    }

    private fun assertNoResourceStagingFiles() = assertTrue(noResourceStagingFiles())
}
