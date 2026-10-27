package io.github.derundevu.yaxc.viewmodel

import io.github.derundevu.yaxc.presentation.main.MainPingState

internal data class MainPingSnapshot(
    val profiles: Map<Long, MainPingState> = emptyMap(),
    val sourceId: Long? = null,
    val completed: Int = 0,
    val total: Int = 0,
)

/** Only the current operation may settle its pending profiles or clear its progress. */
internal class MainPingTracker {
    private var nextId = 0L
    private var operationId: Long? = null
    private val pending = mutableSetOf<Long>()
    var snapshot = MainPingSnapshot()
        private set

    fun start(profileIds: List<Long>, sourceId: Long? = null, batch: Boolean = false): Long {
        val states = snapshot.profiles.mapValues { (_, state) ->
            if (state == MainPingState.Loading) MainPingState.Idle else state
        }.toMutableMap()
        pending.clear()
        pending.addAll(profileIds)
        pending.forEach { states[it] = MainPingState.Loading }
        snapshot = MainPingSnapshot(states, sourceId, total = if (batch) pending.size else 0)
        return (++nextId).also { operationId = it }
    }

    fun isCurrent(id: Long) = operationId == id

    fun result(id: Long, profileId: Long, state: MainPingState) {
        if (!isCurrent(id) || !pending.remove(profileId)) return
        snapshot = snapshot.copy(
            profiles = snapshot.profiles + (profileId to state),
            completed = if (snapshot.total > 0) snapshot.total - pending.size else 0,
        )
    }

    fun retain(profileIds: Set<Long>) {
        pending.retainAll(profileIds)
        snapshot = snapshot.copy(
            profiles = snapshot.profiles.filterKeys { it in profileIds },
            completed = if (snapshot.total > 0) snapshot.total - pending.size else 0,
        )
    }

    fun finish(id: Long) {
        if (!isCurrent(id)) return
        val states = snapshot.profiles.mapValues { (profileId, state) ->
            if (profileId in pending && state == MainPingState.Loading) MainPingState.Idle else state
        }
        operationId = null
        pending.clear()
        snapshot = MainPingSnapshot(profiles = states)
    }
}
