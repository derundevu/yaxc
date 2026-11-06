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
)

enum class ConnectionCaptureStatus { Stopped, Running, Unsupported, Error }

data class ConnectionHistoryState(
    val connections: List<ConnectionRecord> = emptyList(),
    val status: ConnectionCaptureStatus = ConnectionCaptureStatus.Stopped,
    val dropped: Long = 0,
    val persistOnDisk: Boolean = false,
    val error: String? = null,
)
