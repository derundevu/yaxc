package io.github.derundevu.yaxc.repository

import org.json.JSONArray
import org.json.JSONObject

internal data class ConnectionSnapshot(
    val running: Boolean,
    val epoch: String,
    val dropped: Long,
    val connections: List<ConnectionRecord>,
)

/** Owned exclusively by the repository's IO actor. No Android or current-profile lookups here. */
internal class ConnectionHistoryStore(
    private val maxRecords: Int = 5_000,
    private val maxBytes: Int = 8 * 1024 * 1024,
    private val closedTtlMillis: Long = 24 * 60 * 60 * 1000L,
) {
    private data class Entry(val record: ConnectionRecord, val bytes: Int)

    private val rows = LinkedHashMap<String, Entry>()
    private var epoch: String? = null
    private var allowEpochChange = true
    private var previousNativeDropped = 0L
    private var nativeDropped = 0L
    private var localDropped = 0L
    // Only rejected IDs still present in the latest bounded native snapshot are needed. This
    // prevents the same saturated active row being counted again at every one-second poll.
    private var rejected = emptySet<String>()
    val dropped: Long get() = saturatingAdd(saturatingAdd(previousNativeDropped, nativeDropped), localDropped)
    val records: List<ConnectionRecord>
        get() = rows.values.map { it.record }.sortedWith(
            compareByDescending<ConnectionRecord> { it.startedAt }.thenBy { it.id },
        )
    val nextExpiryAt: Long?
        get() = rows.values.asSequence().map { it.record }.filter { it.endedAt != 0L }
            .minOfOrNull { saturatingAdd(it.endedAt, closedTtlMillis) }
    val hasIncompleteTerminal: Boolean
        get() = rows.values.any { it.record.state != "active" && !it.record.liveBytesExact }

    init {
        require(maxRecords in 1..5_000)
        require(maxBytes in 128..8 * 1024 * 1024)
    }

    fun beginRuntime(now: Long) {
        interruptActive(now)
        allowEpochChange = true
        prune(now)
    }

    fun ingest(
        snapshot: ConnectionSnapshot,
        apps: Map<Int, List<ConnectionApp>>,
        now: Long,
    ) {
        require(epoch == null || epoch == snapshot.epoch || allowEpochChange) { "Unexpected connection epoch" }
        if (epoch != snapshot.epoch) {
            previousNativeDropped = saturatingAdd(previousNativeDropped, nativeDropped)
            nativeDropped = 0
            epoch = snapshot.epoch
            rejected = emptySet()
        }
        allowEpochChange = false
        nativeDropped = maxOf(nativeDropped, snapshot.dropped)
        prune(now)
        val existing = rows.keys.toHashSet()
        val snapshotIds = HashSet<String>(snapshot.connections.size)
        for (incoming in snapshot.connections) {
            snapshotIds += incoming.id
            if (incoming.endedAt != 0L && incoming.endedAt <= now - closedTtlMillis) continue
            val old = rows[incoming.id]?.record
            val record = if (old == null) {
                incoming.copy(
                    apps = apps[incoming.uid].orEmpty().toList(),
                )
            } else {
                // Attribution belongs to the observation, not the package manager, selected
                // profile or a later native snapshot. A route may first arrive after Open.
                val terminal = old.state != "active"
                val staleActive = terminal && incoming.state == "active"
                incoming.copy(
                    protocol = old.protocol,
                    destination = old.destination,
                    domain = if (staleActive) old.domain else incoming.domain,
                    destinations = if (staleActive) old.destinations else incoming.destinations,
                    destinationsTruncated = if (staleActive) old.destinationsTruncated else incoming.destinationsTruncated,
                    liveBytesExact = if (terminal) {
                        old.liveBytesExact || (incoming.state != "active" && incoming.liveBytesExact)
                    } else incoming.liveBytesExact,
                    startedAt = old.startedAt,
                    uid = old.uid,
                    apps = old.apps,
                    profileId = if (old.outboundTag.isEmpty()) incoming.profileId else old.profileId,
                    profileName = if (old.outboundTag.isEmpty()) incoming.profileName else old.profileName,
                    profileFingerprint = if (old.outboundTag.isEmpty()) incoming.profileFingerprint else old.profileFingerprint,
                    outboundTag = old.outboundTag.ifEmpty { incoming.outboundTag },
                    route = if (old.outboundTag.isEmpty()) incoming.route else old.route,
                    state = if (terminal) old.state else incoming.state,
                    endedAt = if (terminal) old.endedAt else incoming.endedAt,
                    upload = maxOf(old.upload, incoming.upload),
                    download = maxOf(old.download, incoming.download),
                )
            }
            rows[record.id] = entry(record)
        }
        enforceBounds(existing, snapshotIds)
    }

    fun interruptActive(now: Long) {
        rows.replaceAll { _, value ->
            if (value.record.state == "active") {
                entry(value.record.copy(state = "interrupted", endedAt = now, liveBytesExact = false))
            } else value
        }
        enforceBounds(rows.keys.toSet(), emptySet())
    }

    fun prune(now: Long): Boolean = rows.entries.removeAll { (_, entry) ->
        entry.record.endedAt != 0L && entry.record.endedAt <= now - closedTtlMillis
    }

    fun clearClosed() {
        rows.entries.removeAll { it.value.record.state != "active" }
    }

    fun nativeHistoryCleared() {
        previousNativeDropped = saturatingAdd(previousNativeDropped, nativeDropped)
        nativeDropped = 0
    }

    private fun envelope(totalDropped: Long, currentNativeDropped: Long): JSONObject = JSONObject().apply {
        put("version", 1)
        put("dropped", totalDropped)
        put("nativeEpoch", epoch.orEmpty())
        put("nativeDropped", currentNativeDropped)
        put("connections", JSONArray())
    }

    fun encode(): String = envelope(dropped, nativeDropped).apply {
        val array = getJSONArray("connections")
        records.forEach { array.put(ConnectionJson.recordJson(it, includeApps = true)) }
    }.toString()

    fun restore(raw: String, now: Long) {
        val json = JSONObject(raw)
        require(json.getInt("version") == 1) { "Unsupported connection history version" }
        val restored = ConnectionJson.records(json.getJSONArray("connections"), includeApps = true)
        rows.clear()
        epoch = json.optString("nativeEpoch").takeIf { it.isNotEmpty() }
        allowEpochChange = true
        val totalDropped = json.optLong("dropped").coerceAtLeast(0)
        nativeDropped = json.optLong("nativeDropped").coerceIn(0, totalDropped)
        previousNativeDropped = totalDropped - nativeDropped
        localDropped = 0
        rejected = emptySet()
        restored.forEach { record ->
            val interrupted = if (record.state == "active") {
                record.copy(state = "interrupted", endedAt = now, liveBytesExact = false)
            } else record
            rows[record.id] = entry(interrupted)
        }
        prune(now)
        enforceBounds(emptySet(), emptySet())
    }

    private fun entry(record: ConnectionRecord) = Entry(
        record,
        ConnectionJson.recordJson(record, includeApps = true).toString().toByteArray(Charsets.UTF_8).size + 1,
    )

    private fun enforceBounds(existing: Set<String>, snapshotIds: Set<String>) {
        // Reserve envelope space, including the maximum-width persisted dropped counter.
        var bytes = envelope(Long.MAX_VALUE, Long.MAX_VALUE).toString().toByteArray(Charsets.UTF_8).size
        val retained = LinkedHashMap<String, Entry>()
        val nextRejected = if (snapshotIds.isEmpty()) rejected.toHashSet() else HashSet<String>()
        val ordered = rows.values.sortedWith(
            compareByDescending<Entry> { it.record.state == "active" }
                .thenByDescending { it.record.state == "active" && it.record.id in existing }
                .thenByDescending { it.record.startedAt }
                .thenBy { it.record.id },
        )
        for (entry in ordered) {
            if (retained.size < maxRecords && entry.bytes <= maxBytes - bytes) {
                retained[entry.record.id] = entry
                bytes += entry.bytes
            } else {
                if (entry.record.id !in rejected) localDropped = saturatingAdd(localDropped, 1)
                if (entry.record.id in snapshotIds) nextRejected += entry.record.id
            }
        }
        rows.clear()
        rows.putAll(retained)
        rejected = nextRejected
    }
}

