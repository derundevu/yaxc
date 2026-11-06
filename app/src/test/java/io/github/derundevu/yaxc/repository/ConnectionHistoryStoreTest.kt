package io.github.derundevu.yaxc.repository

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
          "running":true,"epoch":"z-old","dropped":3,"connections":[{
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
        assertEquals(3L, store.dropped)
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
        store.ingest(snapshot(row("new"), epoch = "different", dropped = 7), emptyMap(), now + 1)
        assertEquals(setOf("one", "new"), store.records.map { it.id }.toSet())
        assertEquals(7L, store.dropped)
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
        val overflow = snapshot(*(active + row("c") + row("closed").copy(state = "closed", endedAt = now)).toTypedArray(), dropped = 4)
        store.ingest(overflow, emptyMap(), now)
        assertEquals(setOf("a", "b"), store.records.map { it.id }.toSet())
        assertEquals(6L, store.dropped)
        repeat(3) { store.ingest(overflow, emptyMap(), now) }
        assertEquals(6L, store.dropped)
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
        assertEquals(1L, store.dropped)
        assertTrue(store.encode().toByteArray(Charsets.UTF_8).size <= 1_024)
        store.ingest(snapshot(oversized, useful), emptyMap(), now)
        assertEquals(1L, store.dropped)
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
        assertEquals(0L, store.dropped)
    }

    @Test
    fun clearingOnlyClosedRowsPreservesActiveCountersAndNativeOverflow() {
        val store = ConnectionHistoryStore()
        val active = row("active", upload = 45, download = 67)
        store.ingest(snapshot(active, row("closed").copy(state = "blocked", endedAt = now), dropped = 8), emptyMap(), now)
        store.clearClosed()
        assertEquals(listOf(active), store.records)
        assertEquals(8L, store.dropped)
    }

    @Test
    fun crashRestoreInterruptsActiveAndPersistsHistoricalAppAndConfigIdentity() {
        val store = ConnectionHistoryStore()
        val apps = mapOf(100 to listOf(ConnectionApp("gone.pkg", "Uninstalled app")))
        val original = row("active", upload = 99, download = 88).copy(uid = 100, profileId = 17, profileName = "Deleted profile")
        store.ingest(snapshot(original, row("closed").copy(state = "closed", endedAt = now - 5), dropped = 2), apps, now)
        val saved = store.encode()
        val restored = ConnectionHistoryStore()
        restored.restore(saved, now + 5)
        val interrupted = restored.records.single { it.id == "active" }
        assertEquals("interrupted", interrupted.state)
        assertEquals(now + 5, interrupted.endedAt)
        assertEquals(99L, interrupted.upload)
        assertEquals(apps.getValue(100), interrupted.apps)
        assertEquals("Deleted profile", interrupted.profileName)
        assertEquals(2L, restored.dropped)
        assertEquals("closed", restored.records.single { it.id == "closed" }.state)
        assertEquals(1, JSONObject(restored.encode()).getInt("version"))
        restored.clearClosed()
        assertTrue(restored.records.isEmpty())
    }

    @Test
    fun productionDefaultsBoundBothFiveThousandRowsAndEightMiBOfEncodedHistory() {
        val countBound = ConnectionHistoryStore()
        val rows = List(5_001) { index -> row("row-$index").copy(startedAt = now - index) }
        countBound.ingest(ConnectionSnapshot(true, "count", 0, rows), emptyMap(), now)
        assertEquals(5_000, countBound.records.size)
        assertEquals(1L, countBound.dropped)
        val byteBound = ConnectionHistoryStore()
        val largerRows = rows.take(5_000).map { it.copy(destination = "d".repeat(2_000)) }
        byteBound.ingest(ConnectionSnapshot(true, "bytes", 0, largerRows), emptyMap(), now)
        assertTrue(byteBound.records.size < 5_000)
        assertTrue(byteBound.dropped > 0)
        assertTrue(byteBound.encode().toByteArray(Charsets.UTF_8).size <= 8 * 1024 * 1024)
    }

    @Test
    fun processEpochOverflowIsNotDoubleCountedAcrossRuntimeReloadOrDiskRestore() {
        val store = ConnectionHistoryStore()
        store.ingest(snapshot(row("one"), epoch = "process", dropped = 5), emptyMap(), now)
        store.beginRuntime(now + 1)
        store.ingest(snapshot(row("two"), epoch = "process", dropped = 7), emptyMap(), now + 1)
        assertEquals(7L, store.dropped)
        val restored = ConnectionHistoryStore()
        restored.restore(store.encode(), now + 2)
        restored.beginRuntime(now + 2)
        restored.ingest(snapshot(row("three"), epoch = "process", dropped = 9), emptyMap(), now + 2)
        assertEquals(9L, restored.dropped)
        restored.nativeHistoryCleared()
        restored.clearClosed()
        restored.ingest(snapshot(row("three"), epoch = "process", dropped = 0), emptyMap(), now + 2)
        assertEquals(9L, restored.dropped)
        restored.ingest(snapshot(row("three"), epoch = "process", dropped = 2), emptyMap(), now + 2)
        assertEquals(11L, restored.dropped)
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

    private fun snapshot(vararg rows: ConnectionRecord, epoch: String = "epoch", dropped: Long = 0) =
        ConnectionSnapshot(true, epoch, dropped, rows.toList())
}
