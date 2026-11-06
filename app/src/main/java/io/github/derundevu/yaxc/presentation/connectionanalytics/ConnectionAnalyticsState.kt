package io.github.derundevu.yaxc.presentation.connectionanalytics

import io.github.derundevu.yaxc.repository.ConnectionCaptureStatus
import io.github.derundevu.yaxc.repository.ConnectionHistoryState
import io.github.derundevu.yaxc.repository.ConnectionRecord
import java.text.DateFormat
import java.util.Date
import java.util.Locale

enum class ConnectionGrouping { App, Config }
enum class ConnectionFilter { Active, Closed, All }
enum class ConnectionIdentityKind { App, Config }

data class ConnectionIdentity(
    val key: String,
    val kind: ConnectionIdentityKind,
    val title: String?,
    val detail: String,
    val sharedUid: Boolean = false,
)

data class ConnectionTotals(
    val count: Int = 0,
    val upload: String = "0 B",
    val download: String = "0 B",
    val liveBytesExact: Boolean = true,
)

sealed interface ConnectionAnalyticsItem {
    val key: String
    data class Header(
        override val key: String,
        val identity: ConnectionIdentity,
        val totals: ConnectionTotals,
        val secondary: Boolean,
    ) : ConnectionAnalyticsItem
    data class Flow(
        override val key: String,
        val record: ConnectionRecord,
        val app: ConnectionIdentity,
        val config: ConnectionIdentity,
        val started: String,
        val ended: String?,
        val upload: String,
        val download: String,
        val destinations: String,
    ) : ConnectionAnalyticsItem
}

data class ConnectionAnalyticsState(
    val grouping: ConnectionGrouping = ConnectionGrouping.App,
    val filter: ConnectionFilter = ConnectionFilter.All,
    val status: ConnectionCaptureStatus = ConnectionCaptureStatus.Stopped,
    val persistOnDisk: Boolean = false,
    val error: String? = null,
    val dropped: Long = 0,
    val totals: ConnectionTotals = ConnectionTotals(),
    val items: List<ConnectionAnalyticsItem> = emptyList(),
    val hasClosedHistory: Boolean = false,
)

/** Called on the processing dispatcher, never while composing lazy rows. */
internal fun connectionAnalyticsState(
    history: ConnectionHistoryState,
    grouping: ConnectionGrouping,
    filter: ConnectionFilter,
    locale: Locale = Locale.getDefault(),
): ConnectionAnalyticsState {
    fun key(vararg parts: String) = parts.joinToString("") { "${it.length}:$it" }
    fun app(record: ConnectionRecord): ConnectionIdentity {
        val packages = record.apps.sortedBy { it.packageName }
        return ConnectionIdentity(
            key("app", record.uid.toString(), *packages.map { it.packageName }.toTypedArray()),
            ConnectionIdentityKind.App,
            packages.takeIf { it.isNotEmpty() }?.joinToString("\n") {
                if (it.name.isBlank() || it.name == it.packageName) it.packageName else "${it.name} (${it.packageName})"
            },
            if (record.uid >= 0) "UID ${record.uid}" else "",
            sharedUid = packages.size > 1,
        )
    }
    fun config(record: ConnectionRecord) = ConnectionIdentity(
        if (record.profileId > 0) key("config", record.profileId.toString(), record.profileFingerprint)
        else key("unknown-config", record.profileFingerprint, record.outboundTag, record.route),
        ConnectionIdentityKind.Config,
        record.profileName.takeIf { it.isNotBlank() },
        listOfNotNull(
            record.profileId.takeIf { it != 0L }?.let { "#$it" },
            record.profileFingerprint.takeIf { it.isNotBlank() },
        ).joinToString(" · "),
    )
    fun bytes(value: Long): String {
        val safe = value.coerceAtLeast(0)
        if (safe < 1024) return "$safe B"
        val units = arrayOf("KiB", "MiB", "GiB", "TiB", "PiB", "EiB")
        var size = safe.toDouble() / 1024
        var index = 0
        while (size >= 1024 && index < units.lastIndex) { size /= 1024; index++ }
        return String.format(locale, "%.1f %s", size, units[index])
    }
    fun totals(records: List<ConnectionRecord>) = ConnectionTotals(
        records.size, bytes(records.sumOf { it.upload }), bytes(records.sumOf { it.download }),
        records.all { it.liveBytesExact },
    )
    val visible = history.connections.filter {
        when (filter) {
            ConnectionFilter.Active -> it.state == "active"
            ConnectionFilter.Closed -> it.state != "active"
            ConnectionFilter.All -> true
        }
    }.sortedWith(compareBy<ConnectionRecord> { it.startedAt }.thenBy { it.id })
    val apps = visible.associate { it.id to app(it) }
    val configs = visible.associate { it.id to config(it) }
    fun first(record: ConnectionRecord) = if (grouping == ConnectionGrouping.App) apps.getValue(record.id) else configs.getValue(record.id)
    fun second(record: ConnectionRecord) = if (grouping == ConnectionGrouping.App) configs.getValue(record.id) else apps.getValue(record.id)
    val dateFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM, locale)
    val items = buildList {
        visible.groupBy { first(it).key }.toSortedMap()
            .forEach { (primaryKey, records) ->
                val primary = first(records.last())
                add(ConnectionAnalyticsItem.Header(key("group", primaryKey), primary, totals(records), false))
                records.groupBy { second(it).key }.toSortedMap()
                    .forEach { (secondaryKey, flows) ->
                        val secondary = second(flows.last())
                        add(ConnectionAnalyticsItem.Header(key("subgroup", primaryKey, secondaryKey), secondary, totals(flows), true))
                        flows.forEach { record ->
                            add(ConnectionAnalyticsItem.Flow(
                                "flow:${record.id}", record, apps.getValue(record.id), configs.getValue(record.id),
                                dateFormat.format(Date(record.startedAt)),
                                record.endedAt.takeIf { it > 0 }?.let { dateFormat.format(Date(it)) },
                                bytes(record.upload), bytes(record.download),
                                (listOf(record.destination) + record.destinations).filter { it.isNotBlank() }.distinct().joinToString("\n"),
                            ))
                        }
                    }
            }
    }
    return ConnectionAnalyticsState(
        grouping, filter, history.status, history.persistOnDisk, history.error, history.dropped,
        totals(visible), items, history.connections.any { it.state != "active" },
    )
}
