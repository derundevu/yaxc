package io.github.derundevu.yaxc.helper

import android.app.Application
import io.github.derundevu.yaxc.testing.LoopbackHttpServer
import io.github.derundevu.yaxc.testing.MainDispatcherRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class DownloadHelperTest {
    @get:Rule val files = TemporaryFolder()
    @get:Rule val main = MainDispatcherRule(UnconfinedTestDispatcher())

    @Test
    fun successCommitsExactBinaryBytesBeforeCompletionAndRemovesStaging() = runBlocking {
        LoopbackHttpServer().use { server ->
            val bytes = ByteArray(16391) { (it * 31).toByte() }
            server.enqueue(bytes)
            val target = files.newFile("geoip.dat").apply { writeText("previous") }
            val observed = RecordingDownload {
                assertArrayEquals(bytes, target.readBytes())
                assertEquals(listOf("geoip.dat"), files.root.list()!!.toList())
            }
            start(server.url, target, observed).join()
            assertEquals(1, observed.completions)
            assertTrue(observed.errors.isEmpty())
            assertTrue(observed.progress.isNotEmpty())
            assertEquals(100, observed.progress.last())
            assertTrue(observed.progress.zipWithNext().all { (left, right) -> left <= right })
        }
    }

    @Test
    fun non200AndTruncatedResponsesPreserveInstalledResource() = runBlocking {
        LoopbackHttpServer().use { server ->
            val target = files.newFile("geosite.dat").apply { writeText("installed") }
            server.enqueue("denied".toByteArray(), status = 403)
            server.enqueue("partial".toByteArray(), declaredLength = 100L)
            repeat(2) {
                val listener = RecordingDownload()
                start(server.url, target, listener).join()
                assertEquals("installed", target.readText())
                assertEquals(0, listener.completions)
                assertEquals(1, listener.errors.size)
                assertEquals(listOf("geosite.dat"), files.root.list()!!.toList())
            }
        }
    }

    @Test
    fun failedChunkedTransferPreservesInstalledBytesAfterReceivingAValidPrefix() = runBlocking {
        LoopbackHttpServer().use { server ->
            server.enqueue { output ->
                output.write(
                    ("HTTP/1.1 200 OK\r\nConnection: close\r\nTransfer-Encoding: chunked\r\n\r\n" +
                        "3\r\nabc\r\nnot-a-chunk-length\r\n").toByteArray(Charsets.US_ASCII)
                )
                output.flush()
            }
            val target = files.newFile("geoip.dat").apply { writeText("installed") }
            val listener = RecordingDownload()
            start(server.url, target, listener).join()
            assertEquals("installed", target.readText())
            assertEquals(0, listener.completions)
            assertEquals(1, listener.errors.size)
            assertEquals(listOf("geoip.dat"), files.root.list()!!.toList())
        }
    }

    @Test
    fun unknownLengthSucceedsWithoutFabricatedProgress() = runBlocking {
        LoopbackHttpServer().use { server ->
            val bytes = "unknown length\u0000binary\u00ff".toByteArray()
            server.enqueue(bytes, declaredLength = null)
            val target = File(files.root, "geosite.dat")
            val listener = RecordingDownload()
            start(server.url, target, listener).join()
            assertArrayEquals(bytes, target.readBytes())
            assertEquals(1, listener.completions)
            assertTrue(listener.errors.isEmpty())
            assertTrue(listener.progress.isEmpty())
        }
    }

    @Test
    fun cancellationNeverCommitsOrCallsErrorOrCompletion() = runBlocking {
        LoopbackHttpServer().use { server ->
            val receiving = CountDownLatch(1)
            val release = CountDownLatch(1)
            server.enqueue { output ->
                LoopbackHttpServer.writeHeaders(output, length = 8192)
                output.write(ByteArray(4096) { 1 })
                output.flush()
                check(release.await(5, TimeUnit.SECONDS))
                output.write(ByteArray(4096) { 2 })
            }
            val target = files.newFile("geoip.dat").apply { writeText("installed") }
            val listener = RecordingDownload(onProgress = { receiving.countDown() })
            val job = start(server.url, target, listener)
            try {
                assertTrue(receiving.await(5, TimeUnit.SECONDS))
                job.cancel()
            } finally {
                release.countDown()
            }
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
            assertEquals("installed", target.readText())
            assertEquals(0, listener.completions)
            assertTrue(listener.errors.isEmpty())
            assertEquals(listOf("geoip.dat"), files.root.list()!!.toList())
        }
    }

    @Test
    fun overlappingDownloadsUseSeparateStagingAndOnlyPublishWholeResources() = runBlocking {
        LoopbackHttpServer().use { first ->
            LoopbackHttpServer().use { second ->
                val firstReceiving = CountDownLatch(1)
                val releaseFirst = CountDownLatch(1)
                val firstBytes = ByteArray(8192) { 17 }
                val secondBytes = ByteArray(12288) { 42 }
                first.enqueue { output ->
                    LoopbackHttpServer.writeHeaders(output, length = firstBytes.size.toLong())
                    output.write(firstBytes, 0, 4096)
                    output.flush()
                    check(releaseFirst.await(5, TimeUnit.SECONDS))
                    output.write(firstBytes, 4096, 4096)
                }
                second.enqueue(secondBytes)
                val target = files.newFile("geoip.dat").apply { writeText("installed") }
                val secondTarget = files.newFile("geosite.dat").apply { writeText("other installed") }
                val firstListener = RecordingDownload(onProgress = { firstReceiving.countDown() })
                val secondListener = RecordingDownload()
                val firstJob = start(first.url, target, firstListener)
                try {
                    assertTrue(firstReceiving.await(5, TimeUnit.SECONDS))
                    assertEquals("installed", target.readText())
                    start(second.url, secondTarget, secondListener).join()
                    assertArrayEquals(secondBytes, secondTarget.readBytes())
                    assertEquals("installed", target.readText())
                    assertEquals(1, secondListener.completions)
                } finally {
                    releaseFirst.countDown()
                }
                firstJob.join()
                assertArrayEquals(firstBytes, target.readBytes())
                assertArrayEquals(secondBytes, secondTarget.readBytes())
                assertEquals(1, firstListener.completions)
                assertTrue(firstListener.errors.isEmpty())
                assertEquals(setOf("geoip.dat", "geosite.dat"), files.root.list()!!.toSet())
            }
        }
    }

    @Test
    fun failedCommitReportsErrorAndCleansStagingWithoutReplacingDestination() = runBlocking {
        LoopbackHttpServer().use { server ->
            server.enqueue("download".toByteArray())
            val target = files.newFolder("geoip.dat")
            File(target, "owned").writeText("keep")
            val listener = RecordingDownload()
            start(server.url, target, listener).join()
            assertEquals("keep", File(target, "owned").readText())
            assertEquals(0, listener.completions)
            assertEquals(1, listener.errors.size)
            assertEquals(listOf("geoip.dat"), files.root.list()!!.toList())
        }
    }

    private fun CoroutineScope.start(url: String, target: File, listener: RecordingDownload) =
        DownloadHelper(this, url, target, listener).start()

    private class RecordingDownload(
        private val onProgress: (Int) -> Unit = {},
        private val onComplete: () -> Unit = {},
    ) : DownloadHelper.DownloadListener {
        val progress = mutableListOf<Int>()
        val errors = mutableListOf<Exception>()
        var completions = 0
        override fun onProgress(progress: Int) {
            this.progress += progress
            onProgress.invoke(progress)
        }
        override fun onError(exception: Exception) { errors += exception }
        override fun onComplete() {
            onComplete.invoke()
            completions++
        }
    }
}
