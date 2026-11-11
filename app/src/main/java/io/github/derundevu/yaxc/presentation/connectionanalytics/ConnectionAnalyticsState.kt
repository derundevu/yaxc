package io.github.derundevu.yaxc.presentation.connectionanalytics

import io.github.derundevu.yaxc.repository.ConnectionCaptureStatus
import io.github.derundevu.yaxc.repository.ConnectionHistoryCounters
import io.github.derundevu.yaxc.repository.ConnectionHistoryState
import io.github.derundevu.yaxc.repository.ConnectionRecord
import java.text.DateFormat
import java.util.Date
import java.util.Locale

enum class ConnectionGrouping { App, Config }
enum class ConnectionFilter { Active, Closed, All }
enum class ConnectionIdentityKind { App, Config }
enum class ConnectionProtocol { All, Tcp, Udp }
enum class ConnectionRoute { All, Proxy, Direct, Block, Unknown }
enum class ConnectionTerminal { All, Closed, Error, Blocked, Interrupted }
enum class ConnectionPeriod(val millis: Long) { All(0), FiveMinutes(300_000), Hour(3_600_000), Day(86_400_000) }
enum class ConnectionSort { Newest, Traffic, Duration }

data class ConnectionIdentity(
    val key: String,
    val kind: ConnectionIdentityKind,
    val title: String?,
    val detail: String,
    val sharedUid: Boolean = false,
    val packages: List<String> = emptyList(),
)

data class ConnectionAnalyticsQuery(
    val grouping: ConnectionGrouping = ConnectionGrouping.App,
    val rootFilter: ConnectionFilter = ConnectionFilter.All,
    val selection: ConnectionIdentity? = null,
    val filter: ConnectionFilter = ConnectionFilter.All,
    val search: String = "",
    val oppositeKey: String? = null,
    val protocol: ConnectionProtocol = ConnectionProtocol.All,
    val route: ConnectionRoute = ConnectionRoute.All,
    val terminal: ConnectionTerminal = ConnectionTerminal.All,
    val period: ConnectionPeriod = ConnectionPeriod.All,
    val sort: ConnectionSort = ConnectionSort.Newest,
)

data class ConnectionTotals(
    val count: Int = 0,
    val upload: String = "0 B",
    val download: String = "0 B",
    val liveBytesExact: Boolean = true,
)

data class ConnectionSummary(
    val identity: ConnectionIdentity,
    val totals: ConnectionTotals,
    val activeCount: Int,
    val lastSeen: Long,
) { val key: String get() = "group:${identity.key}" }

data class ConnectionFlow(
    val record: ConnectionRecord,
    val app: ConnectionIdentity,
    val config: ConnectionIdentity,
    val endpoint: String,
    val started: String,
    val upload: String,
    val download: String,
) { val key: String get() = "flow:${record.id}" }

data class ConnectionAnalyticsState(
    val query: ConnectionAnalyticsQuery = ConnectionAnalyticsQuery(),
    val status: ConnectionCaptureStatus = ConnectionCaptureStatus.Stopped,
    val persistOnDisk: Boolean = false,
    val error: String? = null,
    val counters: ConnectionHistoryCounters = ConnectionHistoryCounters(),
    val totals: ConnectionTotals = ConnectionTotals(),
    val summaries: List<ConnectionSummary> = emptyList(),
    val flows: List<ConnectionFlow> = emptyList(),
    val oppositeIdentities: List<ConnectionIdentity> = emptyList(),
    val hasClosedHistory: Boolean = false,
    val captureEnabled: Boolean = true,
)

private fun identityKey(vararg parts: String) = parts.joinToString("") { "${it.length}:$it" }

private fun appKey(record: ConnectionRecord): String = identityKey(
    "app", record.uid.toString(), *record.apps.map { it.packageName }.sorted().toTypedArray(),
)

private fun configKey(record: ConnectionRecord): String =
    if (record.profileId > 0) identityKey("config", record.profileId.toString(), record.profileFingerprint)
    else identityKey("unknown-config", record.profileFingerprint, record.outboundTag, record.route)

private fun primaryKey(record: ConnectionRecord, grouping: ConnectionGrouping) =
    if (grouping == ConnectionGrouping.App) appKey(record) else configKey(record)

