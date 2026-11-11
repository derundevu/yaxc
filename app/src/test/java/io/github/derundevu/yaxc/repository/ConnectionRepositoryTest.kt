package io.github.derundevu.yaxc.repository

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Looper
import io.github.derundevu.yaxc.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.launch
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
        repo.startCapture(emptyMap(), { reads.incrementAndGet(); snapshot("runtime", row("one")) }, {})
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
            repo.startCapture(mapOf("old.pkg" to 100), { snapshot(oldEpoch, row("old", uid = 100).put("profileName", "Old config")) }, {})
            runCurrent()
            repo.endRuntime(snapshot(oldEpoch, row("old", state = "closed", upload = 999, download = 333, uid = 100)))
            repo.startCapture(mapOf("new.pkg" to 100), { snapshot(newEpoch, row("new", upload = 4, uid = 100).put("profileName", "New config")) }, {})
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
            repo.startCapture(emptyMap(), {
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
            repo.startCapture(emptyMap(), { snapshot("new", row("new", upload = 22)) }, {})
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
        repo.startCapture(mapOf("b.pkg" to 100, "a.pkg" to 100), raw::get, {})
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
        repo.startCapture(mapOf("gone.pkg" to 100), { snapshot("first", row("active", uid = 100, upload = 42).put("profileName", "Captured config")) }, {})
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
        repo.startCapture(emptyMap(), raw::get, {})
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
        repo.startCapture(emptyMap(), raw::get, {
            clears.incrementAndGet()
            raw.set(snapshot("epoch", active, generation = 1))
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
        repo.startCapture(emptyMap(), { raw.get() ?: error("private native configuration diagnostic") }, {})
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
            repo.startCapture(emptyMap(), {
                val captured = raw.get()
                if (reads.incrementAndGet() == 2) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
                captured
            }, {
                raw.set(snapshot("epoch", row("active", upload = 100), generation = 1))
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
        repo.startCapture(emptyMap(), raw::get, {})
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
        repo.startCapture(emptyMap(), {
            JSONObject(snapshot("old", row("old", upload = 33))).put("nativeEvicted", 5).toString()
        }, {})
        runCurrent()
        repo.startCapture(emptyMap(), {
            JSONObject(snapshot("new", row("new", upload = 44))).put("nativeRejected", 2).toString()
        }, {})
        runCurrent()
        assertEquals("interrupted", repo.state.value.connections.single { it.id == "old" }.state)
        assertEquals(33L, repo.state.value.connections.single { it.id == "old" }.upload)
        assertEquals("active", repo.state.value.connections.single { it.id == "new" }.state)
        assertEquals(ConnectionHistoryCounters(nativeEvicted = 5, nativeRejected = 2), repo.state.value.counters)
    }

    @Test
    fun closedHistoryExpiresAtTwentyFourHoursEvenWithNoRuntimeOrOpenScreen() = runTest {
        val disk = File(temporary.root, "ttl.json")
        val repo = repository(disk)
        val closed = snapshot("epoch", row("closed", state = "closed"))
        repo.startCapture(emptyMap(), { closed }, {})
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
        repo.startCapture(emptyMap(), { reads.incrementAndGet(); raw.get() }, {})
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
        repo.startCapture(mapOf("old.pkg" to 100), { reads.incrementAndGet(); oldRaw }, {})
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
        repo.startCapture(mapOf("new.pkg" to 100), {
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
        repo.startCapture(emptyMap(), { oldReads.incrementAndGet(); snapshot("process", row("old")) }, {})
        runCurrent()
        repo.endRuntime(snapshot("process", row("old", state = "interrupted").put("liveBytesExact", false)))
        repo.startCapture(emptyMap(), { snapshot("process", row("new")) }, { clears.incrementAndGet() })
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
        repo.startCapture(mapOf("old.pkg" to 100), raw::get, {})
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
        repo.startCapture(mapOf("replacement.pkg" to 100), raw::get, {})
        runCurrent()
        assertTrue(repo.state.value.connections.single { it.id == "after-reconnect" }.apps.isEmpty())
        assertEquals("old.pkg", repo.state.value.connections.single { it.id == "unpolled-old" }.apps.single().packageName)
        assertTrue(repo.state.value.connections.single { it.id == "replacement" }.apps.isEmpty())
        repo.close()
        val restarted = repository(File(temporary.root, "new-process.json"))
        restarted.startCapture(mapOf("replacement.pkg" to 100), {
            snapshot("new-process", row("after-process-restart", uid = 100))
        }, {})
        runCurrent()
        assertEquals("replacement.pkg", restarted.state.value.connections.single().apps.single().packageName)
    }

    @Test
    fun admissionInvalidationPrecedesSharedUidFlowAndCannotBeClearedByCoreReload() = runTest {
        val raw = AtomicReference(snapshot("process"))
        val repo = repository(File(temporary.root, "admission-generation.json"))
        repo.startCapture(mapOf("first.pkg" to 100), raw::get, {})
        runCurrent()
        repo.invalidateUid(100)
        raw.set(snapshot("process", row("changed-shared-uid", uid = 100)))
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(repo.state.value.connections.single().apps.isEmpty())
        repo.endRuntime(snapshot("process", row("changed-shared-uid", state = "closed", uid = 100)))
        repo.startCapture(mapOf("first.pkg" to 100, "second.pkg" to 100), {
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

    @Test
    fun clearResetsPersistedMetricsAndLateFinalAndPackageSnapshotsCannotRestoreThem() = runTest {
        val disk = File(temporary.root, "clear-generation.json")
        val active = row("active", upload = 90)
        val stale = JSONObject(snapshot("epoch", active, row("closed", state = "closed")))
            .put("nativeEvicted", 8).put("nativeRejected", 3).toString()
        val raw = AtomicReference(stale)
        val repo = repository(disk)
        repo.startCapture(emptyMap(), raw::get, { raw.set(snapshot("epoch", active, generation = 1)) })
        runCurrent()
        repo.setPersistence(true)
        runCurrent()
        assertEquals(ConnectionHistoryCounters(nativeEvicted = 8, nativeRejected = 3), repo.state.value.counters)
        repo.clearHistory()
        runCurrent()
        assertEquals(ConnectionHistoryCounters(), repo.state.value.counters)
        repo.updatePackages(emptyMap(), stale)
        runCurrent()
        assertEquals(listOf("active"), repo.state.value.connections.map { it.id })
        assertEquals(ConnectionHistoryCounters(), repo.state.value.counters)
        repo.endRuntime(stale)
        runCurrent()
        assertEquals(listOf("active"), repo.state.value.connections.map { it.id })
        assertEquals(90L, repo.state.value.connections.single().upload)
        assertEquals(ConnectionHistoryCounters(), repo.state.value.counters)
        val saved = JSONObject(disk.readText())
        assertEquals(1, saved.getJSONArray("connections").length())
        assertEquals(1L, saved.getLong("nativeGeneration"))
        assertEquals(0L, saved.getJSONObject("counters").getLong("nativeEvicted"))
        assertEquals(0L, saved.getJSONObject("counters").getLong("nativeRejected"))
        assertFalse(saved.has("dropped"))
    }

    @Test
    fun clearBeforeFirstPollEstablishesNativeFenceBeforeAcceptingLateFinalSnapshot() = runTest {
        val raw = AtomicReference(snapshot("epoch", row("closed", state = "closed"), row("active", upload = 20)))
        val stale = raw.get()
        val repo = repository(File(temporary.root, "first-clear.json"))
        repo.startCapture(emptyMap(), raw::get, {
            raw.set(snapshot("epoch", row("active", upload = 40), generation = 1))
        })
        repo.clearHistory()
        repo.endRuntime(stale)
        runCurrent()
        assertEquals(listOf("active"), repo.state.value.connections.map { it.id })
        assertEquals(40L, repo.state.value.connections.single().upload)
        assertEquals(ConnectionHistoryCounters(), repo.state.value.counters)
    }

    @Test
    fun nativeClearFailureKeepsUncertaintyVisibleAndDoesNotReimportKnownFinishedRowsOrOldCounters() = runTest {
        val full = JSONObject(snapshot("epoch", row("active", upload = 11), row("closed", state = "closed")))
            .put("nativeEvicted", 8).put("nativeRejected", 2).toString()
        val raw = AtomicReference(full)
        val repo = repository(File(temporary.root, "clear-failure.json"))
        repo.startCapture(emptyMap(), raw::get, { error("private native diagnostics") })
        runCurrent()
        repo.clearHistory()
        runCurrent()
        assertNotNull(repo.state.value.error)
        assertFalse(checkNotNull(repo.state.value.error).contains("private"))
        assertEquals(ConnectionHistoryCounters(), repo.state.value.counters)
        raw.set(JSONObject(full).put("nativeEvicted", 9).toString())
        advanceTimeBy(1_000)
        runCurrent()
        assertNotNull(repo.state.value.error)
        assertEquals(listOf("active"), repo.state.value.connections.map { it.id })
        assertEquals(11L, repo.state.value.connections.single().upload)
        assertEquals(ConnectionHistoryCounters(nativeEvicted = 1), repo.state.value.counters)
    }

    @Test
    fun unchangedPollingDoesNotRewriteOptedInHistory() = runTest {
        val disk = File(temporary.root, "unchanged.json")
        val repo = repository(disk)
        repo.startCapture(emptyMap(), { snapshot("epoch", row("active", upload = 7)) }, {})
        runCurrent()
        repo.setPersistence(true)
        runCurrent()
        assertTrue(disk.setLastModified(1_000))
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(1_000L, disk.lastModified())
        assertEquals(7L, savedUpload(disk))
    }

    @Test
    fun optedInReadAcceptsExactEightMiBBoundaryAndRejectsOneExtraBytePrivately() = runTest {
        settings.connectionHistoryOnDisk = true
        val disk = File(temporary.root, "disk-boundary.json")
        val base = JSONObject().put("version", 1).put("dropped", 3)
            .put("connections", JSONArray()).put("padding", "").toString()
        val padding = ConnectionJson.MAX_FILE_BYTES - base.toByteArray(Charsets.UTF_8).size
        disk.writeText(JSONObject(base).put("padding", "x".repeat(padding)).toString())
        assertEquals(ConnectionJson.MAX_FILE_BYTES.toLong(), disk.length())
        val exact = repository(disk)
        runCurrent()
        assertNull(exact.state.value.error)
        assertEquals(ConnectionHistoryCounters(unclassified = 3), exact.state.value.counters)
        assertEquals(3, JSONObject(disk.readText()).getInt("version"))
        exact.close()
        disk.writeBytes(ByteArray(ConnectionJson.MAX_FILE_BYTES + 1) { 'x'.code.toByte() })
        val over = repository(disk)
        runCurrent()
        assertNotNull(over.state.value.error)
        assertTrue(over.state.value.connections.isEmpty())
        assertEquals(ConnectionHistoryCounters(), over.state.value.counters)
        assertFalse(checkNotNull(over.state.value.error).contains("xxxx"))
    }

    @Test
    fun captureOffQuiescesReadsPublicationsAndCheckpointsWithoutErasingHistory() = runTest {
        val disk = File(temporary.root, "capture-off.json")
        val raw = AtomicReference(snapshot("process", row("old", upload = 7)))
        val reads = AtomicInteger()
        val repo = repository(disk)
        val native = repo.startCapture(emptyMap(), { reads.incrementAndGet(); raw.get() }, {})
        var publications = 0
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            repo.state.collect { publications++ }
        }
        runCurrent()
        assertTrue(settings.connectionAnalyticsEnabled)
        repo.setPersistence(true)
        runCurrent()
        raw.set(snapshot("process", row("old", upload = 19)))
        repo.setCaptureEnabled(false)
        runCurrent()
        assertFalse(settings.connectionAnalyticsEnabled)
        assertFalse(repo.state.value.captureEnabled)
        assertTrue(repo.state.value.persistOnDisk)
        assertEquals(ConnectionCaptureStatus.Disabled, repo.state.value.status)
        val cutoff = repo.state.value.connections.single()
        assertEquals("interrupted", cutoff.state)
        assertFalse(cutoff.liveBytesExact)
        assertEquals(19L, cutoff.upload)
        assertEquals(2, reads.get()) // Initial poll plus exactly one quiescent cutoff.
        val published = publications
        val saved = disk.readText()
        assertTrue(disk.setLastModified(1_000))
        raw.set(JSONObject(snapshot("process", row("old", upload = 999), row("disabled-flow")))
            .put("nativeEvicted", 99).toString())
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(2, reads.get())
        assertEquals(published, publications)
        assertEquals(listOf(cutoff), repo.state.value.connections)
        assertEquals(ConnectionHistoryCounters(), repo.state.value.counters)
        assertEquals(saved, disk.readText())
        assertEquals(1_000L, disk.lastModified())
        raw.set(snapshot("process", row("old", state = "interrupted", upload = 999), row("new", upload = 4)))
        repo.setCaptureEnabled(true)
        runCurrent()
        assertTrue(settings.connectionAnalyticsEnabled)
        assertEquals(ConnectionCaptureStatus.Running, repo.state.value.status)
        assertEquals(19L, repo.state.value.connections.single { it.id == "old" }.upload)
        assertEquals(4L, repo.state.value.connections.single { it.id == "new" }.upload)
        assertEquals(listOf(true, false, true), native.toggles)
        assertEquals(2L, native.captureGeneration)
    }

    @Test
    fun persistedOffStartsWithoutReadingAndClearAndDiskChangesNeverResumeCapture() = runTest {
        settings.connectionAnalyticsEnabled = false
        val disk = File(temporary.root, "startup-off.json")
        val reads = AtomicInteger()
        val clears = AtomicInteger()
        val native = CaptureRuntime({ reads.incrementAndGet(); snapshot("process", row("fresh")) }, initialEnabled = false)
        val repo = repository(disk)
        repo.startRuntime(emptyMap(), native::read, { clears.incrementAndGet() }, native::setCapture)
        runCurrent()
        assertEquals(listOf(false), native.toggles)
        assertEquals(ConnectionCaptureStatus.Disabled, repo.state.value.status)
        assertEquals(0, reads.get())
        repo.setPersistence(true)
        repo.clearHistory()
        repo.updatePackages(mapOf("new.pkg" to 100), snapshot("process", row("stale")))
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(0, reads.get())
        assertEquals(1, clears.get())
        assertTrue(repo.state.value.connections.isEmpty())
        assertTrue(repo.state.value.persistOnDisk)
        assertFalse(repo.state.value.captureEnabled)
        repo.setPersistence(false)
        repo.endRuntime(snapshot("process", row("stale-final")))
        runCurrent()
        assertFalse(disk.exists())
        assertTrue(repo.state.value.connections.isEmpty())
        val resumed = CaptureRuntime({ reads.incrementAndGet(); snapshot("process", row("after-reload"), generation = 1) }, initialEnabled = false)
        repo.startRuntime(emptyMap(), resumed::read, {}, resumed::setCapture)
        runCurrent()
        assertEquals(0, reads.get())
        repo.setCaptureEnabled(true)
        runCurrent()
        assertEquals(1, reads.get())
        assertEquals("after-reload", repo.state.value.connections.single().id)
        assertFalse(settings.connectionHistoryOnDisk)
    }

    @Test
    fun nativeToggleFailureDoesNotPersistOrClaimDisabledAndCollectionContinues() = runTest {
        val raw = AtomicReference(snapshot("process", row("old", upload = 5)))
        val repo = repository(File(temporary.root, "toggle-failure.json"))
        val native = repo.startCapture(emptyMap(), raw::get, {})
        runCurrent()
        native.diagnostic = "private native configuration"
        repo.setCaptureEnabled(false)
        runCurrent()
        assertTrue(settings.connectionAnalyticsEnabled)
        assertTrue(repo.state.value.captureEnabled)
        assertEquals(ConnectionCaptureStatus.Error, repo.state.value.status)
        assertFalse(checkNotNull(repo.state.value.error).contains("private"))
        raw.set(snapshot("process", row("old", upload = 15)))
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(15L, repo.state.value.connections.single().upload)
        native.diagnostic = ""
        repo.setCaptureEnabled(false)
        runCurrent()
        assertFalse(repo.state.value.captureEnabled)
        assertFalse(settings.connectionAnalyticsEnabled)
        assertNull(repo.state.value.error)
    }

    @Test
    fun externalCapturePreferencesApplyHotAndFailedChangesRestoreActualPreference() = runTest {
        val repo = repository(File(temporary.root, "external-capture.json"))
        val native = repo.startCapture(emptyMap(), { snapshot("process", row("old")) }, {})
        runCurrent()
        settings.connectionAnalyticsEnabled = false
        shadowOf(Looper.getMainLooper()).idle()
        runCurrent()
        assertFalse(repo.state.value.captureEnabled)
        assertEquals(ConnectionCaptureStatus.Disabled, repo.state.value.status)
        native.diagnostic = "refused"
        settings.connectionAnalyticsEnabled = true
        shadowOf(Looper.getMainLooper()).idle()
        runCurrent()
        assertFalse(repo.state.value.captureEnabled)
        assertFalse(settings.connectionAnalyticsEnabled)
        assertEquals(ConnectionCaptureStatus.Error, repo.state.value.status)
        assertNotNull(repo.state.value.error)
    }

    @Test
    fun failedPreferenceCommitKeepsActualNativeStateAndReportsPersistenceError() = runTest {
        val delegate = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val failingPreferences = object : SharedPreferences by delegate {
            override fun edit(): SharedPreferences.Editor {
                val editor = delegate.edit()
                return object : SharedPreferences.Editor by editor {
                    override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
                        editor.putBoolean(key, value)
                        return this
                    }
                    override fun commit() = false
                }
            }
        }
        val failingSettings = Settings(object : ContextWrapper(app) {
            override fun getSharedPreferences(name: String?, mode: Int) = failingPreferences
        })
        val repo = ConnectionRepository(
            app, failingSettings, StandardTestDispatcher(testScheduler), { now },
            packageLabel = { it }, historyFile = File(temporary.root, "failed-preference.json"),
            parentScope = backgroundScope,
        ).also(repositories::add)
        val native = repo.startCapture(emptyMap(), { snapshot("process", row("old")) }, {})
        runCurrent()
        repo.setCaptureEnabled(false)
        runCurrent()
        assertFalse(native.enabled)
        assertFalse(repo.state.value.captureEnabled)
        assertEquals(ConnectionCaptureStatus.Disabled, repo.state.value.status)
        assertTrue(failingSettings.connectionAnalyticsEnabled)
        assertNotNull(repo.state.value.error)
        assertEquals("interrupted", repo.state.value.connections.single().state)
        repo.startNativeRuntime(native::setCapture) {
            assertFalse(native.enabled) // A stale persisted On cannot overwrite completed Off.
        }
    }

    @Test
    fun oldPackageFinalAndNativeFramesCannotCrossCaptureGenerationEvenAfterResume() = runTest {
        val stale = JSONObject(snapshot("process", row("old", upload = 1), row("unpolled")))
            .put("nativeEvicted", 40).toString()
        val raw = AtomicReference(snapshot("process", row("old", upload = 5)))
        val native = CaptureRuntime(raw::get)
        val frame = AtomicReference<String?>(null)
        val repo = repository(File(temporary.root, "capture-generation.json"))
        repo.startRuntime(emptyMap(), { frame.get() ?: native.read() }, {}, native::setCapture)
        runCurrent()
        repo.setCaptureEnabled(false)
        runCurrent()
        repo.updatePackages(emptyMap(), stale)
        repo.setCaptureEnabled(true)
        frame.set(stale) // A delayed native/cache frame, not just an old actor poll.
        runCurrent()
        assertEquals(listOf("old"), repo.state.value.connections.map { it.id })
        assertEquals(5L, repo.state.value.connections.single().upload)
        assertEquals(ConnectionHistoryCounters(), repo.state.value.counters)
        repo.updatePackages(emptyMap(), stale)
        repo.endRuntime(stale)
        runCurrent()
        assertEquals(listOf("old"), repo.state.value.connections.map { it.id })
        assertEquals(5L, repo.state.value.connections.single().upload)
        assertEquals(ConnectionHistoryCounters(), repo.state.value.counters)
    }

    @Test
    fun delayedReadAtDisableCannotPublishItsPreCutoffSnapshotOrStartAnotherReadOff() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val reads = AtomicInteger()
        val raw = AtomicReference(snapshot("process", row("old", upload = 5)))
        val native = CaptureRuntime(raw::get)
        val repo = ConnectionRepository(
            app, settings, Dispatchers.IO, historyFile = File(temporary.root, "delayed-disable.json"),
            packageLabel = { it },
        ).also(repositories::add)
        try {
            repo.startRuntime(emptyMap(), {
                val captured = native.read()
                if (reads.incrementAndGet() == 2) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
                captured
            }, {}, native::setCapture)
            withTimeout(5_000) { repo.state.first { it.connections.size == 1 } }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            repo.setCaptureEnabled(false)
            raw.set(snapshot("process", row("old", upload = 23)))
            release.countDown()
            val disabled = withTimeout(5_000) { repo.state.first { !it.captureEnabled } }
            assertEquals(23L, disabled.connections.single().upload)
            assertEquals("interrupted", disabled.connections.single().state)
            assertFalse(disabled.connections.single().liveBytesExact)
            assertEquals(3, reads.get())
        } finally {
            release.countDown()
            repo.close()
        }
    }

    @Test
    fun rapidTogglesAndStoppedTailCannotResumeOldCaptureOrOverwriteLatestPreference() = runTest {
        val reads = AtomicInteger()
        val raw = AtomicReference(snapshot("process", row("old", upload = 5)))
        val repo = repository(File(temporary.root, "rapid-capture.json"))
        val native = repo.startCapture(emptyMap(), { reads.incrementAndGet(); raw.get() }, {})
        runCurrent()
        repo.endRuntime(snapshot("process", row("old", state = "interrupted").put("liveBytesExact", false)))
        runCurrent()
        repo.setCaptureEnabled(false)
        runCurrent()
        val cutoffReads = reads.get()
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(cutoffReads, reads.get())
        repo.setCaptureEnabled(true)
        repo.setCaptureEnabled(false)
        repo.setCaptureEnabled(true)
        repo.setCaptureEnabled(false)
        runCurrent()
        shadowOf(Looper.getMainLooper()).idle()
        runCurrent()
        assertFalse(native.enabled)
        assertFalse(settings.connectionAnalyticsEnabled)
        assertFalse(repo.state.value.captureEnabled)
        assertEquals(ConnectionCaptureStatus.Disabled, repo.state.value.status)
        assertEquals(cutoffReads, reads.get())
        repo.unsupportedRuntime()
        runCurrent()
        assertEquals(ConnectionCaptureStatus.Disabled, repo.state.value.status)
        repo.setCaptureEnabled(true)
        runCurrent()
        assertEquals(ConnectionCaptureStatus.Unsupported, repo.state.value.status)
    }

    @Test
    fun disabledCaptureStillExpiresFinishedHistoryAtTwentyFourHoursWithoutNativePolling() = runTest {
        val disk = File(temporary.root, "off-expiry.json")
        val reads = AtomicInteger()
        val repo = repository(disk)
        repo.startCapture(emptyMap(), { reads.incrementAndGet(); snapshot("process", row("old", upload = 5)) }, {})
        runCurrent()
        repo.setPersistence(true)
        repo.setCaptureEnabled(false)
        runCurrent()
        val cutoffReads = reads.get()
        val ttl = 24 * 60 * 60 * 1000L
        advanceTimeBy(ttl - 1)
        runCurrent()
        assertEquals(1, repo.state.value.connections.size)
        assertEquals(cutoffReads, reads.get())
        advanceTimeBy(1)
        runCurrent()
        assertTrue(repo.state.value.connections.isEmpty())
        assertEquals(0, JSONObject(disk.readText()).getJSONArray("connections").length())
        assertEquals(cutoffReads, reads.get())
        assertEquals(ConnectionCaptureStatus.Disabled, repo.state.value.status)
        assertFalse(repo.state.value.captureEnabled)
    }

    @Test
    fun failedStartupSynchronizationNeverStartsReaderAndSameDesiredSettingCanRecover() = runTest {
        settings.connectionAnalyticsEnabled = false
        val reads = AtomicInteger()
        val native = CaptureRuntime({ reads.incrementAndGet(); snapshot("process", row("fresh")) })
        native.diagnostic = "startup synchronization refused"
        val repo = repository(File(temporary.root, "startup-sync-error.json"))
        repo.startRuntime(emptyMap(), native::read, {}, native::setCapture)
        runCurrent()
        assertEquals(ConnectionCaptureStatus.Error, repo.state.value.status)
        assertNotNull(repo.state.value.error)
        assertEquals(0, reads.get())
        native.diagnostic = ""
        repo.setCaptureEnabled(false)
        runCurrent()
        assertEquals(ConnectionCaptureStatus.Disabled, repo.state.value.status)
        assertFalse(native.enabled)
        assertEquals(0, reads.get())
        repo.setCaptureEnabled(true)
        runCurrent()
        assertEquals(ConnectionCaptureStatus.Running, repo.state.value.status)
        assertEquals(1, reads.get())
        assertEquals("fresh", repo.state.value.connections.single().id)
    }

    @Test
    fun failedCutoffReadStillFreezesLastKnownRowsAndFencesStaleFramesOnResume() = runTest {
        val raw = AtomicReference<String?>(snapshot("process", row("old", upload = 5)))
        val native = CaptureRuntime({ checkNotNull(raw.get()) })
        val staleFrame = AtomicReference<String?>(null)
        val repo = repository(File(temporary.root, "failed-cutoff.json"))
        repo.startRuntime(emptyMap(), { staleFrame.get() ?: native.read() }, {}, native::setCapture)
        runCurrent()
        raw.set(null)
        repo.setCaptureEnabled(false)
        runCurrent()
        assertFalse(repo.state.value.captureEnabled)
        assertFalse(settings.connectionAnalyticsEnabled)
        assertEquals(ConnectionCaptureStatus.Error, repo.state.value.status)
        assertNotNull(repo.state.value.error)
        assertEquals(5L, repo.state.value.connections.single().upload)
        assertEquals("interrupted", repo.state.value.connections.single().state)
        staleFrame.set(snapshot("process", row("old", upload = 999), row("stale")))
        repo.setCaptureEnabled(true)
        runCurrent()
        assertEquals(listOf("old"), repo.state.value.connections.map { it.id })
        assertEquals(5L, repo.state.value.connections.single().upload)
        staleFrame.set(null)
        raw.set(snapshot("process", row("old", state = "interrupted", upload = 999), row("fresh")))
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(setOf("old", "fresh"), repo.state.value.connections.map { it.id }.toSet())
        assertEquals(5L, repo.state.value.connections.single { it.id == "old" }.upload)
        assertFalse(repo.state.value.connections.single { it.id == "old" }.liveBytesExact)
    }

    @Test
    fun completedOffCannotBeOverwrittenByDelayedNativeStartupWithAnOlderPreference() = runBlocking {
        val readyToStart = CountDownLatch(1)
        val releaseStart = CountDownLatch(1)
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val reads = AtomicInteger()
        val capturedAtStartup = AtomicInteger()
        val native = CaptureRuntime({ reads.incrementAndGet(); snapshot("process") })
        val repo = ConnectionRepository(
            app, settings, Dispatchers.IO, packageLabel = { it },
            historyFile = File(temporary.root, "delayed-native-start.json"),
        ).also(repositories::add)
        try {
            val startup = executor.submit {
                // The service can be suspended after an older caller observed On.
                assertTrue(settings.connectionAnalyticsEnabled)
                readyToStart.countDown()
                check(releaseStart.await(5, TimeUnit.SECONDS))
                repo.startNativeRuntime(native::setCapture) {
                    // A newly admitted native connection must see the completed Off.
                    if (native.enabled) capturedAtStartup.incrementAndGet()
                    repo.startRuntime(emptyMap(), native::read, {}, native::setCapture)
                }
            }
            assertTrue(readyToStart.await(5, TimeUnit.SECONDS))
            repo.setCaptureEnabled(false)
            withTimeout(5_000) { repo.state.first { !it.captureEnabled } }
            assertFalse(settings.connectionAnalyticsEnabled)
            releaseStart.countDown()
            startup.get(5, TimeUnit.SECONDS)
            assertFalse(native.enabled)
            assertEquals(0, capturedAtStartup.get())
            assertEquals(0, reads.get())
        } finally {
            releaseStart.countDown()
            executor.shutdownNow()
            repo.close()
        }
    }

    @Test
    fun offBeforePendingRuntimeStartControlsNativeBeforePublishingDisabled() = runTest {
        val native = CaptureRuntime({ snapshot("process") })
        val repo = repository(File(temporary.root, "pending-native-registration.json"))
        runCurrent()
        var observationsAfterCompletedOff = 0
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            repo.state.collect { state ->
                // Admission between actor commands cannot observe On after Off has completed.
                if (!state.captureEnabled && native.enabled) observationsAfterCompletedOff++
            }
        }
        repo.setCaptureEnabled(false)
        repo.startRuntime(emptyMap(), native::read, {}, native::setCapture)
        runCurrent()
        assertFalse(native.enabled)
        assertFalse(settings.connectionAnalyticsEnabled)
        assertEquals(ConnectionCaptureStatus.Disabled, repo.state.value.status)
        assertEquals(0, observationsAfterCompletedOff)
        assertTrue(repo.state.value.connections.isEmpty())
    }

    private fun snapshot(epoch: String, vararg rows: JSONObject, generation: Long = 0): String = JSONObject().apply {
        put("running", true)
        put("epoch", epoch)
        put("generation", generation)
        put("enabled", true)
        put("captureGeneration", 0)
        put("nativeEvicted", 0)
        put("nativeRejected", 0)
        put("connections", JSONArray(rows.toList()))
    }.toString()

    /** Stateful native boundary; tests never load JNI or silently default the required callback. */
    private inner class CaptureRuntime(
        private val source: () -> String,
        initialEnabled: Boolean = true,
    ) {
        var enabled = initialEnabled
            private set
        var captureGeneration = 0L
            private set
        var diagnostic = ""
        val toggles = mutableListOf<Boolean>()

        fun setCapture(enabled: Boolean): String {
            toggles += enabled
            if (diagnostic.isNotEmpty()) return diagnostic
            if (this.enabled != enabled) {
                this.enabled = enabled
                captureGeneration++
            }
            return ""
        }

        fun read(): String = JSONObject(source()).apply {
            put("enabled", enabled)
            put("captureGeneration", captureGeneration)
            if (!enabled) {
                val rows = getJSONArray("connections")
                for (index in 0 until rows.length()) {
                    val row = rows.getJSONObject(index)
                    if (row.getString("state") == "active") {
                        row.put("state", "interrupted").put("endedAt", now).put("liveBytesExact", false)
                    }
                }
            }
        }.toString()
    }

    private fun ConnectionRepository.startCapture(
        packages: Map<String, Int>,
        reader: () -> String,
        clear: () -> Unit,
    ): CaptureRuntime = CaptureRuntime(reader).also { native ->
        startRuntime(packages, native::read, clear, native::setCapture)
    }

    private fun savedUpload(file: File): Long =
        JSONObject(file.readText()).getJSONArray("connections").getJSONObject(0).getLong("upload")
}
