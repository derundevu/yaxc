package io.github.derundevu.yaxc.presentation.connectionanalytics

import io.github.derundevu.yaxc.repository.ConnectionApp
import io.github.derundevu.yaxc.repository.ConnectionCaptureStatus
import io.github.derundevu.yaxc.repository.ConnectionHistoryCounters
import io.github.derundevu.yaxc.repository.ConnectionHistoryState
import io.github.derundevu.yaxc.repository.ConnectionRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class ConnectionAnalyticsStateTest {
    private val now = 10_000_000L
    private fun present(records: List<ConnectionRecord>, query: ConnectionAnalyticsQuery = ConnectionAnalyticsQuery()) =
        connectionAnalyticsState(ConnectionHistoryState(records), query, Locale.US, now)

    @Test
    fun rootsContainOnlySummariesAndDrilldownsPartitionRetainedFlowsInBothDirections() {
        val shared = listOf(ConnectionApp("a.package", "First"), ConnectionApp("b.package", "Second"))
        val records = listOf(
            ConnectionRecord(id = "shared-a", uid = 42, apps = shared, profileId = 1, profileName = "Captured A", profileFingerprint = "old-a", upload = 100),
            ConnectionRecord(id = "shared-b", uid = 42, apps = shared.reversed(), profileId = 2, profileName = "Captured B", upload = 200),
            ConnectionRecord(id = "unknown-app", profileId = 1, profileName = "Captured A", profileFingerprint = "old-a", upload = 300),
            ConnectionRecord(id = "unknown-config", uid = 42, apps = shared, upload = 400),
            ConnectionRecord(id = "unknown-other-uid", uid = 99, upload = 500),
        )
        ConnectionGrouping.entries.forEach { direction ->
            val root = present(records, ConnectionAnalyticsQuery(grouping = direction))
            assertTrue(root.flows.isEmpty())
            assertTrue(root.oppositeIdentities.isEmpty())
            assertEquals(5, root.totals.count)
            assertEquals("1.5 KiB", root.totals.upload)
            assertEquals(records.size, root.summaries.sumOf { it.totals.count })
            assertEquals(root.summaries.size, root.summaries.map { it.key }.toSet().size)
            val selectedFlows = root.summaries.flatMap { summary ->
                val selected = present(records, root.query.copy(selection = summary.identity))
                assertTrue(selected.summaries.isEmpty())
                assertEquals(summary.totals, selected.totals)
                selected.flows
            }
            assertEquals(records.map { it.id }.toSet(), selectedFlows.map { it.record.id }.toSet())
            assertEquals(records.size, selectedFlows.size)
            val sharedFlow = selectedFlows.first { it.record.id == "shared-a" }
            assertTrue(sharedFlow.app.sharedUid)
            assertEquals(listOf("a.package", "b.package"), sharedFlow.app.packages)
            assertEquals(sharedFlow.app, selectedFlows.first { it.record.id == "shared-b" }.app)
            assertNull(selectedFlows.first { it.record.id == "unknown-app" }.app.title)
            assertNotEquals(selectedFlows.first { it.record.id == "unknown-app" }.app.key, selectedFlows.first { it.record.id == "unknown-other-uid" }.app.key)
            assertNull(selectedFlows.first { it.record.id == "unknown-config" }.config.title)
        }
    }

    @Test
    fun rootStatusFilterChangesMembershipAndSummaryTotalsWithoutChangingDetailContext() {
        val records = listOf(
            ConnectionRecord(id = "active", uid = 1, upload = 100),
            ConnectionRecord(id = "closed", uid = 1, state = "closed", upload = 200),
            ConnectionRecord(id = "only-closed", uid = 2, state = "error", upload = 400),
        )
        val active = present(records, ConnectionAnalyticsQuery(rootFilter = ConnectionFilter.Active))
        assertEquals(1, active.summaries.size)
        assertEquals(1, active.totals.count)
        assertEquals("100 B", active.totals.upload)
        assertEquals(1, active.summaries.single().activeCount)
        val closed = present(records, active.query.copy(rootFilter = ConnectionFilter.Closed))
        assertEquals(2, closed.summaries.size)
        assertEquals(2, closed.totals.count)
        assertEquals("600 B", closed.totals.upload)
        assertTrue(closed.summaries.all { it.activeCount == 0 })
        assertTrue(active.hasClosedHistory)
    }

    @Test
    fun historicalLabelsAndActualRoutesRemainCapturedWhileKeysSurviveRenames() {
        val older = ConnectionRecord(id = "older", startedAt = 100, uid = 42,
            apps = listOf(ConnectionApp("same.package", "Old app label")),
            profileId = 7, profileFingerprint = "same-profile", profileName = "Old config label",
            outboundTag = "physical-old", route = "proxy", upload = 100)
        val newer = older.copy(id = "newer", startedAt = 200,
            apps = listOf(ConnectionApp("same.package", "New app label")), profileName = "New config label",
            outboundTag = "physical-new", route = "direct", upload = 200)
        ConnectionGrouping.entries.forEach { direction ->
            val root = present(listOf(older, newer), ConnectionAnalyticsQuery(grouping = direction))
            assertEquals(1, root.summaries.size)
            assertEquals(2, root.summaries.single().totals.count)
            val selection = root.summaries.single().identity
            assertTrue(selection.title!!.contains(if (direction == ConnectionGrouping.App) "New app label" else "New config label"))
            val selected = present(listOf(older, newer), root.query.copy(selection = selection))
            assertEquals(listOf("newer", "older"), selected.flows.map { it.record.id })
            val oldFlow = selected.flows.last()
            val newFlow = selected.flows.first()
            assertTrue(oldFlow.app.title!!.contains("Old app label"))
            assertEquals("Old config label", oldFlow.config.title)
            assertEquals("physical-old", oldFlow.record.outboundTag)
            assertEquals("proxy", oldFlow.record.route)
            assertEquals(oldFlow.app.key, newFlow.app.key)
            assertEquals(oldFlow.config.key, newFlow.config.key)
        }
    }

    @Test
    fun allDetailFiltersCombineAndTotalsDescribeOnlyTheirIntersection() {
        val target = ConnectionRecord(id = "target", uid = 1, profileId = 7, profileName = "Config", protocol = "udp", route = "direct",
            state = "error", startedAt = now - 100, endedAt = now - 50, destination = "203.0.113.1:443", domain = "EXAMPLE.org", upload = 300, download = 200)
        val records = listOf(target,
            target.copy(id = "other-app", uid = 2), target.copy(id = "other-config", profileId = 8),
            target.copy(id = "tcp", protocol = "tcp"), target.copy(id = "proxy", route = "proxy"),
            target.copy(id = "closed", state = "closed"), target.copy(id = "old", startedAt = now - 300_001),
            target.copy(id = "other-destination", domain = "other.org", destination = "192.0.2.1:80"),
            target.copy(id = "active", state = "active"))
        val root = present(records)
        val selection = root.summaries.first { it.totals.count > 1 }.identity
        val scope = present(records, root.query.copy(selection = selection))
        val opposite = scope.oppositeIdentities.first { it.detail.startsWith("#7") }
        val query = scope.query.copy(search = "example.ORG", filter = ConnectionFilter.Closed, oppositeKey = opposite.key,
            protocol = ConnectionProtocol.Udp, route = ConnectionRoute.Direct, terminal = ConnectionTerminal.Error,
            period = ConnectionPeriod.FiveMinutes, sort = ConnectionSort.Traffic)
        val selected = present(records, query)
        assertEquals(listOf("target"), selected.flows.map { it.record.id })
        assertEquals(1, selected.totals.count)
        assertEquals("300 B", selected.totals.upload)
        assertEquals("200 B", selected.totals.download)
        assertEquals(listOf("target"), present(records, query.copy(search = "443")).flows.map { it.record.id })
        assertEquals(listOf("target"), present(records, query.copy(search = "203.0.113")).flows.map { it.record.id })
        assertEquals(0, present(records, query.copy(filter = ConnectionFilter.Active)).totals.count)
    }

    @Test
    fun associationDestinationsUnknownRoutesAndEveryTerminalOutcomeAreFilterable() {
        val records = listOf("closed", "error", "blocked", "interrupted").mapIndexed { index, state ->
            ConnectionRecord(id = state, uid = 1, state = state, route = "custom", protocol = "udp", destinations = listOf("198.51.100.$index:53"))
        } + ConnectionRecord(id = "active", uid = 1)
        val selection = present(records).summaries.single().identity
        val query = ConnectionAnalyticsQuery(selection = selection, filter = ConnectionFilter.Closed, route = ConnectionRoute.Unknown)
        assertEquals(4, present(records, query).totals.count)
        ConnectionTerminal.entries.filter { it != ConnectionTerminal.All }.forEach { terminal ->
            assertEquals(listOf(terminal.name.lowercase()), present(records, query.copy(terminal = terminal)).flows.map { it.record.id })
        }
        assertEquals(listOf("error"), present(records, query.copy(search = "198.51.100.1:53")).flows.map { it.record.id })
    }

    @Test
    fun sortingUsesNewestTrafficAndElapsedDurationWithStableTies() {
        val records = listOf(
            ConnectionRecord(id = "new", uid = 1, startedAt = now - 10, upload = 1),
            ConnectionRecord(id = "traffic", uid = 1, startedAt = now - 20, upload = 100, download = 200),
            ConnectionRecord(id = "long", uid = 1, startedAt = now - 300, endedAt = now - 100, state = "closed", upload = 10),
        )
        val query = ConnectionAnalyticsQuery(selection = present(records).summaries.single().identity)
        assertEquals(listOf("new", "traffic", "long"), present(records, query).flows.map { it.record.id })
        assertEquals(listOf("traffic", "long", "new"), present(records, query.copy(sort = ConnectionSort.Traffic)).flows.map { it.record.id })
        assertEquals(listOf("long", "traffic", "new"), present(records, query.copy(sort = ConnectionSort.Duration)).flows.map { it.record.id })
    }

    @Test
    fun periodClockExpiresRecordsWithoutNewCaptureAndHasNoRootOrAllPeriodTimer() {
        val record = ConnectionRecord(id = "boundary", uid = 1, startedAt = now - 300_000)
        val history = ConnectionHistoryState(listOf(record))
        val root = present(history.connections)
        val query = root.query.copy(selection = root.summaries.single().identity, period = ConnectionPeriod.FiveMinutes)
        assertEquals(1, connectionAnalyticsState(history, query, Locale.US, now).totals.count)
        assertEquals(0, connectionAnalyticsState(history, query, Locale.US, now + 1).totals.count)
        assertEquals(1L, nextConnectionRefresh(connectionAnalyticsState(history, query, Locale.US, now), now))
        assertNull(nextConnectionRefresh(connectionAnalyticsState(history, query, Locale.US, now + 1), now + 1))
        assertNull(nextConnectionRefresh(root, now))
        assertNull(nextConnectionRefresh(connectionAnalyticsState(history, query.copy(period = ConnectionPeriod.All), Locale.US, now), now))
    }

    @Test
    fun durationClockSleepsUntilAnActiveFlowOvertakesAFinishedFlow() {
        val active = ConnectionRecord(id = "active", uid = 1, startedAt = now - 10)
        val finished = ConnectionRecord(id = "finished", uid = 1, startedAt = now - 100, endedAt = now - 50, state = "closed")
        val records = listOf(active, finished)
        val query = ConnectionAnalyticsQuery(selection = present(records).summaries.single().identity, sort = ConnectionSort.Duration)
        val history = ConnectionHistoryState(records)
        val state = connectionAnalyticsState(history, query, Locale.US, now)
        assertEquals(listOf("finished", "active"), state.flows.map { it.record.id })
        assertEquals(41L, nextConnectionRefresh(state, now))
        assertEquals(listOf("active", "finished"), connectionAnalyticsState(history, query, Locale.US, now + 41).flows.map { it.record.id })
    }

    @Test
    fun unchangedRowsReusePresentationAndCountersRemainSeparateFromRetainedTotals() {
        val records = listOf(ConnectionRecord(id = "one", uid = 1), ConnectionRecord(id = "two", uid = 1))
        val counters = ConnectionHistoryCounters(nativeEvicted = 10, nativeRejected = 3, historyEvicted = 20, historyRejected = 2, unclassified = 4)
        val history = ConnectionHistoryState(connections = records, counters = counters)
        val query = ConnectionAnalyticsQuery(selection = present(records).summaries.single().identity)
        val presenter = ConnectionAnalyticsPresenter()
        val before = presenter.present(history, query, Locale.US, now)
        val after = presenter.present(history.copy(connections = listOf(records[0], records[1].copy(upload = 123))), query, Locale.US, now + 1)
        assertSame(before.flows.first { it.record.id == "one" }, after.flows.first { it.record.id == "one" })
        assertEquals(before.flows.map { it.key }, after.flows.map { it.key })
        assertEquals(2, after.totals.count)
        assertEquals(counters, after.counters)
        assertFalse(after.flows.any { it.record.id.isEmpty() })
    }

    @Test
    fun captureAndDiskTransitionsRefreshCachedStateWithoutDiscardingRetainedRows() {
        val records = listOf(ConnectionRecord(id = "past", uid = 1, state = "interrupted", upload = 456))
        val presenter = ConnectionAnalyticsPresenter()
        val enabled = ConnectionHistoryState(connections = records, status = ConnectionCaptureStatus.Running)
        val root = presenter.present(enabled, ConnectionAnalyticsQuery(), Locale.US, now)
        assertTrue(root.captureEnabled)
        val disabled = enabled.copy(status = ConnectionCaptureStatus.Disabled, captureEnabled = false)
        val rootDisabled = presenter.present(disabled, root.query, Locale.US, now)
        assertFalse(rootDisabled.captureEnabled)
        assertFalse(rootDisabled.persistOnDisk)
        assertEquals(ConnectionCaptureStatus.Disabled, rootDisabled.status)
        assertEquals(root.summaries, rootDisabled.summaries)
        assertEquals(root.totals, rootDisabled.totals)
        assertTrue(rootDisabled.hasClosedHistory)

        val selected = root.query.copy(selection = root.summaries.single().identity)
        val detail = presenter.present(disabled, selected, Locale.US, now)
        val diskEnabled = presenter.present(disabled.copy(persistOnDisk = true), selected, Locale.US, now)
        assertFalse(diskEnabled.captureEnabled)
        assertTrue(diskEnabled.persistOnDisk)
        assertSame(detail.flows.single(), diskEnabled.flows.single())
        val resumed = presenter.present(enabled.copy(persistOnDisk = true), selected, Locale.US, now)
        assertTrue(resumed.captureEnabled)
        assertTrue(resumed.persistOnDisk)
        assertEquals(ConnectionCaptureStatus.Running, resumed.status)
        assertSame(detail.flows.single(), resumed.flows.single())
        assertEquals(records, resumed.flows.map { it.record })
    }
}
