package io.github.derundevu.yaxc.presentation.connectionanalytics

import io.github.derundevu.yaxc.repository.ConnectionApp
import io.github.derundevu.yaxc.repository.ConnectionHistoryState
import io.github.derundevu.yaxc.repository.ConnectionRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class ConnectionAnalyticsStateTest {
    @Test
    fun bothDirectionsIncludeEveryFlowOnceAndKeepSharedUidAndUnknownIdentitiesIndependent() {
        val shared = listOf(ConnectionApp("a.package", "First"), ConnectionApp("b.package", "Second"))
        val records = listOf(
            ConnectionRecord(id = "shared-a", uid = 42, apps = shared, profileId = 1, profileName = "Captured A", profileFingerprint = "old-a", upload = 100),
            ConnectionRecord(id = "shared-b", uid = 42, apps = shared.reversed(), profileId = 2, profileName = "Captured B", upload = 200),
            ConnectionRecord(id = "unknown-app", profileId = 1, profileName = "Captured A", profileFingerprint = "old-a", upload = 300),
            ConnectionRecord(id = "unknown-config", uid = 42, apps = shared, upload = 400),
        )
        ConnectionGrouping.entries.forEach { direction ->
            val state = connectionAnalyticsState(ConnectionHistoryState(records), direction, ConnectionFilter.All, Locale.US)
            val flows = state.items.filterIsInstance<ConnectionAnalyticsItem.Flow>()
            assertEquals(records.map { it.id }.toSet(), flows.map { it.record.id }.toSet())
            assertEquals(records.size, flows.size)
            assertEquals(state.items.size, state.items.map { it.key }.toSet().size)
            assertEquals(4, state.totals.count)
            assertEquals("1000 B", state.totals.upload)
            assertTrue(flows.first { it.record.id == "shared-a" }.app.title!!.contains("a.package"))
            assertTrue(flows.first { it.record.id == "shared-a" }.app.title!!.contains("b.package"))
            assertTrue(flows.first { it.record.id == "shared-a" }.app.sharedUid)
            assertEquals(flows.first { it.record.id == "shared-a" }.app, flows.first { it.record.id == "shared-b" }.app)
            assertNull(flows.first { it.record.id == "unknown-app" }.app.title)
            assertEquals("Captured A", flows.first { it.record.id == "unknown-app" }.config.title)
            assertNull(flows.first { it.record.id == "unknown-config" }.config.title)
            assertTrue(flows.first { it.record.id == "unknown-config" }.app.title != null)
            val primaryCount = state.items.filterIsInstance<ConnectionAnalyticsItem.Header>().filter { !it.secondary }.sumOf { it.totals.count }
            assertEquals(records.size, primaryCount)
        }
    }

    @Test
    fun renamedCapturedLabelsAndDifferentActualRoutesShareStableGroupsWithoutRelabelingFlows() {
        val older = ConnectionRecord(
            id = "older", startedAt = 100, uid = 42,
            apps = listOf(ConnectionApp("same.package", "Old app label")),
            profileId = 7, profileFingerprint = "same-profile", profileName = "Old config label",
            outboundTag = "physical-old", route = "proxy", upload = 100,
        )
        val newer = older.copy(
            id = "newer", startedAt = 200,
            apps = listOf(ConnectionApp("same.package", "New app label")),
            profileName = "New config label", outboundTag = "physical-new", route = "direct", upload = 200,
        )
        ConnectionGrouping.entries.forEach { direction ->
            val state = connectionAnalyticsState(ConnectionHistoryState(listOf(newer, older)), direction, ConnectionFilter.All)
            val headers = state.items.filterIsInstance<ConnectionAnalyticsItem.Header>()
            assertEquals(2, headers.size)
            assertEquals(1, headers.count { !it.secondary })
            assertEquals(1, headers.count { it.secondary })
            assertEquals(2, headers.first().totals.count)
            assertEquals("300 B", headers.first().totals.upload)
            assertTrue(headers.single { it.identity.kind == ConnectionIdentityKind.App }.identity.title!!.contains("New app label"))
            assertEquals("New config label", headers.single { it.identity.kind == ConnectionIdentityKind.Config }.identity.title)
            assertEquals(state.items.size, state.items.map { it.key }.toSet().size)
            val flows = state.items.filterIsInstance<ConnectionAnalyticsItem.Flow>().associateBy { it.record.id }
            assertTrue(flows.getValue("older").app.title!!.contains("Old app label"))
            assertEquals("Old config label", flows.getValue("older").config.title)
            assertEquals("physical-old", flows.getValue("older").record.outboundTag)
            assertEquals("proxy", flows.getValue("older").record.route)
            assertEquals("New config label", flows.getValue("newer").config.title)
            assertEquals(flows.getValue("older").app.key, flows.getValue("newer").app.key)
            assertEquals(flows.getValue("older").config.key, flows.getValue("newer").config.key)
        }
    }

    @Test
    fun closedFilterIncludesAllTerminalOutcomesAndUpdatesPreserveFlowKeysAndCapturedConfig() {
        val records = listOf("active", "closed", "error", "blocked", "interrupted").mapIndexed { index, state ->
            ConnectionRecord(id = "flow-$index", state = state, startedAt = index.toLong(), profileName = "At dispatch", profileFingerprint = "captured")
        }
        fun build(filter: ConnectionFilter, input: List<ConnectionRecord> = records) = connectionAnalyticsState(ConnectionHistoryState(input), ConnectionGrouping.Config, filter)
        assertEquals(listOf("flow-0"), build(ConnectionFilter.Active).items.filterIsInstance<ConnectionAnalyticsItem.Flow>().map { it.record.id })
        assertEquals(4, build(ConnectionFilter.Closed).totals.count)
        assertTrue(build(ConnectionFilter.Active).hasClosedHistory)
        val original = build(ConnectionFilter.All).items.filterIsInstance<ConnectionAnalyticsItem.Flow>().first()
        val updated = build(ConnectionFilter.All, records.map { it.copy(upload = 1234, download = 5678) }).items.filterIsInstance<ConnectionAnalyticsItem.Flow>().first()
        assertEquals(original.key, updated.key)
        assertEquals(original.config, updated.config)
    }
}
