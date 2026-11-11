package io.github.derundevu.yaxc.repository

/** Captured at runtime start; never resolved against today's installed packages for old rows. */
data class ConnectionApp(val packageName: String, val name: String)

/** Destination metadata is sensitive even though no payload or configuration is collected. */
data class ConnectionRecord(
    val id: String = "",
    val protocol: String = "",
    val destination: String = "",
    val domain: String = "",
    val destinations: List<String> = emptyList(),
    val destinationsTruncated: Boolean = false,
    val destinationKind: String = "connection",
    val startedAt: Long = 0,
    val endedAt: Long = 0,
    val state: String = "active",
    val upload: Long = 0,
    val download: Long = 0,
    val liveBytesExact: Boolean = true,
    val uid: Int = -1,
    val profileId: Long = 0,
    val profileName: String = "",
    val profileFingerprint: String = "",
    val outboundTag: String = "",
    val route: String = "unknown",
    val apps: List<ConnectionApp> = emptyList(),
    /** Local persistence fence: a capture cutoff is never eligible for late byte correction. */
    val captureStopped: Boolean = false,
)

enum class ConnectionCaptureStatus { Stopped, Running, Disabled, Unsupported, Error }

/** Event counts, not a count of distinct missing flows; native evictions may precede capture. */
data class ConnectionHistoryCounters(
    val nativeEvicted: Long = 0,
    val nativeRejected: Long = 0,
    val historyEvicted: Long = 0,
    val historyRejected: Long = 0,
    val unclassified: Long = 0,
)

data class ConnectionHistoryState(
    val connections: List<ConnectionRecord> = emptyList(),
    val status: ConnectionCaptureStatus = ConnectionCaptureStatus.Stopped,
    val counters: ConnectionHistoryCounters = ConnectionHistoryCounters(),
    val persistOnDisk: Boolean = false,
    val error: String? = null,
    val captureEnabled: Boolean = true,
)
