package io.github.derundevu.yaxc.repository

import android.app.Application
import android.content.Context
import android.os.Looper
import io.github.derundevu.yaxc.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ConnectionRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var app: Application
    private lateinit var settings: Settings
    private val repositories = mutableListOf<ConnectionRepository>()
    private val now = 1_800_000_000_000L

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        settings = Settings(app)
    }

    @After
    fun tearDown() {
        repositories.forEach { it.close() }
        app.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun defaultOffNeverPollsBeforeActualStartAndNeverWritesDestinationMetadata() = runTest {
        val disk = File(temporary.root, "history.json")
        disk.writeText("leftover-sensitive-history")
        val unrelated = File(temporary.root, "unrelated").apply { writeText("leave-me") }
        val reads = AtomicInteger()
        val repo = repository(disk)
        runCurrent()
        assertFalse(settings.connectionHistoryOnDisk)
        assertFalse(repo.state.value.persistOnDisk)
        assertFalse(disk.exists())
        assertEquals("leave-me", unrelated.readText())
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(0, reads.get())
        repo.startRuntime(emptyMap(), { reads.incrementAndGet(); snapshot("runtime", row("one")) }, {})
        runCurrent()
        assertEquals(1, reads.get())
        assertEquals(ConnectionCaptureStatus.Running, repo.state.value.status)
        assertEquals("one", repo.state.value.connections.single().id)
        assertFalse(disk.exists())
        repo.endRuntime(snapshot("runtime", row("one", state = "closed", upload = 17)))
        runCurrent()
        val stoppedReads = reads.get()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(stoppedReads, reads.get())
        assertEquals(17L, repo.state.value.connections.single().upload)
        assertEquals(ConnectionCaptureStatus.Stopped, repo.state.value.status)
        assertFalse(disk.exists())
    }

    @Test
    fun immediateEndAndRestartPreservesFinalSnapshotForEitherLexicalEpochOrder() = runTest {
        for ((oldEpoch, newEpoch) in listOf("z-old" to "a-new", "a-old" to "z-new")) {
            val repo = repository(File(temporary.root, "$oldEpoch.json"))
            repo.startRuntime(mapOf("old.pkg" to 100), { snapshot(oldEpoch, row("old", uid = 100).put("profileName", "Old config")) }, {})
            runCurrent()
            repo.endRuntime(snapshot(oldEpoch, row("old", state = "closed", upload = 999, download = 333, uid = 100)))
            repo.startRuntime(mapOf("new.pkg" to 100), { snapshot(newEpoch, row("new", upload = 4, uid = 100).put("profileName", "New config")) }, {})
            runCurrent()
            val rows = repo.state.value.connections.associateBy { it.id }
            assertEquals(ConnectionCaptureStatus.Running, repo.state.value.status)
            assertEquals(999L, rows.getValue("old").upload)
            assertEquals(333L, rows.getValue("old").download)
            assertEquals("closed", rows.getValue("old").state)
            assertEquals("Old config", rows.getValue("old").profileName)
            assertEquals("old.pkg", rows.getValue("old").apps.single().packageName)
            assertEquals("New config", rows.getValue("new").profileName)
            assertTrue(rows.getValue("new").apps.isEmpty())
            repo.close()
        }
    }

    @Test
    fun blockedOldReaderCannotDelayRestartOrOverwriteFinalOrNewEpochWhenItReturns() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val repo = ConnectionRepository(
            app, settings, dispatcher = Dispatchers.IO,
            historyFile = File(temporary.root, "late.json"), packageLabel = { it },
        ).also(repositories::add)
        try {
            repo.startRuntime(emptyMap(), {
                entered.countDown()
                try {
                    check(release.await(5, TimeUnit.SECONDS))
                    snapshot("old", row("old", upload = 1))
                } finally {
                    exited.countDown()
                }
            }, {})
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            repo.endRuntime(snapshot("old", row("old", state = "closed", upload = 777)))
            repo.startRuntime(emptyMap(), { snapshot("new", row("new", upload = 22)) }, {})
            val running = withTimeout(5_000) {
                repo.state.first { state -> state.connections.any { it.id == "new" } }
            }
            assertEquals(777L, running.connections.single { it.id == "old" }.upload)
            release.countDown()
            assertTrue(exited.await(5, TimeUnit.SECONDS))
            repo.endRuntime(snapshot("new", row("new", state = "closed", upload = 55)))
            val stopped = withTimeout(5_000) { repo.state.first { it.status == ConnectionCaptureStatus.Stopped } }
            assertEquals(777L, stopped.connections.single { it.id == "old" }.upload)
            assertEquals(55L, stopped.connections.single { it.id == "new" }.upload)
            assertEquals("closed", stopped.connections.single { it.id == "old" }.state)
        } finally {
            release.countDown()
            repo.close()
        }
    }

    @Test
    fun profileNameUpdatesOnlyAffectNewObservationsAndSharedUidIncludesEveryPackage() = runTest {
        val raw = AtomicReference(snapshot("epoch", row("old", uid = 100).put("profileName", "First name"), row("unknown")))
        val repo = repository(File(temporary.root, "identities.json"))
        repo.startRuntime(mapOf("b.pkg" to 100, "a.pkg" to 100), raw::get, {})
        runCurrent()
        repo.updatePackages(mapOf("replacement.pkg" to 100))
        raw.set(snapshot("epoch", row("old", uid = 100).put("profileName", "Renamed"),
            row("new", uid = 100).put("profileName", "Renamed"), row("unknown")))
        advanceTimeBy(1_000)
        runCurrent()
        val rows = repo.state.value.connections.associateBy { it.id }
        assertEquals("First name", rows.getValue("old").profileName)
        assertEquals("Renamed", rows.getValue("new").profileName)
        assertEquals(listOf("a.pkg", "b.pkg"), rows.getValue("old").apps.map { it.packageName })
        assertEquals(listOf("label:a.pkg", "label:b.pkg"), rows.getValue("old").apps.map { it.name })
        assertTrue(rows.getValue("new").apps.isEmpty())
        assertTrue(rows.getValue("unknown").apps.isEmpty())
    }

    @Test
    fun optInReloadInterruptsActiveAndDisablingDeletesOwnedFilesWithoutErasingMemory() = runTest {
        val disk = File(temporary.root, "persist.json")
        val repo = repository(disk)
        repo.startRuntime(mapOf("gone.pkg" to 100), { snapshot("first", row("active", uid = 100, upload = 42).put("profileName", "Captured config")) }, {})
        runCurrent()
        repo.setPersistence(true)
        runCurrent()
        assertTrue(settings.connectionHistoryOnDisk)
        assertTrue(disk.isFile)
        assertEquals("active", JSONObject(disk.readText()).getJSONArray("connections").getJSONObject(0).getString("state"))
        repo.close() // Simulated process loss, deliberately without endRuntime.
        val restored = repository(disk)
        runCurrent()
        val record = restored.state.value.connections.single()
        assertEquals("interrupted", record.state)
        assertEquals(now, record.endedAt)
        assertEquals(42L, record.upload)
        assertEquals("Captured config", record.profileName)
        assertEquals(listOf(ConnectionApp("gone.pkg", "label:gone.pkg")), record.apps)
        assertEquals(ConnectionCaptureStatus.Stopped, restored.state.value.status)
        restored.setPersistence(false)
        runCurrent()
        assertEquals(listOf(record), restored.state.value.connections)
        assertFalse(settings.connectionHistoryOnDisk)
        assertFalse(restored.state.value.persistOnDisk)
        assertFalse(disk.exists())
        assertFalse(File(disk.path + ".bak").exists())
        assertFalse(File(disk.path + ".new").exists())
    }

    @Test
    fun externalPreferenceChangesAreObservedWithoutAnActiveRuntime() = runTest {
        val disk = File(temporary.root, "external.json")
        val repo = repository(disk)
        runCurrent()
        val preferences = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
        preferences.edit().putBoolean("connectionHistoryOnDisk", true).commit()
        shadowOf(Looper.getMainLooper()).idle()
        runCurrent()
        assertTrue(repo.state.value.persistOnDisk)
        assertTrue(disk.exists())
        preferences.edit().putBoolean("connectionHistoryOnDisk", false).commit()
        shadowOf(Looper.getMainLooper()).idle()
        runCurrent()
        assertFalse(repo.state.value.persistOnDisk)
        assertFalse(disk.exists())
    }

    @Test
    fun diskSnapshotsAreThrottledButStopFlushesFinalBytesAndUnknownFieldsNeverPersist() = runTest {
        val disk = File(temporary.root, "throttle.json")
        val raw = AtomicReference(snapshot("epoch", row("active", upload = 1)).let {
            JSONObject(it).put("config", "raw-secret-config").toString()
        })
        val repo = repository(disk)
        repo.startRuntime(emptyMap(), raw::get, {})
        runCurrent()
        repo.setPersistence(true)
        runCurrent()
        assertEquals(1L, savedUpload(disk))
        assertFalse(disk.readText().contains("raw-secret-config"))
        raw.set(snapshot("epoch", row("active", upload = 10)))
        advanceTimeBy(4_999)
        runCurrent()
        assertEquals(10L, repo.state.value.connections.single().upload)
        assertEquals(1L, savedUpload(disk))
        advanceTimeBy(1)
        runCurrent()
        assertEquals(10L, savedUpload(disk))
        repo.endRuntime(snapshot("epoch", row("active", state = "closed", upload = 99)))
        runCurrent()
        assertEquals(99L, savedUpload(disk))
        assertEquals("closed", JSONObject(disk.readText()).getJSONArray("connections").getJSONObject(0).getString("state"))
    }

    @Test
    fun clearDelegatesOnlyClosedHistoryAndPreservesLiveCountersIncludingPersistedCopy() = runTest {
        val disk = File(temporary.root, "clear.json")
        val active = row("active", upload = 90, download = 80)
        val raw = AtomicReference(snapshot("epoch", active, row("closed", state = "closed")))
        val clears = AtomicInteger()
        val repo = repository(disk)
        repo.startRuntime(emptyMap(), raw::get, {
            clears.incrementAndGet()
            raw.set(snapshot("epoch", active))
        })
        runCurrent()
        repo.setPersistence(true)
        runCurrent()
        repo.clearHistory()
        runCurrent()
        assertEquals(1, clears.get())
        val record = repo.state.value.connections.single()
        assertEquals("active", record.state)
        assertEquals(90L, record.upload)
        assertEquals(80L, record.download)
        assertEquals(1, JSONObject(disk.readText()).getJSONArray("connections").length())
        assertEquals(90L, savedUpload(disk))
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf(record), repo.state.value.connections)
    }

    @Test
    fun thrownReaderFailureIsPrivateAndNextSuccessfulPollRecoversWithoutLosingHistory() = runTest {
        val raw = AtomicReference<String?>(snapshot("epoch", row("active", upload = 3)))
        val repo = repository(File(temporary.root, "reader-error.json"))
        repo.startRuntime(emptyMap(), { raw.get() ?: error("private native configuration diagnostic") }, {})
        runCurrent()
        raw.set(null)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(ConnectionCaptureStatus.Error, repo.state.value.status)
        assertFalse(checkNotNull(repo.state.value.error).contains("configuration"))
        assertEquals(3L, repo.state.value.connections.single().upload)
        raw.set(snapshot("epoch", row("active", upload = 9)))
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(ConnectionCaptureStatus.Running, repo.state.value.status)
        assertNull(repo.state.value.error)
        assertEquals(9L, repo.state.value.connections.single().upload)
    }

    @Test
    fun snapshotAlreadyInFlightAtClearCannotResurrectClosedRows() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val nativeCleared = CountDownLatch(1)
        val reads = AtomicInteger()
        val raw = AtomicReference(snapshot("epoch", row("active", upload = 90), row("closed", state = "closed")))
        val repo = ConnectionRepository(
            app, settings, dispatcher = Dispatchers.IO,
            historyFile = File(temporary.root, "clear-race.json"), packageLabel = { it },
        ).also(repositories::add)
        try {
            repo.startRuntime(emptyMap(), {
                val captured = raw.get()
                if (reads.incrementAndGet() == 2) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
                captured
            }, {
                raw.set(snapshot("epoch", row("active", upload = 100)))
                nativeCleared.countDown()
            })
            withTimeout(5_000) { repo.state.first { it.connections.size == 2 } }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            repo.clearHistory()
            release.countDown()
            assertTrue(nativeCleared.await(5, TimeUnit.SECONDS))
            val latest = withTimeout(5_000) {
                repo.state.first { it.connections.any { row -> row.id == "active" && row.upload == 100L } }
            }
            assertEquals(listOf("active"), latest.connections.map { it.id })
        } finally {
            release.countDown()
            repo.close()
        }
    }

    @Test
    fun unsupportedPreservesHistoryAndReaderAndStorageErrorsArePrivateAndNonFatal() = runTest {
        val raw = AtomicReference(snapshot("epoch", row("active", upload = 3)))
        val blocker = File(temporary.root, "not-a-directory").apply { writeText("block") }
        val repo = repository(File(blocker, "history.json"))
        repo.startRuntime(emptyMap(), raw::get, {})
        runCurrent()
        repo.setPersistence(true)
        runCurrent()
        assertNotNull(repo.state.value.error)
        raw.set("raw-password-config-is-not-json")
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(ConnectionCaptureStatus.Error, repo.state.value.status)
        assertFalse(checkNotNull(repo.state.value.error).contains("raw-password"))
        assertEquals(3L, repo.state.value.connections.single().upload)
        raw.set(snapshot("epoch", row("active", upload = 12)))
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(ConnectionCaptureStatus.Running, repo.state.value.status)
        assertEquals(12L, repo.state.value.connections.single().upload)
        assertNotNull(repo.state.value.error) // Storage failure remains independently visible.
        repo.unsupportedRuntime()
        runCurrent()
        assertEquals(ConnectionCaptureStatus.Unsupported, repo.state.value.status)
        assertEquals("interrupted", repo.state.value.connections.single().state)
        repo.setPersistence(false)
        runCurrent()
        assertNull(repo.state.value.error)
    }

    @Test
    fun directRuntimeReplacementInterruptsPreviousActiveAndAccumulatesNativeOverflow() = runTest {
        val repo = repository(File(temporary.root, "replace.json"))
        repo.startRuntime(emptyMap(), {
            JSONObject(snapshot("old", row("old", upload = 33))).put("dropped", 5).toString()
        }, {})
        runCurrent()
        repo.startRuntime(emptyMap(), {
            JSONObject(snapshot("new", row("new", upload = 44))).put("dropped", 2).toString()
        }, {})
        runCurrent()
        assertEquals("interrupted", repo.state.value.connections.single { it.id == "old" }.state)
        assertEquals(33L, repo.state.value.connections.single { it.id == "old" }.upload)
        assertEquals("active", repo.state.value.connections.single { it.id == "new" }.state)
        assertEquals(7L, repo.state.value.dropped)
    }

    @Test
    fun closedHistoryExpiresAtTwentyFourHoursEvenWithNoRuntimeOrOpenScreen() = runTest {
        val disk = File(temporary.root, "ttl.json")
        val repo = repository(disk)
        val closed = snapshot("epoch", row("closed", state = "closed"))
        repo.startRuntime(emptyMap(), { closed }, {})
        runCurrent()
        repo.endRuntime(closed)
        repo.setPersistence(true)
        runCurrent()
        val ttl = 24 * 60 * 60 * 1000L
        advanceTimeBy(ttl - 1)
        runCurrent()
        assertEquals(1, repo.state.value.connections.size)
        advanceTimeBy(1)
        runCurrent()
        assertTrue(repo.state.value.connections.isEmpty())
        assertEquals(0, JSONObject(disk.readText()).getJSONArray("connections").length())
        assertEquals(ConnectionCaptureStatus.Stopped, repo.state.value.status)
    }

    @Test
    fun stoppedTailPreservesFinalCreditsAndStopsEarlyOnceTerminalBytesAreExact() = runTest {
        val disk = File(temporary.root, "tail.json")
        val raw = AtomicReference(snapshot("process", row("one", upload = 3)))
        val reads = AtomicInteger()
        val repo = repository(disk)
        repo.startRuntime(emptyMap(), { reads.incrementAndGet(); raw.get() }, {})
        runCurrent()
        repo.setPersistence(true)
        runCurrent()
        val interrupted = row("one", state = "interrupted", upload = 9).put("liveBytesExact", false)
        repo.endRuntime(snapshot("process", interrupted))
        runCurrent()
        assertEquals(9L, repo.state.value.connections.single().upload)
        assertFalse(repo.state.value.connections.single().liveBytesExact)
        raw.set(snapshot("process", row("one", state = "interrupted", upload = 99)))
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(ConnectionCaptureStatus.Stopped, repo.state.value.status)
        assertEquals(99L, repo.state.value.connections.single().upload)
        assertTrue(repo.state.value.connections.single().liveBytesExact)
        assertEquals(99L, savedUpload(disk))
        val finalReads = reads.get()
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(finalReads, reads.get())
    }

    @Test
    fun incompleteStoppedTailIsBoundedAndNextRuntimeCanCorrectOlderRowsWithoutRelabeling() = runTest {
        val reads = AtomicInteger()
        val oldRaw = snapshot("process", row("old", uid = 100, upload = 3).put("profileName", "Old"))
        val repo = repository(File(temporary.root, "tail-bound.json"))
        repo.startRuntime(mapOf("old.pkg" to 100), { reads.incrementAndGet(); oldRaw }, {})
        runCurrent()
        repo.endRuntime(null)
        runCurrent()
        advanceTimeBy(6_000)
        runCurrent()
        val boundedReads = reads.get()
        assertTrue(boundedReads in 2..6)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(boundedReads, reads.get())
        assertEquals(ConnectionCaptureStatus.Stopped, repo.state.value.status)
        assertFalse(repo.state.value.connections.single().liveBytesExact)
        repo.startRuntime(mapOf("new.pkg" to 100), {
            snapshot("process", row("old", state = "interrupted", uid = 100, upload = 900).put("profileName", "Old"),
                row("new", uid = 100).put("profileName", "New"))
        }, {})
        runCurrent()
        val old = repo.state.value.connections.single { it.id == "old" }
        assertEquals(900L, old.upload)
        assertTrue(old.liveBytesExact)
        assertEquals("Old", old.profileName)
        assertEquals("old.pkg", old.apps.single().packageName)
        assertEquals(ConnectionCaptureStatus.Running, repo.state.value.status)
    }

    @Test
    fun startingRuntimeCancelsPendingTailAndClearingWhileStoppedAlsoClearsNativeHistory() = runTest {
        val oldReads = AtomicInteger()
        val clears = AtomicInteger()
        val repo = repository(File(temporary.root, "tail-cancel.json"))
        repo.startRuntime(emptyMap(), { oldReads.incrementAndGet(); snapshot("process", row("old")) }, {})
        runCurrent()
        repo.endRuntime(snapshot("process", row("old", state = "interrupted").put("liveBytesExact", false)))
        repo.startRuntime(emptyMap(), { snapshot("process", row("new")) }, { clears.incrementAndGet() })
        runCurrent()
        advanceTimeBy(6_000)
        runCurrent()
        assertEquals(1, oldReads.get())
        repo.endRuntime(snapshot("process", row("new", state = "closed")))
        runCurrent()
        repo.clearHistory()
        runCurrent()
        assertEquals(1, clears.get())
        assertTrue(repo.state.value.connections.isEmpty())
    }

    @Test
    fun uidReuseCapturesOldRowsButNeedsProcessRestartForNewPackageAttribution() = runTest {
        val raw = AtomicReference(snapshot("process"))
        val repo = repository(File(temporary.root, "uid-generation.json"))
        repo.startRuntime(mapOf("old.pkg" to 100), raw::get, {})
        runCurrent()
        val old = row("unpolled-old", state = "closed", uid = 100)
        val preceding = snapshot("process", old)
        raw.set(snapshot("process", old, row("replacement", uid = 100), row("fresh-uid", uid = 101)))
        repo.updatePackages(mapOf("replacement.pkg" to 100, "fresh.pkg" to 101), preceding)
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        val captured = repo.state.value.connections.associateBy { it.id }
        assertEquals("old.pkg", captured.getValue("unpolled-old").apps.single().packageName)
        assertTrue(captured.getValue("replacement").apps.isEmpty())
        assertEquals("fresh.pkg", captured.getValue("fresh-uid").apps.single().packageName)

        repo.endRuntime(snapshot("process", old, row("replacement", state = "closed", uid = 100),
            row("fresh-uid", state = "closed", uid = 101)))
        raw.set(snapshot("process", row("after-reconnect", uid = 100)))
        repo.startRuntime(mapOf("replacement.pkg" to 100), raw::get, {})
        runCurrent()
        assertTrue(repo.state.value.connections.single { it.id == "after-reconnect" }.apps.isEmpty())
        assertEquals("old.pkg", repo.state.value.connections.single { it.id == "unpolled-old" }.apps.single().packageName)
        assertTrue(repo.state.value.connections.single { it.id == "replacement" }.apps.isEmpty())
        repo.close()
        val restarted = repository(File(temporary.root, "new-process.json"))
        restarted.startRuntime(mapOf("replacement.pkg" to 100), {
            snapshot("new-process", row("after-process-restart", uid = 100))
        }, {})
        runCurrent()
        assertEquals("replacement.pkg", restarted.state.value.connections.single().apps.single().packageName)
    }

    @Test
    fun admissionInvalidationPrecedesSharedUidFlowAndCannotBeClearedByCoreReload() = runTest {
        val raw = AtomicReference(snapshot("process"))
        val repo = repository(File(temporary.root, "admission-generation.json"))
        repo.startRuntime(mapOf("first.pkg" to 100), raw::get, {})
        runCurrent()
        repo.invalidateUid(100)
        raw.set(snapshot("process", row("changed-shared-uid", uid = 100)))
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(repo.state.value.connections.single().apps.isEmpty())
        repo.endRuntime(snapshot("process", row("changed-shared-uid", state = "closed", uid = 100)))
        repo.startRuntime(mapOf("first.pkg" to 100, "second.pkg" to 100), {
            snapshot("process", row("new-shared-flow", uid = 100))
        }, {})
        runCurrent()
        assertTrue(repo.state.value.connections.single { it.id == "new-shared-flow" }.apps.isEmpty())
    }

    private fun TestScope.repository(file: File): ConnectionRepository = ConnectionRepository(
        app,
        settings,
        dispatcher = StandardTestDispatcher(testScheduler),
        clock = { now + testScheduler.currentTime },
        packageLabel = { "label:$it" },
        historyFile = file,
        parentScope = backgroundScope,
    ).also(repositories::add)

    private fun row(
        id: String,
        state: String = "active",
        upload: Long = 0,
        download: Long = 0,
        uid: Int = -1,
    ): JSONObject = JSONObject().apply {
        put("id", id)
        put("protocol", "tcp")
        put("destination", "private.example:443")
        put("domain", "private.example")
        put("destinations", JSONArray())
        put("destinationsTruncated", false)
        put("destinationKind", "connection")
        put("startedAt", now - 100)
        put("endedAt", if (state == "active") 0 else now)
        put("state", state)
        put("upload", upload)
        put("download", download)
        put("liveBytesExact", true)
        put("uid", uid)
        put("profileId", 7)
        put("profileName", "")
        put("profileFingerprint", "captured-fingerprint")
        put("outboundTag", "captured-outbound")
        put("route", "proxy")
    }

    private fun snapshot(epoch: String, vararg rows: JSONObject): String = JSONObject().apply {
        put("running", true)
        put("epoch", epoch)
        put("dropped", 0)
        put("connections", JSONArray(rows.toList()))
    }.toString()

    private fun savedUpload(file: File): Long =
        JSONObject(file.readText()).getJSONArray("connections").getJSONObject(0).getLong("upload")
}
