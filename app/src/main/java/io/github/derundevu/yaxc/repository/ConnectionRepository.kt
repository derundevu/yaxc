package io.github.derundevu.yaxc.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.AtomicFile
import io.github.derundevu.yaxc.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Process-owned, opt-in-on-disk connection metadata. Creating this object never reads the core;
 * only the service's actual runtime lifecycle starts a collector. All mutations and file/package
 * work are serialized by an IO actor, independent of whether an analytics screen is visible.
 */
class ConnectionRepository(
    context: Context,
    private val settings: Settings,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
    packageLabel: ((String) -> String)? = null,
    historyFile: File? = null,
    parentScope: CoroutineScope? = null,
) {
    private sealed interface Command {
        data class Start(
            val token: Long,
            val packages: Map<String, Int>,
            val reader: () -> String,
            val clear: () -> Unit,
        ) : Command
        data class End(val snapshot: String?) : Command
        data object Unsupported : Command
        data object Clear : Command
        data class Packages(val packages: Map<String, Int>, val precedingSnapshot: String?) : Command
        data class UncertainUid(val uid: Int) : Command
        data class Persistence(val enabled: Boolean, val updateSetting: Boolean) : Command
        data class Capture(val enabled: Boolean, val updateSetting: Boolean) : Command
        data class Poll(
            val token: Long,
            val revision: Long,
            val raw: String?,
            val failed: Boolean,
            val tail: Boolean = false,
            val consumed: CompletableDeferred<Unit> = CompletableDeferred(),
        ) : Command
        data object Flush : Command
        data class Maintenance(val expiry: Long) : Command
        data class TailFinished(val token: Long) : Command
    }

    private class Runtime(
        val token: Long,
        val reader: () -> String,
        val clear: () -> Unit,
        var apps: Map<Int, List<ConnectionApp>>,
    ) {
        val nativeLock = Any()
        @Volatile var revision = 0L // Mutated under nativeLock; actor reads must never wait on a reader.
        var lastSnapshot: String? = null
        var lastHistoryRevision = -1L
        var lastRunning = false
        var awaitingClearBoundary = false
        var captureEnabled: Boolean? = null
        var captureGeneration: Long? = null
        var pump: Job? = null
    }

    private val appContext = context.applicationContext
    private val label = packageLabel ?: { name: String ->
        val pm = appContext.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(name, 0)).toString()
    }
    private val scope = CoroutineScope(
        (parentScope?.coroutineContext ?: EmptyCoroutineContext) +
            SupervisorJob(parentScope?.coroutineContext?.get(Job)) + dispatcher,
    )
    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val enqueueLock = Any()
    // Serializes native startup admission with completed hot toggles, including before Start is consumed.
    private val captureControlLock = Any()
    private var nativeCapture: ((Boolean) -> String)? = null
    private var nextToken = 0L
    private val history = ConnectionHistoryStore()
    private val file by lazy(LazyThreadSafetyMode.NONE) {
        AtomicFile(historyFile ?: File(appContext.noBackupFilesDir, "connection-history.json"))
    }
    private val mutableState = MutableStateFlow(ConnectionHistoryState())
    val state: StateFlow<ConnectionHistoryState> = mutableState.asStateFlow()

    // Everything below, except the listener and public command enqueueing, belongs to the actor.
    private var runtime: Runtime? = null
    private var tailRuntime: Runtime? = null
    private var nativeClear: (() -> Unit)? = null
    private val uncertainUids = mutableSetOf<Int>()
    private var lastPackageSets: Map<Int, Set<String>> = emptyMap()
    private var status = ConnectionCaptureStatus.Stopped
    private var idleStatus = ConnectionCaptureStatus.Stopped
    private var persistence = false
    private var captureEnabled = settings.connectionAnalyticsEnabled
    private var captureSettingError: String? = null
    private var captureToggleError: String? = null
    private var captureError: String? = null
    private var historyClearError: String? = null
    private var storageError: String? = null
    private var attributionError: String? = null
    private var dirty = false
    private var lastFlush: Long? = null
    private var scheduledFlush: Job? = null
    private var scheduledRetention: Job? = null
    private var scheduledExpiry: Long? = null
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key == "connectionHistoryOnDisk") {
            enqueue(Command.Persistence(settings.connectionHistoryOnDisk, updateSetting = false))
        }
        if (key == null || key == "connectionAnalyticsEnabled") {
            enqueue(Command.Capture(settings.connectionAnalyticsEnabled, updateSetting = false))
        }
    }

    init {
        settings.registerChangeListener(listener)
        scope.launch {
            initialize()
            for (command in commands) {
                try {
                    handle(command)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Analytics must never terminate forwarding or expose raw native/config errors.
                    captureError = "Connection analytics could not update."
                    status = ConnectionCaptureStatus.Error
                    publish()
                }
            }
        }
    }

    /**
     * The real service boundary registers control before native Start can admit connections.
     * Reading the preference, applying it, and starting the core share the hot-toggle gate.
     */
    internal fun startNativeRuntime(setCaptureEnabled: (Boolean) -> String, startCore: () -> Unit) {
        synchronized(captureControlLock) {
            nativeCapture = setCaptureEnabled
            val diagnostic = setCaptureEnabled(preferredCaptureEnabled())
            check(diagnostic.isEmpty()) { diagnostic }
            startCore()
        }
    }

    private fun preferredCaptureEnabled(): Boolean =
        if (captureSettingError != null) captureEnabled else settings.connectionAnalyticsEnabled

    fun startRuntime(
        packages: Map<String, Int>,
        reader: () -> String,
        clearHistory: () -> Unit,
        setCaptureEnabled: (Boolean) -> String,
    ) {
        synchronized(captureControlLock) {
            nativeCapture = setCaptureEnabled
            synchronized(enqueueLock) {
                commands.trySend(Command.Start(++nextToken, packages.toMap(), reader, clearHistory))
            }
        }
    }

    fun updatePackages(packages: Map<String, Int>, precedingSnapshot: String? = null) =
        enqueue(Command.Packages(packages.toMap(), precedingSnapshot))

    /** A TUN admission saw a changed shared UID before its SOCKS flow could be observed. */
    fun invalidateUid(uid: Int) = enqueue(Command.UncertainUid(uid))

    fun endRuntime(finalSnapshot: String?) = enqueue(Command.End(finalSnapshot))

    fun unsupportedRuntime() = enqueue(Command.Unsupported)

    /** Closed metadata only: never stop a flow, reset its byte counters, or clear other app data. */
    fun clearHistory() = enqueue(Command.Clear)

    fun setPersistence(enabled: Boolean) = enqueue(Command.Persistence(enabled, updateSetting = true))

    fun setCaptureEnabled(enabled: Boolean) = enqueue(Command.Capture(enabled, updateSetting = true))

    private fun enqueue(command: Command) {
        synchronized(enqueueLock) { commands.trySend(command) }
    }

    private fun initialize() {
        persistence = settings.connectionHistoryOnDisk
        captureEnabled = settings.connectionAnalyticsEnabled
        if (!captureEnabled) status = ConnectionCaptureStatus.Disabled
        if (persistence) {
            try {
                if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) {
                    file.openRead().use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            require(output.size() <= ConnectionJson.MAX_FILE_BYTES - count)
                            output.write(buffer, 0, count)
                        }
                        history.restore(output.toString(Charsets.UTF_8.name()), clock())
                    }
                    dirty = true
                    flush(force = true) // Persist the interrupted state, not active rows again.
                }
            } catch (_: Exception) {
                storageError = "Saved connection history could not be read."
            }
        } else {
            deleteFile()
        }
        publish()
    }

    private fun handle(command: Command) {
        when (command) {
            is Command.Start -> synchronized(captureControlLock) { start(command) }
            is Command.End -> stop(command.snapshot, ConnectionCaptureStatus.Stopped)
            Command.Unsupported -> stop(null, ConnectionCaptureStatus.Unsupported)
            is Command.TailFinished -> if (tailRuntime?.token == command.token) tailRuntime = null
            is Command.Packages -> {
                runtime?.let { active ->
                    synchronized(active.nativeLock) { active.revision++ }
                    if (captureEnabled) command.precedingSnapshot?.let { ingest(it, active) }
                    active.apps = captureApps(command.packages)
                }
                publish()
            }
            is Command.UncertainUid -> {
                uncertainUids += command.uid
                runtime?.let { it.apps = it.apps + (command.uid to emptyList()) }
                tailRuntime?.let { it.apps = it.apps + (command.uid to emptyList()) }
            }
            Command.Clear -> clear()
            is Command.Persistence -> persistence(command)
            is Command.Capture -> synchronized(captureControlLock) { capture(command) }
            is Command.Poll -> try {
                poll(command)
            } finally {
                command.consumed.complete(Unit)
            }
            Command.Flush -> {
                scheduledFlush = null
                flush(force = false)
                publish()
            }
            is Command.Maintenance -> if (command.expiry == scheduledExpiry) {
                scheduledRetention = null
                scheduledExpiry = null
                dirty = history.prune(clock()) || dirty
                flush(force = false)
                publish()
            }
        }
    }

    private fun resolveApps(packages: Map<String, Int>): Map<Int, List<ConnectionApp>> {
        attributionError = null
        return packages.entries.filter { it.value >= 0 }.sortedBy { it.key }.groupBy(
            keySelector = { it.value },
            valueTransform = { (name, _) ->
                val capturedLabel = try {
                    label(name)
                } catch (_: Exception) {
                    attributionError = "Some application names could not be read."
                    name
                }
                ConnectionApp(name, capturedLabel)
            },
        ).mapValues { it.value.toList() }
    }

    private fun captureApps(packages: Map<String, Int>): Map<Int, List<ConnectionApp>> {
        val resolved = resolveApps(packages)
        if (packages.isNotEmpty()) {
            val next = resolved.mapValues { (_, apps) -> apps.mapTo(mutableSetOf()) { it.packageName } }
            for ((uid, previous) in lastPackageSets) {
                if (next[uid] != previous) uncertainUids += uid
            }
            lastPackageSets = next
        }
        // Native history spans core reloads, and a TUN session may still have an old
        // authenticated association. Do not lend an ambiguous UID to a replacement
        // package again in this process; already captured historical rows stay intact.
        return resolved.mapValues { (uid, apps) -> if (uid in uncertainUids) emptyList() else apps } +
            uncertainUids.associateWith { emptyList() }
    }

    private fun start(command: Command.Start) {
        runtime?.pump?.cancel()
        stopTail()
        nativeClear = command.clear
        history.beginRuntime(clock())
        captureError = null
        attributionError = null
        val apps = captureApps(command.packages)
        val active = Runtime(command.token, command.reader, command.clear, apps)
        runtime = active
        synchronizeCapture(active, preferredCaptureEnabled())
        dirty = true
        flush(force = false)
        publish()
        if (active.captureEnabled == true) startPump(active)
    }

    private fun startPump(active: Runtime) {
        active.pump = scope.launch {
            while (isActive) {
                val result = read(active, tail = false)
                currentCoroutineContext().ensureActive()
                commands.send(result)
                result.consumed.await() // At most one pending snapshot per runtime, even on slow storage.
                delay(1_000)
            }
        }
    }

    private suspend fun read(active: Runtime, tail: Boolean): Command.Poll {
        val coroutine = currentCoroutineContext()
        return synchronized(active.nativeLock) {
            coroutine.ensureActive() // Cancellation while waiting for a previous read must not start another call.
            val revision = active.revision
            try {
                Command.Poll(active.token, revision, active.reader(), failed = false, tail = tail)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Command.Poll(active.token, revision, null, failed = true, tail = tail)
            }
        }
    }

    private fun stopTail() {
        tailRuntime?.pump?.cancel()
        tailRuntime = null
    }

    private fun poll(command: Command.Poll) {
        val active = (if (command.tail) tailRuntime else runtime) ?: return
        if (active.token != command.token) return
        if (active.revision != command.revision) return
        if (!captureEnabled || active.captureEnabled != true) return
        if (command.failed) {
            captureError = "Connection snapshot could not be read."
            if (!command.tail) status = ConnectionCaptureStatus.Error
        } else {
            ingest(checkNotNull(command.raw), active, fromReader = true)
        }
        if (command.tail) {
            status = ConnectionCaptureStatus.Stopped
            if (!history.hasIncompleteTerminal) stopTail()
        }
        flush(force = command.tail && tailRuntime == null)
        publish()
    }

    private fun ingest(raw: String, active: Runtime, fromReader: Boolean = false) {
        if (active.awaitingClearBoundary && !fromReader) return
        val before = history.revision
        try {
            if (active.lastSnapshot != raw || active.lastHistoryRevision != before) {
                val snapshot = ConnectionJson.snapshot(raw)
                if (snapshot.enabled != captureEnabled) return
                if (active.captureGeneration?.let { snapshot.captureGeneration < it } == true) return
                if (!history.ingest(snapshot, active.apps, clock())) return
                if (!snapshot.running) history.interruptActive(clock())
                active.lastSnapshot = raw
                active.lastHistoryRevision = history.revision
                active.lastRunning = snapshot.running
                active.awaitingClearBoundary = false
                active.captureGeneration = snapshot.captureGeneration
            }
            status = if (captureToggleError != null) ConnectionCaptureStatus.Error
                else if (!captureEnabled) ConnectionCaptureStatus.Disabled
                else if (active.lastRunning) ConnectionCaptureStatus.Running else ConnectionCaptureStatus.Stopped
            captureError = null
        } catch (_: Exception) {
            captureError = "Connection snapshot is invalid or belongs to another runtime."
            status = ConnectionCaptureStatus.Error
        }
        dirty = history.revision != before || dirty
    }

    private fun stop(finalSnapshot: String?, stoppedStatus: ConnectionCaptureStatus) {
        val active = runtime
        stopTail()
        active?.pump?.cancel()
        runtime = null // Late polls from this reader cannot overwrite this or a subsequent start.
        idleStatus = stoppedStatus
        if (captureEnabled && finalSnapshot != null && active != null) ingest(finalSnapshot, active)
        history.interruptActive(clock())
        history.prune(clock())
        status = if (captureEnabled) stoppedStatus else ConnectionCaptureStatus.Disabled
        dirty = true
        flush(force = true)
        publish()
        if (captureEnabled && active?.captureEnabled == true && stoppedStatus == ConnectionCaptureStatus.Stopped && history.hasIncompleteTerminal) {
            startTail(active)
        }
    }

    private fun startTail(active: Runtime) {
        tailRuntime = active
        active.pump = scope.launch {
            withTimeoutOrNull(5_000) {
                repeat(5) {
                    delay(1_000)
                    val result = read(active, tail = true)
                    currentCoroutineContext().ensureActive()
                    commands.send(result)
                    result.consumed.await()
                }
            }
            commands.send(Command.TailFinished(active.token))
        }
    }

    private fun clear() {
        val active = runtime ?: tailRuntime
        stopTail()
        history.clearClosed()
        if (active != null) {
            // Serialize clear with reads. A read that completed before clear retains its older
            // revision and is rejected even if its actor command arrives after clear.
            synchronized(active.nativeLock) {
                active.revision++
                try {
                    val requiresBoundary = active.lastSnapshot == null
                    active.clear()
                    history.nativeHistoryCleared()
                    historyClearError = null
                    active.lastSnapshot = null
                    active.awaitingClearBoundary = requiresBoundary
                    // Clear can win the actor queue before the first poll. Establish its
                    // native epoch/generation now so even a late final snapshot is fenced.
                    if (requiresBoundary && captureEnabled) ingest(active.reader(), active, fromReader = true)
                } catch (_: Exception) {
                    historyClearError = "Native closed connection history could not be cleared."
                }
            }
        }
        if (active == null && nativeClear != null) {
            try {
                nativeClear?.invoke()
                history.nativeHistoryCleared()
                historyClearError = null
            } catch (_: Exception) {
                historyClearError = "Native closed connection history could not be cleared."
            }
        }
        dirty = true
        flush(force = true)
        publish()
    }

    /** Native cutover precedes persistence; a failed native operation never claims success. */
    private fun synchronizeCapture(active: Runtime?, enabled: Boolean): Boolean {
        val previous = captureEnabled
        val callback = nativeCapture
        try {
            check(callback?.invoke(enabled).orEmpty().isEmpty())
        } catch (_: Exception) {
            captureToggleError = "Connection analytics capture could not be changed."
            status = ConnectionCaptureStatus.Error
            return false
        }
        captureEnabled = enabled
        captureError = null
        captureToggleError = null
        if (previous != enabled) history.captureChanged(enabled)
        if (active != null) {
            val wasCapturing = active.captureEnabled == true
            val transitioned = active.captureEnabled != null && active.captureEnabled != enabled
            if (transitioned) active.captureGeneration = (active.captureGeneration ?: 0) + 1
            active.captureEnabled = enabled
            active.lastSnapshot = null
            if (!enabled && previous) {
                if (wasCapturing) {
                    // One quiescent native cutoff, never a periodic disabled reader.
                    try {
                        ingest(active.reader(), active, fromReader = true)
                        if (active.lastSnapshot == null) {
                            captureError = "Connection cutoff snapshot is stale or invalid."
                            status = ConnectionCaptureStatus.Error
                        }
                    } catch (_: Exception) {
                        captureError = "Connection cutoff snapshot could not be read."
                        status = ConnectionCaptureStatus.Error
                    }
                }
                history.freezeCapture(clock())
                dirty = true
            }
        }
        if (enabled || captureError == null) {
            captureError = null
            status = if (!enabled) ConnectionCaptureStatus.Disabled
                else if (active != null && runtime === active) ConnectionCaptureStatus.Running
                else idleStatus
        }
        return true
    }

    private fun capture(command: Command.Capture) {
        if (!command.updateSetting && settings.connectionAnalyticsEnabled != command.enabled) return
        val active = runtime ?: tailRuntime
        val wasTail = tailRuntime === active && active != null
        val needsToggle = captureEnabled != command.enabled || active?.captureEnabled?.let { it != command.enabled } == true ||
            (active != null && active.captureEnabled == null)
        if (needsToggle) {
            active?.pump?.cancel()
            stopTail()
            val succeeded = if (active == null) {
                synchronizeCapture(null, command.enabled)
            } else synchronized(active.nativeLock) {
                active.revision++
                synchronizeCapture(active, command.enabled)
            }
            if (!succeeded) {
                if (!command.updateSetting) {
                    try {
                        settings.connectionAnalyticsEnabled = captureEnabled
                    } catch (_: Exception) {
                        captureSettingError = "Connection analytics preference could not be saved."
                    }
                }
                if (active?.captureEnabled == true) {
                    if (runtime === active) startPump(active)
                    else if (wasTail) startTail(active)
                }
                publish()
                return
            }
            if (!captureEnabled) {
                history.freezeCapture(clock())
                dirty = true
                flush(force = true)
            } else if (active != null && runtime === active) {
                startPump(active)
            }
        }
        if (command.updateSetting || settings.connectionAnalyticsEnabled != captureEnabled) {
            try {
                settings.connectionAnalyticsEnabled = captureEnabled
                captureSettingError = null
            } catch (_: Exception) {
                captureSettingError = "Connection analytics preference could not be saved."
            }
        }
        publish()
    }

    private fun persistence(command: Command.Persistence) {
        if (command.updateSetting) settings.connectionHistoryOnDisk = command.enabled
        if (persistence == command.enabled) {
            if (!persistence) deleteFile()
            publish()
            return
        }
        persistence = command.enabled
        scheduledFlush?.cancel()
        scheduledFlush = null
        if (persistence) {
            dirty = true
            flush(force = true)
        } else {
            deleteFile()
        }
        publish()
    }

    private fun deleteFile() {
        try {
            file.delete()
            check(!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists() &&
                !File(file.baseFile.path + ".new").exists())
            storageError = null
        } catch (_: Exception) {
            storageError = "Saved connection history could not be deleted."
        }
    }

    private fun flush(force: Boolean) {
        if (!persistence || !dirty) return
        val now = clock()
        val elapsed = lastFlush?.let { (now - it).coerceAtLeast(0) } ?: 5_000L
        if (!force && elapsed < 5_000) {
            if (scheduledFlush == null) scheduledFlush = scope.launch {
                delay(5_000 - elapsed)
                commands.send(Command.Flush)
            }
            return
        }
        scheduledFlush?.cancel()
        scheduledFlush = null
        // Also throttle retries on failing storage, without impacting collection.
        lastFlush = now
        try {
            val bytes = history.encode().toByteArray(Charsets.UTF_8)
            require(bytes.size <= ConnectionJson.MAX_FILE_BYTES)
            val output = file.startWrite()
            try {
                output.write(bytes)
                file.finishWrite(output)
            } catch (failure: Exception) {
                file.failWrite(output)
                throw failure
            }
            dirty = false
            storageError = null
        } catch (_: Exception) {
            storageError = "Connection history could not be saved."
        }
    }

    private fun publish() {
        mutableState.value = ConnectionHistoryState(
            connections = history.records,
            status = status,
            counters = history.counters,
            persistOnDisk = persistence,
            error = captureToggleError ?: captureError ?: captureSettingError ?: historyClearError ?: storageError ?: attributionError,
            captureEnabled = captureEnabled,
        )
        val nextExpiry = history.nextExpiryAt
        if (nextExpiry != scheduledExpiry) {
            scheduledRetention?.cancel()
            scheduledExpiry = nextExpiry
            scheduledRetention = nextExpiry?.let { expiry ->
                scope.launch {
                    delay((expiry - clock()).coerceAtLeast(1))
                    commands.send(Command.Maintenance(expiry))
                }
            }
        }
    }

    /** Test/application teardown only; runtime stops must use endRuntime to preserve final bytes. */
    internal fun close() {
        settings.unregisterChangeListener(listener)
        scope.cancel()
        commands.close()
    }
}