private fun appIdentity(record: ConnectionRecord): ConnectionIdentity {
    val apps = record.apps.sortedBy { it.packageName }
    return ConnectionIdentity(
        appKey(record), ConnectionIdentityKind.App,
        apps.takeIf { it.isNotEmpty() }?.joinToString(" · ") {
            if (it.name.isBlank() || it.name == it.packageName) it.packageName else "${it.name} (${it.packageName})"
        },
        if (record.uid >= 0) "UID ${record.uid}" else "",
        sharedUid = apps.size > 1,
        packages = apps.map { it.packageName },
    )
}

private fun configIdentity(record: ConnectionRecord) = ConnectionIdentity(
    configKey(record), ConnectionIdentityKind.Config, record.profileName.takeIf { it.isNotBlank() },
    listOfNotNull(record.profileId.takeIf { it != 0L }?.let { "#$it" }, record.profileFingerprint.takeIf { it.isNotBlank() }).joinToString(" · "),
)

private fun primaryIdentity(record: ConnectionRecord, grouping: ConnectionGrouping) =
    if (grouping == ConnectionGrouping.App) appIdentity(record) else configIdentity(record)

private fun oppositeIdentity(record: ConnectionRecord, grouping: ConnectionGrouping) =
    if (grouping == ConnectionGrouping.App) configIdentity(record) else appIdentity(record)

private fun ConnectionFilter.matches(record: ConnectionRecord) = when (this) {
    ConnectionFilter.Active -> record.state == "active"
    ConnectionFilter.Closed -> record.state != "active"
    ConnectionFilter.All -> true
}

internal fun connectionBytes(value: Long, locale: Locale): String {
    val safe = value.coerceAtLeast(0)
    if (safe < 1024) return "$safe B"
    val units = arrayOf("KiB", "MiB", "GiB", "TiB", "PiB", "EiB")
    var size = safe.toDouble() / 1024
    var index = 0
    while (size >= 1024 && index < units.lastIndex) { size /= 1024; index++ }
    return String.format(locale, "%.1f %s", size, units[index])
}

private fun totals(records: List<ConnectionRecord>, locale: Locale) = ConnectionTotals(
    records.size, connectionBytes(records.sumOf { it.upload }, locale), connectionBytes(records.sumOf { it.download }, locale),
    records.all { it.liveBytesExact },
)

/** Pure host-benchmark entry point. Root presentation never creates flow rows or formats timestamps. */
internal fun connectionAnalyticsState(
    history: ConnectionHistoryState,
    query: ConnectionAnalyticsQuery,
    locale: Locale = Locale.getDefault(),
    now: Long = System.currentTimeMillis(),
): ConnectionAnalyticsState = ConnectionAnalyticsPresenter().present(history, query, locale, now)

/** Screen-owned, bounded cache: unchanged rows retain their presentation across live byte updates elsewhere. */
internal class ConnectionAnalyticsPresenter {
    private var cachedLocale: Locale? = null
    private val rows = HashMap<String, ConnectionFlow>()
    private var lastConnections: List<ConnectionRecord>? = null
    private var lastQuery: ConnectionAnalyticsQuery? = null
    private var lastState: ConnectionAnalyticsState? = null

