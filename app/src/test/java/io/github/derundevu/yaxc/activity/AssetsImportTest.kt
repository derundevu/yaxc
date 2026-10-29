package io.github.derundevu.yaxc.activity

import android.app.Application
import android.net.Uri
import android.os.Looper
import io.github.derundevu.yaxc.testing.NullableContentResolverShadow
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], shadows = [NullableContentResolverShadow::class])
class AssetsImportTest {
    @get:Rule val files = TemporaryFolder()
    private val resolver get() = RuntimeEnvironment.getApplication().contentResolver
    private val uri = Uri.parse("content://regression.resources/synthetic-resource")

    @Before
    fun resetProviderDeath() {
        NullableContentResolverShadow.nullStreamUri = null
    }

    @Test
    fun importsExactBytesOffMainAndClosesProviderBeforePublishing() = runBlocking {
        val target = installed()
        val bytes = ByteArray(17017) { (it * 13).toByte() }
        var closed = false
        val stream = object : ByteArrayInputStream(bytes) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                assertFalse(Thread.currentThread() === Looper.getMainLooper().thread)
                return super.read(buffer, offset, length)
            }
            override fun close() {
                assertEquals("installed", target.readText())
                closed = true
                super.close()
            }
        }
        shadowOf(resolver).registerInputStream(uri, stream)
        importAssetFromUri(resolver, uri, target)
        assertTrue(closed)
        assertArrayEquals(bytes, target.readBytes())
        assertOnlyInstalledResource()
    }

    @Test
    fun providerOpenFailureOrNullStreamCannotTruncateTheInstalledFile() = runBlocking {
        val target = installed()
        shadowOf(resolver).registerInputStreamSupplier(uri) { throw FileNotFoundException("Synthetic missing resource") }
        expectImportFailure(target)
        NullableContentResolverShadow.nullStreamUri = uri
        expectImportFailure(target)
        assertOnlyInstalledResource()
    }

    @Test
    fun readFailureAfterPartialCopyClosesStreamAndPreservesInstalledResource() = runBlocking {
        val target = installed()
        var closed = false
        shadowOf(resolver).registerInputStream(uri, object : InputStream() {
            private var copied = false
            override fun read(): Int = throw IOException("Synthetic interrupted provider")
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                assertFalse(Thread.currentThread() === Looper.getMainLooper().thread)
                if (copied) throw IOException("Synthetic interrupted provider")
                copied = true
                buffer[offset] = 42
                return 1
            }
            override fun close() { closed = true }
        })
        expectImportFailure(target)
        assertTrue(closed)
        assertOnlyInstalledResource()
    }

    @Test
    fun closeFailureIsNotReportedAsSuccessfulImportAndKeepsPreviousBytes() = runBlocking {
        val target = installed()
        shadowOf(resolver).registerInputStream(uri, object : ByteArrayInputStream("replacement".toByteArray()) {
            override fun close() { throw IOException("Synthetic close failure") }
        })
        expectImportFailure(target)
        assertOnlyInstalledResource()
    }

    @Test
    fun lifecycleStyleCancellationWhileProviderIsReadingNeverCommitsPartialOrCompleteCopy() = runBlocking {
        val target = installed()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var closed = false
        shadowOf(resolver).registerInputStream(uri, object : InputStream() {
            private var reads = 0
            override fun read(): Int = error("Bulk read expected")
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (reads++ == 0) {
                    buffer[offset] = 1
                    return 1
                }
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                return -1
            }
            override fun close() { closed = true }
        })
        val operation = launch(start = CoroutineStart.UNDISPATCHED) {
            importAssetFromUri(resolver, uri, target)
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            operation.cancel()
        } finally {
            release.countDown()
        }
        operation.cancelAndJoin()
        assertTrue(operation.isCancelled)
        assertTrue(closed)
        assertEquals("installed", target.readText())
        assertOnlyInstalledResource()
    }

    private fun installed() = files.newFile("geoip.dat").apply { writeText("installed") }

    private suspend fun expectImportFailure(target: File) {
        val failure = try {
            importAssetFromUri(resolver, uri, target)
            null
        } catch (error: IOException) {
            error
        }
        assertTrue("Provider failure must reach the activity's failure branch", failure is IOException)
        assertEquals("installed", target.readText())
    }

    private fun assertOnlyInstalledResource() {
        assertEquals(listOf("geoip.dat"), files.root.list()!!.toList())
    }
}
