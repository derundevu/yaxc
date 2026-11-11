package io.github.derundevu.yaxc.repository

import org.json.JSONArray
import org.json.JSONObject
import java.util.TreeSet

internal data class ConnectionSnapshot(
    val running: Boolean,
    val epoch: String,
    val nativeEvicted: Long,
    val nativeRejected: Long,
    val connections: List<ConnectionRecord>,
    val generation: Long = 0,
    val enabled: Boolean = true,
    val captureGeneration: Long = 0,
)

/** Owned exclusively by the repository's IO actor. No Android or current-profile lookups here. */
internal class ConnectionHistoryStore(
    private val maxRecords: Int = 5_000,
    private val maxBytes: Int = 8 * 1024 * 1024,
    private val closedTtlMillis: Long = 24 * 60 * 60 * 1000L,
) {
    private class Entry(var record: ConnectionRecord, var bytes: Int, var source: ConnectionRecord?)

    private val rows = LinkedHashMap<String, Entry>()
    // Completion time, never start time. ID makes millisecond ties deterministic across polls/restores.
    private val finished = TreeSet(compareBy<Entry> { it.record.endedAt }.thenBy { it.record.id })
    private var rowBytes = 0
    private var finishedBytes = 0
    private var epoch: String? = null
    private var generation = 0L
    private var captureGeneration = 0L
    private var captureEnabled = true
    private var allowEpochChange = true
    private var legacyBaselinePending = false
    private var nativeEvicted = 0L
    private var nativeRejected = 0L
    private var envelopeBytes = 0
    private var rejected = mutableSetOf<String>()
    private val suppressed = mutableSetOf<String>()
    private var lastTerminalIds = emptySet<String>()
    private var orderedIds: List<String>? = null
    private var cachedRecords: List<ConnectionRecord>? = null
    var revision: Long = 0
        private set
    var counters = ConnectionHistoryCounters()
        private set
    val records: List<ConnectionRecord>
        get() = cachedRecords ?: run {
            val ids = orderedIds ?: rows.values.sortedWith(
                compareByDescending<Entry> { it.record.startedAt }.thenBy { it.record.id },
            ).map { it.record.id }.also { orderedIds = it }
            ids.map { rows.getValue(it).record }.also { cachedRecords = it }
        }
    val nextExpiryAt: Long?
        get() = finished.firstOrNull()?.record?.endedAt?.let { saturatingAdd(it, closedTtlMillis) }
    val hasIncompleteTerminal: Boolean
        get() = finished.any { !it.record.liveBytesExact }

    init {
        require(maxRecords in 1..5_000)
        require(maxBytes in 128..8 * 1024 * 1024)
        reserveEnvelope()
    }

    fun beginRuntime(now: Long) {
        interruptActive(now)
        allowEpochChange = true
        prune(now)
    }

    /** False means a pre-clear snapshot; callers must not publish its lifecycle state either. */
    fun ingest(snapshot: ConnectionSnapshot, apps: Map<Int, List<ConnectionApp>>, now: Long): Boolean {
        require(epoch == null || epoch == snapshot.epoch || allowEpochChange) { "Unexpected connection epoch" }
        if (epoch == snapshot.epoch && snapshot.generation < generation) return false
        if (epoch == snapshot.epoch && snapshot.captureGeneration < captureGeneration) return false
        if (epoch != snapshot.epoch) {
            epoch = snapshot.epoch
            generation = snapshot.generation
            revision++
            legacyBaselinePending = false
            nativeEvicted = 0
            nativeRejected = 0
            rejected.clear()
            suppressed.clear()
            reserveEnvelope()
        } else if (snapshot.generation > generation) {
            clearClosed()
            generation = snapshot.generation
            nativeEvicted = 0
            nativeRejected = 0
        }
        if (snapshot.captureGeneration != captureGeneration || snapshot.enabled != captureEnabled) {
            captureGeneration = snapshot.captureGeneration
            captureEnabled = snapshot.enabled
            revision++
        }
        enforceEnvelope()
        allowEpochChange = false
        if (legacyBaselinePending) {
            nativeEvicted = snapshot.nativeEvicted
            nativeRejected = snapshot.nativeRejected
            legacyBaselinePending = false
        }
        val evictedDelta = (snapshot.nativeEvicted - nativeEvicted).coerceAtLeast(0)
        val rejectedDelta = (snapshot.nativeRejected - nativeRejected).coerceAtLeast(0)
        if (evictedDelta != 0L || rejectedDelta != 0L) updateCounters(counters.copy(
            nativeEvicted = saturatingAdd(counters.nativeEvicted, evictedDelta),
            nativeRejected = saturatingAdd(counters.nativeRejected, rejectedDelta),
        ))
        nativeEvicted = maxOf(nativeEvicted, snapshot.nativeEvicted)
        nativeRejected = maxOf(nativeRejected, snapshot.nativeRejected)
        prune(now)
        val snapshotIds = snapshot.connections.mapTo(HashSet(snapshot.connections.size)) { it.id }
        rejected.retainAll(snapshotIds)
        suppressed.retainAll(snapshotIds)
        var discardedUpdates: MutableSet<String>? = null
        lastTerminalIds = snapshot.connections.asSequence().filter { it.state != "active" }.map { it.id }.toSet()
        // Terminal transitions must land before any retained active row grows: near the byte
        // bound a later completion can provide the FIFO victim needed to keep that active row.
        for (pass in 0..1) {
            for (incoming in snapshot.connections) {
                if ((incoming.state == "active") != (pass == 1)) continue
                val old = rows[incoming.id] ?: continue
                if (old.source == incoming) continue
                update(incoming, old)
                if (incoming.id !in rows) {
                    if (discardedUpdates == null) discardedUpdates = mutableSetOf()
                    discardedUpdates.add(incoming.id)
                }
            }
        }
        for (pass in 0..1) {
            for (incoming in snapshot.connections) {
                if ((incoming.state == "active") != (pass == 0) || incoming.id in rows || incoming.id in suppressed ||
                    discardedUpdates?.contains(incoming.id) == true) continue
                if (incoming.endedAt != 0L && incoming.endedAt <= now - closedTtlMillis) continue
                if (rows.size == maxRecords && finished.isEmpty()) {
                    reject(incoming)
                    continue
                }
                val capturedApps = apps[incoming.uid].orEmpty()
                val record = if (capturedApps.isEmpty()) incoming else incoming.copy(apps = capturedApps)
                admit(entry(record, incoming), wasRetained = false)
            }
        }
        enforceEnvelope()
        if (!snapshot.enabled) freezeCapture(now)
        return true
    }

    private fun update(incoming: ConnectionRecord, old: Entry) {
        val record = merge(old.record, incoming)
        if (record == old.record) {
            old.source = incoming
            return
        }
        val bytes = if (sameMetadata(old.record, record)) {
            old.bytes + decimalLength(record.upload) - decimalLength(old.record.upload) +
                decimalLength(record.download) - decimalLength(old.record.download) +
                (if (record.liveBytesExact) 4 else 5) - (if (old.record.liveBytesExact) 4 else 5)
        } else encodedBytes(record)
        if (rowBytes - old.bytes + bytes <= maxBytes - envelopeBytes) {
            val becameFinished = old.record.state == "active" && record.state != "active"
            rowBytes += bytes - old.bytes
            if (old.record.state != "active") finishedBytes += bytes - old.bytes
            old.record = record
            old.bytes = bytes
            old.source = incoming
            // A terminal row's completion time/ID never changes, so byte credits do not
            // need to remove/reinsert its FIFO key or allocate another internal entry.
            if (becameFinished) {
                finishedBytes += bytes
                finished.add(old)
            }
            changed(structure = false)
        } else {
            remove(old.record.id)
            admit(Entry(record, bytes, incoming), wasRetained = true)
        }
    }

    private fun merge(old: ConnectionRecord, incoming: ConnectionRecord): ConnectionRecord {
        if (old.captureStopped || (old.state != "active" && incoming.state == "active")) return old
        val terminal = old.state != "active"
        return incoming.copy(
            protocol = old.protocol,
            destination = old.destination,
            liveBytesExact = if (terminal) old.liveBytesExact || (incoming.state != "active" && incoming.liveBytesExact) else incoming.liveBytesExact,
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

    fun interruptActive(now: Long) {
        // Copy only the IDs being mutated, not every record or its encoded representation.
        val activeIds = rows.values.asSequence().filter { it.record.state == "active" }.map { it.record.id }.toList()
        for (id in activeIds) {
            val old = checkNotNull(remove(id))
            admit(entry(old.record.copy(state = "interrupted", endedAt = now, liveBytesExact = false)), wasRetained = true)
        }
    }

    fun captureChanged(enabled: Boolean) {
        if (captureEnabled == enabled) return
        captureEnabled = enabled
        captureGeneration = saturatingAdd(captureGeneration, 1)
        revision++
    }

    /** Preserve the cutoff even when a later retained native frame contains this ID. */
    fun freezeCapture(now: Long) {
        interruptActive(now)
        val ids = rows.values.filter { !it.record.captureStopped }.map { it.record.id }
        for (id in ids) {
            val old = checkNotNull(remove(id))
            admit(entry(old.record.copy(captureStopped = true)), wasRetained = true)
        }
    }

    fun prune(now: Long): Boolean {
        var changed = false
        while (true) {
            val first = finished.firstOrNull() ?: break
            if (first.record.endedAt > now - closedTtlMillis) break
            suppressed += first.record.id
            remove(first.record.id)
            changed = true
        }
        return changed
    }

    /** Clear resets event metrics, not live flow byte totals. Keep native baselines on failure. */
    fun clearClosed() {
        suppressed += lastTerminalIds
        while (finished.isNotEmpty()) {
            val id = finished.first().record.id
            suppressed += id
            remove(id)
        }
        rejected.clear()
        updateCounters(ConnectionHistoryCounters())
    }

    fun nativeHistoryCleared() {
        generation = saturatingAdd(generation, 1)
        nativeEvicted = 0
        nativeRejected = 0
        legacyBaselinePending = false
        revision++
    }

    private fun admit(candidate: Entry, wasRetained: Boolean) {
        val id = candidate.record.id
        // First prove admission is possible without displacing any active row. Otherwise
        // a too-large candidate could pointlessly evict useful finished rows and still fail.
        val budget = maxBytes - envelopeBytes
        val activeBytes = rowBytes - finishedBytes
        if (rows.size - finished.size >= maxRecords || candidate.bytes > budget - activeBytes) {
            if (wasRetained && candidate.record.state != "active") {
                suppressed += id
                updateCounters(counters.copy(historyEvicted = saturatingAdd(counters.historyEvicted, 1)))
            } else reject(candidate.record)
            return
        }
        // Any removal after this feasibility check is ordinary completion-FIFO rotation,
        // including a newly seen terminal row already older than the retained window.
        rows[id] = candidate
        rowBytes += candidate.bytes
        if (candidate.record.state != "active") {
            finished += candidate
            finishedBytes += candidate.bytes
        }
        changed(structure = true)
        while (rows.size > maxRecords || rowBytes > maxBytes - envelopeBytes) {
            val victim = finished.first()
            remove(victim.record.id)
            suppressed += victim.record.id
            updateCounters(counters.copy(historyEvicted = saturatingAdd(counters.historyEvicted, 1)))
        }
    }

    private fun reject(record: ConnectionRecord) {
        if (rejected.add(record.id)) {
            updateCounters(counters.copy(historyRejected = saturatingAdd(counters.historyRejected, 1)))
        }
        if (record.state != "active") suppressed += record.id
    }

    private fun remove(id: String): Entry? {
        val old = rows.remove(id) ?: return null
        rowBytes -= old.bytes
        if (old.record.state != "active") {
            finished.remove(old)
            finishedBytes -= old.bytes
        }
        changed(structure = true)
        return old
    }

    private fun enforceEnvelope() {
        while (rows.isNotEmpty() && rowBytes > maxBytes - envelopeBytes) {
            val victim = finished.firstOrNull() ?: rows.values.last()
            remove(victim.record.id)
            if (victim.record.state == "active") reject(victim.record) else {
                suppressed += victim.record.id
                updateCounters(counters.copy(historyEvicted = saturatingAdd(counters.historyEvicted, 1)))
            }
        }
    }

    private fun changed(structure: Boolean) {
        revision++
        cachedRecords = null
        if (structure) orderedIds = null
    }

    private fun updateCounters(value: ConnectionHistoryCounters) {
        if (value != counters) {
            counters = value
            revision++
        }
    }

    private fun envelope(reserve: Boolean = false): JSONObject = JSONObject().apply {
        put("version", 3)
        put("counters", ConnectionJson.countersJson(if (reserve) ConnectionHistoryCounters(
            Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE,
        ) else counters))
        put("nativeEpoch", epoch.orEmpty())
        put("nativeGeneration", if (reserve) Long.MAX_VALUE else generation)
        put("captureGeneration", if (reserve) Long.MAX_VALUE else captureGeneration)
        put("captureEnabled", if (reserve) false else captureEnabled)
        put("nativeBaseline", JSONObject().put("nativeEvicted", if (reserve) Long.MAX_VALUE else nativeEvicted)
            .put("nativeRejected", if (reserve) Long.MAX_VALUE else nativeRejected))
        put("connections", JSONArray())
    }

    private fun reserveEnvelope() {
        envelopeBytes = envelope(reserve = true).toString().toByteArray(Charsets.UTF_8).size
    }

    fun encode(): String = envelope().apply {
        val array = getJSONArray("connections")
        records.forEach { array.put(ConnectionJson.recordJson(it, includeApps = true)) }
    }.toString()

    fun restore(raw: String, now: Long) {
        val json = JSONObject(raw)
        val version = json.getInt("version")
        require(version in 1..3) { "Unsupported connection history version" }
        val restored = ConnectionJson.records(json.getJSONArray("connections"), includeApps = true)
        rows.clear()
        finished.clear()
        rowBytes = 0
        finishedBytes = 0
        changed(structure = true)
        epoch = json.optString("nativeEpoch").takeIf { it.isNotEmpty() }
        generation = if (version >= 2) json.getLong("nativeGeneration").coerceAtLeast(0) else 0
        captureGeneration = if (version >= 3) json.getLong("captureGeneration").also { require(it >= 0) } else 0
        captureEnabled = if (version >= 3) json.getBoolean("captureEnabled") else true
        allowEpochChange = true
        legacyBaselinePending = version == 1 && epoch != null
        counters = if (version >= 2) ConnectionJson.counters(json.getJSONObject("counters")) else {
            // The old aggregate mixed native eviction/refusal and local rotation/refusal.
            ConnectionHistoryCounters(unclassified = json.optLong("dropped").coerceAtLeast(0))
        }
        val baseline = json.optJSONObject("nativeBaseline")
        nativeEvicted = baseline?.optLong("nativeEvicted")?.coerceAtLeast(0) ?: 0
        nativeRejected = baseline?.optLong("nativeRejected")?.coerceAtLeast(0) ?: 0
        rejected.clear()
        suppressed.clear()
        lastTerminalIds = emptySet()
        reserveEnvelope()
        for (record in restored) {
            val interrupted = if (record.state == "active") record.copy(state = "interrupted", endedAt = now, liveBytesExact = false) else record
            if (interrupted.endedAt > now - closedTtlMillis) admit(entry(interrupted), wasRetained = false)
        }
    }

    private fun entry(record: ConnectionRecord, source: ConnectionRecord? = null) = Entry(record, encodedBytes(record), source)
    private fun encodedBytes(record: ConnectionRecord) =
        ConnectionJson.recordJson(record, includeApps = true).toString().toByteArray(Charsets.UTF_8).size + 1

    private fun sameMetadata(a: ConnectionRecord, b: ConnectionRecord): Boolean =
        a.id == b.id && a.protocol == b.protocol && a.destination == b.destination && a.domain == b.domain &&
            a.destinations == b.destinations && a.destinationsTruncated == b.destinationsTruncated &&
            a.destinationKind == b.destinationKind && a.startedAt == b.startedAt && a.endedAt == b.endedAt &&
            a.state == b.state && a.uid == b.uid && a.profileId == b.profileId && a.profileName == b.profileName &&
            a.profileFingerprint == b.profileFingerprint && a.outboundTag == b.outboundTag && a.route == b.route && a.apps == b.apps
}

internal object ConnectionJson {
    const val MAX_FILE_BYTES = 8 * 1024 * 1024
    private const val MAX_SNAPSHOT_CHARS = 16 * 1024 * 1024
    private val states = setOf("active", "closed", "error", "blocked", "interrupted")

    fun snapshot(raw: String): ConnectionSnapshot {
        require(raw.length <= MAX_SNAPSHOT_CHARS) { "Connection snapshot exceeds limit" }
        val json = JSONObject(raw)
        return ConnectionSnapshot(
            running = json.getBoolean("running"),
            epoch = json.getString("epoch").also { require(it.length <= 256) },
            nativeEvicted = json.getLong("nativeEvicted").coerceAtLeast(0),
            nativeRejected = json.getLong("nativeRejected").coerceAtLeast(0),
            connections = records(json.getJSONArray("connections"), includeApps = false),
            generation = json.getLong("generation").coerceAtLeast(0),
            enabled = json.getBoolean("enabled"),
            captureGeneration = json.getLong("captureGeneration").also { require(it >= 0) },
        )
    }

    fun counters(json: JSONObject) = ConnectionHistoryCounters(
        nativeEvicted = json.getLong("nativeEvicted").coerceAtLeast(0),
        nativeRejected = json.getLong("nativeRejected").coerceAtLeast(0),
        historyEvicted = json.getLong("historyEvicted").coerceAtLeast(0),
        historyRejected = json.getLong("historyRejected").coerceAtLeast(0),
        unclassified = json.getLong("unclassified").coerceAtLeast(0),
    )

    fun countersJson(value: ConnectionHistoryCounters): JSONObject = JSONObject()
        .put("nativeEvicted", value.nativeEvicted).put("nativeRejected", value.nativeRejected)
        .put("historyEvicted", value.historyEvicted).put("historyRejected", value.historyRejected)
        .put("unclassified", value.unclassified)

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
                captureStopped = includeApps && json.optBoolean("captureStopped"),
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
        if (includeApps) put("captureStopped", record.captureStopped)
        if (includeApps) put("apps", JSONArray().apply {
            record.apps.forEach { app -> put(JSONObject().put("packageName", app.packageName).put("name", app.name)) }
        })
    }
}

private fun saturatingAdd(a: Long, b: Long): Long = if (Long.MAX_VALUE - a < b) Long.MAX_VALUE else a + b
private fun decimalLength(value: Long): Int {
    var remaining = value
    var length = 1
    while (remaining >= 10) {
        remaining /= 10
        length++
    }
    return length
}
