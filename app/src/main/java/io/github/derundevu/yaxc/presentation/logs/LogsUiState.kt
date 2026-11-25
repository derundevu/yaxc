package io.github.derundevu.yaxc.presentation.logs

enum class LogLevel(internal val preferenceId: String) {
    Verbose("verbose"), Debug("debug"), Info("info"), Warning("warning"), Error("error"), Unknown("unknown"),
}

enum class LogSource(internal val preferenceId: String) {
    Xray("xray"), App("app"), Vpn("vpn"), Helper("helper"),
}

enum class LogStreamStatus { Connecting, Live, Waiting, Unavailable }

data class LogEntry(
    val id: Long,
    val timestamp: String?,
    val level: LogLevel,
    val source: LogSource,
    val tag: String?,
    val message: String,
    val prefix: String = "",
) {
    fun rawText(): String = prefix + message
}

data class LogsUiState(
    val entries: List<LogEntry> = emptyList(),
    val query: String = "",
    val selectedLevels: Set<LogLevel> = emptySet(),
    val selectedSources: Set<LogSource> = emptySet(),
    val paused: Boolean = false,
    val pendingCount: Long = 0L,
    val totalCount: Int = 0,
    val droppedCount: Long = 0L,
    val status: LogStreamStatus = LogStreamStatus.Connecting,
    val error: String? = null,
    val clearGeneration: Long = 0L,
)