internal object ConnectionJson {
    // Native history is itself bounded to 5,000 rows. Reject corrupt/unbounded input before
    // parsing; the reader's supplied string is never copied into state or error messages.
    const val MAX_FILE_BYTES = 8 * 1024 * 1024
    private const val MAX_SNAPSHOT_CHARS = 16 * 1024 * 1024
    private val states = setOf("active", "closed", "error", "blocked", "interrupted")

    fun snapshot(raw: String): ConnectionSnapshot {
        require(raw.length <= MAX_SNAPSHOT_CHARS) { "Connection snapshot exceeds limit" }
        val json = JSONObject(raw)
        return ConnectionSnapshot(
            running = json.getBoolean("running"),
            epoch = json.getString("epoch").also { require(it.length <= 256) },
            dropped = json.getLong("dropped").coerceAtLeast(0),
            connections = records(json.getJSONArray("connections"), includeApps = false),
        )
    }

    fun records(array: JSONArray, includeApps: Boolean): List<ConnectionRecord> {
        require(array.length() <= 5_000) { "Connection snapshot exceeds row limit" }
        return List(array.length()) { index ->
            val json = array.getJSONObject(index)
            val state = json.getString("state")
            require(state in states)
            val id = json.getString("id")
            require(id.length in 1..256)
            val destinations = json.optJSONArray("destinations") ?: JSONArray()
            val apps = if (includeApps) json.optJSONArray("apps") ?: JSONArray() else JSONArray()
            ConnectionRecord(
                id = id,
                protocol = json.getString("protocol"),
                destination = json.getString("destination"),
                domain = json.optString("domain"),
                destinations = List(destinations.length()) { destinations.getString(it) },
                destinationsTruncated = json.optBoolean("destinationsTruncated"),
                destinationKind = json.optString("destinationKind", "connection"),
                startedAt = json.getLong("startedAt").coerceAtLeast(0),
                endedAt = json.getLong("endedAt").coerceAtLeast(0),
                state = state,
                upload = json.getLong("upload").coerceAtLeast(0),
                download = json.getLong("download").coerceAtLeast(0),
                liveBytesExact = json.optBoolean("liveBytesExact", true),
                uid = json.optInt("uid", -1),
                profileId = json.optLong("profileId"),
                profileName = json.optString("profileName"),
                profileFingerprint = json.optString("profileFingerprint"),
                outboundTag = json.optString("outboundTag"),
                route = json.optString("route", "unknown"),
                apps = List(apps.length()) {
                    val app = apps.getJSONObject(it)
                    ConnectionApp(app.getString("packageName"), app.getString("name"))
                },
            ).also {
                require((it.state == "active") == (it.endedAt == 0L)) { "Invalid connection end time" }
            }
        }
    }

    fun recordJson(record: ConnectionRecord, includeApps: Boolean = false): JSONObject = JSONObject().apply {
        put("id", record.id)
        put("protocol", record.protocol)
        put("destination", record.destination)
        put("domain", record.domain)
        put("destinations", JSONArray(record.destinations))
        put("destinationsTruncated", record.destinationsTruncated)
        put("destinationKind", record.destinationKind)
        put("startedAt", record.startedAt)
        put("endedAt", record.endedAt)
        put("state", record.state)
        put("upload", record.upload)
        put("download", record.download)
        put("liveBytesExact", record.liveBytesExact)
        put("uid", record.uid)
        put("profileId", record.profileId)
        put("profileName", record.profileName)
        put("profileFingerprint", record.profileFingerprint)
        put("outboundTag", record.outboundTag)
        put("route", record.route)
        if (includeApps) put("apps", JSONArray().apply {
            record.apps.forEach { app -> put(JSONObject().put("packageName", app.packageName).put("name", app.name)) }
        })
    }
}

private fun saturatingAdd(a: Long, b: Long): Long = if (Long.MAX_VALUE - a < b) Long.MAX_VALUE else a + b