    fun present(history: ConnectionHistoryState, query: ConnectionAnalyticsQuery, locale: Locale, now: Long): ConnectionAnalyticsState {
        val timeDependent = query.selection != null && (query.period != ConnectionPeriod.All || query.sort == ConnectionSort.Duration)
        if (lastConnections === history.connections && lastQuery == query && cachedLocale == locale && !timeDependent) {
            return requireNotNull(lastState).copy(status = history.status, persistOnDisk = history.persistOnDisk,
                error = history.error, counters = history.counters, captureEnabled = history.captureEnabled)
        }
        if (cachedLocale != locale) rows.clear()
        cachedLocale = locale
        val summaries: List<ConnectionSummary>
        val flows: List<ConnectionFlow>
        val opposite: List<ConnectionIdentity>
        val retained: List<ConnectionRecord>
        if (query.selection == null) {
            rows.clear()
            retained = history.connections.filter { query.rootFilter.matches(it) }
            summaries = retained.groupBy { primaryKey(it, query.grouping) }.values.map { records ->
                val newest = records.maxWith(compareBy<ConnectionRecord> { it.startedAt }.thenBy { it.id })
                ConnectionSummary(primaryIdentity(newest, query.grouping), totals(records, locale), records.count { it.state == "active" }, newest.startedAt)
            }.sortedWith(compareByDescending<ConnectionSummary> { it.lastSeen }.thenBy { it.identity.key })
            flows = emptyList()
            opposite = emptyList()
        } else {
            summaries = emptyList()
            val scope = history.connections.filter { primaryKey(it, query.grouping) == query.selection.key }
            opposite = scope.groupBy {
                if (query.grouping == ConnectionGrouping.App) configKey(it) else appKey(it)
            }.values.map { records ->
                oppositeIdentity(records.maxWith(compareBy<ConnectionRecord> { it.startedAt }.thenBy { it.id }), query.grouping)
            }.sortedWith(compareBy<ConnectionIdentity> { it.title.orEmpty() }.thenBy { it.key })
            val search = query.search.trim()
            retained = scope.filter { record ->
                query.filter.matches(record) &&
                    (query.oppositeKey == null || (if (query.grouping == ConnectionGrouping.App) configKey(record) else appKey(record)) == query.oppositeKey) &&
                    (query.protocol == ConnectionProtocol.All || record.protocol.equals(query.protocol.name, ignoreCase = true)) &&
                    (query.route == ConnectionRoute.All || when (query.route) {
                        ConnectionRoute.Unknown -> record.route !in listOf("proxy", "direct", "block")
                        else -> record.route.equals(query.route.name, ignoreCase = true)
                    }) &&
                    (query.terminal == ConnectionTerminal.All || record.state.equals(query.terminal.name, ignoreCase = true)) &&
                    (query.period == ConnectionPeriod.All || record.startedAt >= now - query.period.millis) &&
                    (search.isEmpty() || record.domain.contains(search, true) || record.destination.contains(search, true) || record.destinations.any { it.contains(search, true) })
            }.sortedWith(when (query.sort) {
                ConnectionSort.Newest -> compareByDescending<ConnectionRecord> { it.startedAt }.thenByDescending { it.id }
                ConnectionSort.Traffic -> compareByDescending<ConnectionRecord> { it.upload.toDouble() + it.download.toDouble() }.thenByDescending { it.startedAt }.thenBy { it.id }
                ConnectionSort.Duration -> compareByDescending<ConnectionRecord> { ((if (it.state == "active") now else it.endedAt) - it.startedAt).coerceAtLeast(0) }.thenByDescending { it.startedAt }.thenBy { it.id }
            })
            val dateFormat by lazy { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM, locale) }
            flows = retained.map { record ->
                rows[record.id]?.takeIf { it.record == record } ?: ConnectionFlow(
                    record, appIdentity(record), configIdentity(record),
                    record.domain.takeIf { it.isNotBlank() }?.let { domain ->
                        if (record.destination.isBlank()) domain else "$domain · ${record.destination}"
                    } ?: record.destination.ifBlank { record.destinations.firstOrNull().orEmpty() },
                    dateFormat.format(Date(record.startedAt)), connectionBytes(record.upload, locale), connectionBytes(record.download, locale),
                ).also { rows[record.id] = it }
            }
            rows.keys.retainAll(retained.mapTo(HashSet()) { it.id })
        }
        return ConnectionAnalyticsState(
            query, history.status, history.persistOnDisk, history.error, history.counters,
            totals(retained, locale), summaries, flows, opposite, history.connections.any { it.state != "active" },
            history.captureEnabled,
        ).also { lastConnections = history.connections; lastQuery = query; lastState = it }
    }
}

/** Sleep until a visible period boundary or an active duration overtakes a finished flow. */
internal fun nextConnectionRefresh(state: ConnectionAnalyticsState, now: Long): Long? {
    val query = state.query
    if (query.selection == null) return null
    var deadline: Long? = null
    fun consider(value: Long) {
        if (value > now && (deadline == null || value < requireNotNull(deadline))) deadline = value
    }
    if (query.period != ConnectionPeriod.All) {
        state.flows.forEach { consider(it.record.startedAt + query.period.millis + 1) }
    }
    if (query.sort == ConnectionSort.Duration) {
        val finished = state.flows.asSequence().map { it.record }.filter { it.state != "active" }
            .map { (it.endedAt - it.startedAt).coerceAtLeast(0) }.toList().toLongArray().also { it.sort() }
        if (finished.isNotEmpty()) state.flows.forEach { flow ->
            val record = flow.record
            if (record.state == "active") {
                val position = finished.binarySearch((now - record.startedAt).coerceAtLeast(0))
                val next = if (position < 0) -position - 1 else position
                if (next < finished.size) consider(record.startedAt + finished[next] + 1)
            }
        }
    }
    return deadline?.let { (it - now).coerceAtLeast(1) }
}
