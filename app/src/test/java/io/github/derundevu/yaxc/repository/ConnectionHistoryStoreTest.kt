package io.github.derundevu.yaxc.repository

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ConnectionHistoryStoreTest {
    private val now = 1_800_000_000_000L
    private val ttl = 24 * 60 * 60 * 1000L

    @Test
    fun nativeSchemaDecodesAssociationDestinationsDeferredBytesAndUnknownIdentity() {
        val snapshot = ConnectionJson.snapshot("""{
          "running":true,"enabled":true,"captureGeneration":0,"epoch":"z-old","generation":0,"nativeEvicted":3,"nativeRejected":2,"connections":[{
            "id":"flow-1","protocol":"udp","destination":"192.0.2.1:443",
            "domain":"private.example","destinations":["192.0.2.1:443","192.0.2.2:443"],
            "destinationsTruncated":true,"destinationKind":"association",
            "startedAt":100,"endedAt":0,"state":"active","upload":12345678901,
            "download":9876543210,"liveBytesExact":false,"uid":-1,
            "profileId":42,"profileName":"Captured","profileFingerprint":"abcdef",
            "outboundTag":"lr-42","route":"proxy","config":"must-not-be-kept"
          }]}
        """)
        val store = ConnectionHistoryStore()
        store.ingest(snapshot, emptyMap(), now)
        val row = store.records.single()
        assertEquals("association", row.destinationKind)
        assertEquals(listOf("192.0.2.1:443", "192.0.2.2:443"), row.destinations)
        assertTrue(row.destinationsTruncated)
        assertFalse(row.liveBytesExact)
        assertEquals(12_345_678_901L, row.upload)
        assertEquals(9_876_543_210L, row.download)
        assertEquals(42L, row.profileId)
        assertEquals("Captured", row.profileName)
        assertEquals("abcdef", row.profileFingerprint)
        assertEquals("private.example", row.domain)
        assertTrue(row.apps.isEmpty())
        assertEquals(ConnectionHistoryCounters(nativeEvicted = 3, nativeRejected = 2), store.counters)
        assertFalse(store.encode().contains("must-not-be-kept"))
    }

    @Test
    fun transitionsPreserveFinalStateCountersAndRejectAnotherEpochWithoutMutation() {
        val store = ConnectionHistoryStore()
        val active = row("one", upload = 10, download = 20)
        store.ingest(snapshot(active), emptyMap(), now)
        store.ingest(snapshot(active.copy(state = "error", endedAt = now, upload = 50, download = 60)), emptyMap(), now)
        store.ingest(snapshot(active.copy(upload = 1, download = 2)), emptyMap(), now)
        assertEquals("error", store.records.single().state)
        assertEquals(now, store.records.single().endedAt)
        assertEquals(50L, store.records.single().upload)
        assertEquals(60L, store.records.single().download)
        try {
            store.ingest(snapshot(row("intruder"), epoch = "different"), emptyMap(), now)
            throw AssertionError("A different native epoch must be rejected")
        } catch (_: IllegalArgumentException) {
            assertEquals(listOf("one"), store.records.map { it.id })
        }
        store.beginRuntime(now + 1)
        store.ingest(snapshot(row("new"), epoch = "different", nativeEvicted = 7), emptyMap(), now + 1)
        assertEquals(setOf("one", "new"), store.records.map { it.id }.toSet())
        assertEquals(7L, store.counters.nativeEvicted)
    }

    @Test
    fun sharedUidNamesAndProfilesAreImmutableButNewRowsCaptureLatestRuntimeIdentity() {
        val store = ConnectionHistoryStore()
        val firstApps = mapOf(100 to listOf(ConnectionApp("a.pkg", "A"), ConnectionApp("b.pkg", "B")))
        val first = row("one").copy(uid = 100, profileId = 7, profileName = "Old config", profileFingerprint = "old-fingerprint")
        store.ingest(snapshot(first, row("unknown")), firstApps, now)
        val replacementApps = mapOf(100 to listOf(ConnectionApp("replacement.pkg", "Replacement")))
        store.ingest(
            snapshot(first.copy(uid = 200, profileId = 9, profileName = "Relabeled", profileFingerprint = "new"),
                first.copy(id = "two", profileName = "Renamed config")),
            replacementApps,
            now,
        )
        val old = store.records.single { it.id == "one" }
        assertEquals(firstApps.getValue(100), old.apps)
        assertEquals(100, old.uid)
        assertEquals(7L, old.profileId)
        assertEquals("Old config", old.profileName)
        assertEquals("old-fingerprint", old.profileFingerprint)
        val new = store.records.single { it.id == "two" }
        assertEquals(replacementApps.getValue(100), new.apps)
        assertEquals("Renamed config", new.profileName)
        assertTrue(store.records.single { it.id == "unknown" }.apps.isEmpty())
        store.beginRuntime(now + 1)
        store.ingest(snapshot(row("three").copy(uid = 100), epoch = "next"), replacementApps, now + 1)
        assertEquals(firstApps.getValue(100), store.records.single { it.id == "one" }.apps)
    }

    @Test
    fun delayedRouteCapturesItsActualProfileNameThenKeepsThatIdentity() {
        val store = ConnectionHistoryStore()
        val opening = row("pending").copy(profileId = 0, profileName = "", profileFingerprint = "", outboundTag = "", route = "unknown")
        store.ingest(snapshot(opening), emptyMap(), now)
        val routed = opening.copy(profileId = 42, profileName = "Actual member", profileFingerprint = "actual-version", outboundTag = "member-42", route = "proxy")
        store.ingest(snapshot(routed), emptyMap(), now + 1)
        assertEquals(42L, store.records.single().profileId)
        assertEquals("Actual member", store.records.single().profileName)
        store.ingest(snapshot(routed.copy(profileName = "Renamed later")), emptyMap(), now + 2)
        assertEquals("Actual member", store.records.single().profileName)
    }

    @Test
    fun saturatedActiveRowsAreBoundedWithoutPollInflationAndClosedRowsLosePriority() {
        val store = ConnectionHistoryStore(maxRecords = 2)
        val active = listOf(row("a"), row("b").copy(startedAt = now - 1))
        store.ingest(snapshot(*active.toTypedArray()), emptyMap(), now)
        val overflow = snapshot(*(active + row("c") + row("closed").copy(state = "closed", endedAt = now)).toTypedArray(), nativeEvicted = 4)
        store.ingest(overflow, emptyMap(), now)
        assertEquals(setOf("a", "b"), store.records.map { it.id }.toSet())
        assertEquals(ConnectionHistoryCounters(nativeEvicted = 4, historyRejected = 2), store.counters)
        repeat(3) { store.ingest(overflow, emptyMap(), now) }
        assertEquals(ConnectionHistoryCounters(nativeEvicted = 4, historyRejected = 2), store.counters)
        store.ingest(snapshot(active[0].copy(upload = 900), active[1], row("c")), emptyMap(), now)
        assertEquals(900L, store.records.single { it.id == "a" }.upload)
        assertEquals(2, store.records.size)
    }

    @Test
    fun encodedByteCapRejectsOversizedRowsAndRetainsUsefulSmallerRows() {
        val store = ConnectionHistoryStore(maxBytes = 1_024)
        val oversized = row("large").copy(destination = "sensitive".repeat(400))
        val useful = row("small")
        store.ingest(snapshot(oversized, useful), emptyMap(), now)
        assertEquals(listOf("small"), store.records.map { it.id })
        assertEquals(1L, store.counters.historyRejected)
        assertTrue(store.encode().toByteArray(Charsets.UTF_8).size <= 1_024)
        store.ingest(snapshot(oversized, useful), emptyMap(), now)
        assertEquals(1L, store.counters.historyRejected)
    }

    @Test
    fun closedTtlExpiresAtBoundaryButNeverExpiresAnActiveAssociation() {
        val store = ConnectionHistoryStore()
        val old = row("expired").copy(state = "closed", startedAt = now - ttl - 100, endedAt = now - ttl)
        val recent = old.copy(id = "recent", endedAt = now - ttl + 1)
        val active = row("active").copy(startedAt = now - ttl * 10)
        store.ingest(snapshot(old, recent, active), emptyMap(), now)
        assertEquals(setOf("recent", "active"), store.records.map { it.id }.toSet())
        store.prune(now + 1)
        assertEquals(listOf("active"), store.records.map { it.id })
        assertEquals(ConnectionHistoryCounters(), store.counters)
    }

    @Test
    fun clearingOnlyClosedRowsPreservesActiveBytesAndResetsEveryHistoryMetric() {
        val store = ConnectionHistoryStore()
        val active = row("active", upload = 45, download = 67)
        store.ingest(snapshot(active, row("closed").copy(state = "blocked", endedAt = now), nativeEvicted = 8, nativeRejected = 3), emptyMap(), now)
        store.clearClosed()
        assertEquals(listOf(active), store.records)
        assertEquals(ConnectionHistoryCounters(), store.counters)
        store.ingest(snapshot(active, row("closed").copy(state = "blocked", endedAt = now), nativeEvicted = 8, nativeRejected = 3), emptyMap(), now)
        assertEquals(listOf(active), store.records)
        assertEquals(ConnectionHistoryCounters(), store.counters)
    }

    @Test
    fun crashRestoreInterruptsActiveAndPersistsHistoricalAppAndConfigIdentity() {
        val store = ConnectionHistoryStore()
        val apps = mapOf(100 to listOf(ConnectionApp("gone.pkg", "Uninstalled app")))
        val original = row("active", upload = 99, download = 88).copy(uid = 100, profileId = 17, profileName = "Deleted profile")
        store.ingest(snapshot(original, row("closed").copy(state = "closed", endedAt = now - 5), nativeEvicted = 2), apps, now)
        val saved = store.encode()
        val restored = ConnectionHistoryStore()
        restored.restore(saved, now + 5)
        val interrupted = restored.records.single { it.id == "active" }
        assertEquals("interrupted", interrupted.state)
        assertEquals(now + 5, interrupted.endedAt)
        assertEquals(99L, interrupted.upload)
        assertEquals(apps.getValue(100), interrupted.apps)
        assertEquals("Deleted profile", interrupted.profileName)
        assertEquals(2L, restored.counters.nativeEvicted)
        assertEquals("closed", restored.records.single { it.id == "closed" }.state)
        assertEquals(3, JSONObject(restored.encode()).getInt("version"))
        restored.clearClosed()
        assertTrue(restored.records.isEmpty())
    }

    @Test
    fun productionDefaultsBoundBothFiveThousandRowsAndEightMiBOfEncodedHistory() {
        val countBound = ConnectionHistoryStore()
        val rows = List(5_001) { index -> row("row-$index").copy(startedAt = now - index) }
        countBound.ingest(ConnectionSnapshot(true, "count", 0, 0, rows), emptyMap(), now)
        assertEquals(5_000, countBound.records.size)
        assertEquals(1L, countBound.counters.historyRejected)
        val byteBound = ConnectionHistoryStore()
        val largerRows = rows.take(5_000).map { it.copy(destination = "d".repeat(2_000)) }
        byteBound.ingest(ConnectionSnapshot(true, "bytes", 0, 0, largerRows), emptyMap(), now)
        assertTrue(byteBound.records.size < 5_000)
        assertTrue(byteBound.counters.historyRejected > 0)
        assertTrue(byteBound.encode().toByteArray(Charsets.UTF_8).size <= 8 * 1024 * 1024)
    }

    @Test
    fun processEpochMetricsDoNotDoubleCountAcrossReloadRestoreAndClear() {
        val store = ConnectionHistoryStore()
        store.ingest(snapshot(row("one"), epoch = "process", nativeEvicted = 5, nativeRejected = 2), emptyMap(), now)
        store.beginRuntime(now + 1)
        store.ingest(snapshot(row("two"), epoch = "process", nativeEvicted = 7, nativeRejected = 3), emptyMap(), now + 1)
        assertEquals(ConnectionHistoryCounters(nativeEvicted = 7, nativeRejected = 3), store.counters)
        val restored = ConnectionHistoryStore()
        restored.restore(store.encode(), now + 2)
        restored.beginRuntime(now + 2)
        restored.ingest(snapshot(row("three"), epoch = "process", nativeEvicted = 9, nativeRejected = 4), emptyMap(), now + 2)
        assertEquals(ConnectionHistoryCounters(nativeEvicted = 9, nativeRejected = 4), restored.counters)
        restored.clearClosed()
        restored.nativeHistoryCleared()
        val stale = snapshot(row("erased").copy(state = "closed", endedAt = now), epoch = "process", nativeEvicted = 50)
        assertFalse(restored.ingest(stale, emptyMap(), now + 2))
        restored.ingest(snapshot(row("three"), epoch = "process", generation = 1), emptyMap(), now + 2)
        assertEquals(ConnectionHistoryCounters(), restored.counters)
        restored.ingest(snapshot(row("three"), epoch = "process", generation = 1, nativeEvicted = 2, nativeRejected = 1), emptyMap(), now + 2)
        assertEquals(ConnectionHistoryCounters(nativeEvicted = 2, nativeRejected = 1), restored.counters)
        assertFalse(restored.records.any { it.id == "erased" })
    }

    @Test
    fun finishedRotationUsesCompletionOrderAndNeverReimportsStillPublishedVictims() {
        val store = ConnectionHistoryStore(maxRecords = 2)
        val longRunning = row("long").copy(startedAt = now - 100_000, state = "closed", endedAt = now)
        val earlierCompletion = row("short").copy(startedAt = now - 5, state = "closed", endedAt = now - 1)
        store.ingest(snapshot(longRunning, earlierCompletion), emptyMap(), now)
        val newest = row("new").copy(state = "closed", endedAt = now + 1)
        val full = snapshot(longRunning, earlierCompletion, newest)
        store.ingest(full, emptyMap(), now + 1)
        assertEquals(setOf("long", "new"), store.records.map { it.id }.toSet())
        assertEquals(ConnectionHistoryCounters(historyEvicted = 1), store.counters)
        repeat(3) { store.ingest(full, emptyMap(), now + 1) }
        assertEquals(setOf("long", "new"), store.records.map { it.id }.toSet())
        assertEquals(ConnectionHistoryCounters(historyEvicted = 1), store.counters)
    }

    @Test
    fun equalCompletionTimesHaveStableTiesIndependentOfSnapshotOrderAndRestore() {
        val store = ConnectionHistoryStore(maxRecords = 2)
        val a = row("a").copy(state = "closed", endedAt = now, startedAt = now - 1)
        val b = a.copy(id = "b", startedAt = now - 10_000)
        val c = a.copy(id = "c")
        store.ingest(snapshot(b, a), emptyMap(), now)
        val restored = ConnectionHistoryStore(maxRecords = 2)
        restored.restore(store.encode(), now)
        for (history in listOf(store, restored)) {
            history.ingest(snapshot(c, b, a), emptyMap(), now)
            assertEquals(setOf("b", "c"), history.records.map { it.id }.toSet())
            history.ingest(snapshot(a, b, c), emptyMap(), now)
            assertEquals(setOf("b", "c"), history.records.map { it.id }.toSet())
        }
    }

    @Test
    fun byteBoundRotatesFinishedFIFOWhilePreservingLongRunningRecentCompletion() {
        val store = ConnectionHistoryStore(maxBytes = 2_048)
        val long = row("long").copy(destination = "d".repeat(200), startedAt = now - 100_000, state = "closed", endedAt = now)
        val old = long.copy(id = "old", startedAt = now - 1, endedAt = now - 1)
        val newest = long.copy(id = "new", endedAt = now + 1)
        store.ingest(snapshot(long, old), emptyMap(), now)
        store.ingest(snapshot(long, old, newest), emptyMap(), now + 1)
        assertEquals(setOf("long", "new"), store.records.map { it.id }.toSet())
        assertEquals(1L, store.counters.historyEvicted)
        assertEquals(0L, store.counters.historyRejected)
        assertTrue(store.encode().toByteArray(Charsets.UTF_8).size <= 2_048)
    }

    @Test
    fun nativeEvictionAndRejectionAreIndependentOfLocalRotationAndCaptureRefusal() {
        val store = ConnectionHistoryStore(maxRecords = 2)
        val active = row("active")
        val closed = row("closed").copy(state = "closed", endedAt = now)
        store.ingest(snapshot(active, closed), emptyMap(), now)
        store.ingest(snapshot(active, closed, row("new"), row("refused"), nativeEvicted = 11, nativeRejected = 7), emptyMap(), now)
        assertEquals(setOf("active", "new"), store.records.map { it.id }.toSet())
        assertEquals(ConnectionHistoryCounters(11, 7, 1, 1), store.counters)
        val retained = ConnectionHistoryStore()
        retained.ingest(snapshot(active, closed, nativeEvicted = 11), emptyMap(), now)
        assertEquals(2, retained.records.size)
        assertEquals(ConnectionHistoryCounters(nativeEvicted = 11), retained.counters)
    }

    @Test
    fun legacyAggregateRestoresOnlyAsUnclassifiedAndNewExportHasNoDroppedAlias() {
        val legacy = JSONObject().put("version", 1).put("nativeEpoch", "epoch")
            .put("nativeDropped", 3).put("dropped", 9).put("connections", org.json.JSONArray())
        val store = ConnectionHistoryStore()
        store.restore(legacy.toString(), now)
        assertEquals(ConnectionHistoryCounters(unclassified = 9), store.counters)
        store.ingest(snapshot(nativeEvicted = 20, nativeRejected = 4), emptyMap(), now)
        assertEquals(ConnectionHistoryCounters(unclassified = 9), store.counters)
        store.ingest(snapshot(nativeEvicted = 21, nativeRejected = 5), emptyMap(), now)
        assertEquals(ConnectionHistoryCounters(nativeEvicted = 1, nativeRejected = 1, unclassified = 9), store.counters)
        val exported = JSONObject(store.encode())
        assertEquals(3, exported.getInt("version"))
        assertFalse(exported.has("dropped"))
        assertFalse(exported.has("nativeDropped"))
        assertEquals(9L, exported.getJSONObject("counters").getLong("unclassified"))
        store.clearClosed()
        assertEquals(ConnectionHistoryCounters(), store.counters)
    }

    @Test
    fun unchangedSnapshotsReusePublishedRecordsAndNumericUpdatesKeepExactByteAccounting() {
        val store = ConnectionHistoryStore(maxBytes = 2_048)
        val active = row("active", upload = 9).copy(destination = "é".repeat(150))
        store.ingest(snapshot(active), emptyMap(), now)
        val records = store.records
        val revision = store.revision
        store.ingest(snapshot(active.copy()), emptyMap(), now)
        assertSame(records, store.records)
        assertEquals(revision, store.revision)
        val changed = active.copy(upload = Long.MAX_VALUE, download = Long.MAX_VALUE, liveBytesExact = false)
        store.ingest(snapshot(changed), emptyMap(), now)
        assertEquals(changed, store.records.single())
        assertTrue(store.encode().toByteArray(Charsets.UTF_8).size <= 2_048)
        assertEquals(ConnectionHistoryCounters(), store.counters)
    }

    @Test
    fun clearFenceAndZeroMetricsSurvivePersistenceAndRejectLateFullSnapshots() {
        val store = ConnectionHistoryStore()
        val active = row("active", upload = 80)
        val terminal = row("finished").copy(state = "closed", endedAt = now)
        val stale = snapshot(active, terminal, nativeEvicted = 12, nativeRejected = 3)
        store.ingest(stale, emptyMap(), now)
        store.clearClosed()
        store.nativeHistoryCleared()
        val restored = ConnectionHistoryStore()
        restored.restore(store.encode(), now)
        assertFalse(restored.ingest(stale, emptyMap(), now))
        assertEquals(listOf("active"), restored.records.map { it.id })
        assertEquals(80L, restored.records.single().upload)
        assertEquals(ConnectionHistoryCounters(), restored.counters)
    }

    @Test
    fun byteSaturatedActiveGrowthUsesLaterTerminalTransitionRegardlessOfSnapshotOrder() {
        val a = row("a", upload = 9)
        val b = row("b")
        val envelope = JSONObject(ConnectionHistoryStore().apply {
            ingest(snapshot(), emptyMap(), now)
        }.encode())
        for (objectName in listOf("counters", "nativeBaseline")) {
            val values = envelope.getJSONObject(objectName)
            for (key in values.keys()) values.put(key, Long.MAX_VALUE)
        }
        envelope.put("nativeGeneration", Long.MAX_VALUE)
        envelope.put("captureGeneration", Long.MAX_VALUE).put("captureEnabled", false)
        val budget = envelope.toString().toByteArray(Charsets.UTF_8).size +
            ConnectionJson.recordJson(a, includeApps = true).toString().toByteArray(Charsets.UTF_8).size +
            ConnectionJson.recordJson(b, includeApps = true).toString().toByteArray(Charsets.UTF_8).size + 2
        val growth = a.copy(upload = Long.MAX_VALUE)
        val completion = b.copy(state = "closed", endedAt = now)
        for (ordered in listOf(listOf(growth, completion), listOf(completion, growth))) {
            val store = ConnectionHistoryStore(maxBytes = budget)
            store.ingest(snapshot(a, b), emptyMap(), now)
            assertEquals(2, store.records.size)
            store.ingest(snapshot(*ordered.toTypedArray()), emptyMap(), now)
            assertEquals(listOf(growth), store.records)
            assertEquals(ConnectionHistoryCounters(historyEvicted = 1), store.counters)
            assertTrue(store.encode().toByteArray(Charsets.UTF_8).size <= budget)
        }
    }

    @Test
    fun impossibleActiveAdmissionDoesNotEvictUsefulFinishedRowsBeforeRefusing() {
        val store = ConnectionHistoryStore(maxBytes = 2_304)
        val active = row("active").copy(destination = "a".repeat(500))
        val closed = row("closed").copy(state = "closed", endedAt = now)
        val cannotFitBesideActive = row("large").copy(destination = "b".repeat(1_000))
        store.ingest(snapshot(active, closed), emptyMap(), now)
        assertEquals(2, store.records.size)
        val full = snapshot(active, closed, cannotFitBesideActive)
        repeat(3) { store.ingest(full, emptyMap(), now) }
        assertEquals(setOf("active", "closed"), store.records.map { it.id }.toSet())
        assertEquals(ConnectionHistoryCounters(historyRejected = 1), store.counters)
        assertTrue(store.encode().toByteArray(Charsets.UTF_8).size <= 2_304)
    }

    @Test
    fun newlySeenFinishedRowOutsideTheFifoWindowIsRotationNotCaptureRefusal() {
        val store = ConnectionHistoryStore(maxRecords = 2)
        val recent = row("recent").copy(state = "closed", endedAt = now)
        val newest = recent.copy(id = "newest", endedAt = now + 1)
        val old = recent.copy(id = "old", endedAt = now - 1)
        store.ingest(snapshot(recent, newest), emptyMap(), now + 1)
        val full = snapshot(recent, newest, old)
        repeat(3) { store.ingest(full, emptyMap(), now + 1) }
        assertEquals(setOf("recent", "newest"), store.records.map { it.id }.toSet())
        assertEquals(ConnectionHistoryCounters(historyEvicted = 1), store.counters)
    }

    @Test
    fun captureFencePreservesFinishedHistoryAndCutoffBytesAcrossResumeAndRestore() {
        val store = ConnectionHistoryStore()
        val old = row("old", upload = 5)
        val closed = row("closed").copy(state = "closed", endedAt = now)
        val stale = snapshot(old.copy(upload = 999), row("stale"), nativeEvicted = 99)
        store.ingest(snapshot(old, closed, nativeEvicted = 3), emptyMap(), now)
        store.captureChanged(false)
        val cutoff = old.copy(state = "interrupted", endedAt = now, upload = 23, liveBytesExact = false)
        store.ingest(snapshot(cutoff, closed, nativeEvicted = 3, enabled = false, captureGeneration = 1), emptyMap(), now)
        store.freezeCapture(now)
        val restored = ConnectionHistoryStore()
        restored.restore(store.encode(), now)
        assertFalse(restored.ingest(stale, emptyMap(), now))
        restored.captureChanged(true)
        restored.ingest(snapshot(old.copy(upload = 999), closed, row("fresh"), nativeEvicted = 3, captureGeneration = 2), emptyMap(), now)
        assertEquals(setOf("old", "closed", "fresh"), restored.records.map { it.id }.toSet())
        val retained = restored.records.single { it.id == "old" }
        assertEquals(23L, retained.upload)
        assertEquals("interrupted", retained.state)
        assertFalse(retained.liveBytesExact)
        assertEquals(ConnectionHistoryCounters(nativeEvicted = 3), restored.counters)
        restored.beginRuntime(now)
        restored.ingest(snapshot(row("new-process"), epoch = "new-process", captureGeneration = 0), emptyMap(), now)
        assertEquals(23L, restored.records.single { it.id == "old" }.upload)
        val saved = JSONObject(restored.encode())
        assertEquals(0L, saved.getLong("captureGeneration"))
        assertEquals(ConnectionHistoryCounters(nativeEvicted = 3), restored.counters)
    }

    @Test
    fun versionTwoHistoryMigratesWithoutInventingCaptureOrLegacyEventCounts() {
        val source = ConnectionHistoryStore()
        source.ingest(snapshot(row("retained"), nativeEvicted = 8, nativeRejected = 2), emptyMap(), now)
        val v2 = JSONObject(source.encode()).put("version", 2).apply {
            remove("captureGeneration")
            remove("captureEnabled")
            getJSONArray("connections").getJSONObject(0).remove("captureStopped")
        }
        val restored = ConnectionHistoryStore()
        restored.restore(v2.toString(), now)
        assertEquals("interrupted", restored.records.single().state)
        assertEquals(ConnectionHistoryCounters(nativeEvicted = 8, nativeRejected = 2), restored.counters)
        assertEquals(0L, JSONObject(restored.encode()).getLong("captureGeneration"))
        restored.ingest(snapshot(row("retained"), nativeEvicted = 8, nativeRejected = 2), emptyMap(), now)
        assertEquals(ConnectionHistoryCounters(nativeEvicted = 8, nativeRejected = 2), restored.counters)
    }

    @Test
    fun snapshotRequiresExplicitCaptureStateAndNonnegativeCaptureGeneration() {
        val valid = JSONObject().put("running", true).put("epoch", "process")
            .put("generation", 0).put("enabled", false).put("captureGeneration", 1)
            .put("nativeEvicted", 0).put("nativeRejected", 0).put("connections", org.json.JSONArray())
        assertFalse(ConnectionJson.snapshot(valid.toString()).enabled)
        for (missing in listOf("enabled", "captureGeneration")) {
            val invalid = JSONObject(valid.toString()).apply { remove(missing) }
            try {
                ConnectionJson.snapshot(invalid.toString())
                throw AssertionError("Capture state is required")
            } catch (_: org.json.JSONException) {
                // Missing native contract fields must not look like successful capture.
            }
        }
        try {
            ConnectionJson.snapshot(valid.put("captureGeneration", -1).toString())
            throw AssertionError("A negative capture fence must be rejected")
        } catch (_: IllegalArgumentException) {
            // A wrapped or corrupt native sequence must never undo the cutoff.
        }
    }

    private fun row(id: String, upload: Long = 0, download: Long = 0) = ConnectionRecord(
        id = id,
        protocol = "tcp",
        destination = "192.0.2.1:443",
        startedAt = now - 100,
        upload = upload,
        download = download,
        outboundTag = "proxy",
        route = "proxy",
    )

    private fun snapshot(
        vararg rows: ConnectionRecord,
        epoch: String = "epoch",
        nativeEvicted: Long = 0,
        nativeRejected: Long = 0,
        generation: Long = 0,
        enabled: Boolean = true,
        captureGeneration: Long = 0,
    ) = ConnectionSnapshot(true, epoch, nativeEvicted, nativeRejected, rows.toList(), generation, enabled, captureGeneration)
}
