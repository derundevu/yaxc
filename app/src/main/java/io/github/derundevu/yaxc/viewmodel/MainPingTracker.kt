package io.github.derundevu.yaxc.viewmodel

import io.github.derundevu.yaxc.presentation.main.MainPingState

internal data class MainPingSnapshot(
    val profiles: Map<Long, MainPingState> = emptyMap(),
    val sourceId: Long? = null,
    val completed: Int = 0,
    val total: Int = 0,
)

/** Main-thread scheduler; a reservation owns its worker until native teardown completes. */
internal class MainPingTracker(
    private val workerCount: Int = 5,
    private val singleQueueCapacity: Int = 10,
) {
    data class Request(val id: Long, val profileId: Long, val single: Boolean)

    private var nextId = 0L
    private val active = linkedMapOf<Long, Request>()
    private val singles = linkedSetOf<Long>()
    private var batchProfiles: Set<Long>? = null
    private val batchPending = linkedSetOf<Long>()

    var snapshot = MainPingSnapshot()
        private set

    fun requestSingle(profileId: Long): Boolean {
        if (active.values.any { it.profileId == profileId }) {
            // An explicitly requested, reintroduced profile may rejoin its native probe.
            if (profileId !in snapshot.profiles) markLoading(profileId)
            return true
        }
        if (profileId in singles) return true
        if (singles.size >= singleQueueCapacity) return false
        singles.add(profileId)
        markLoading(profileId)
        return true
    }

    fun requestBatch(profileIds: List<Long>, sourceId: Long? = null) {
        val profiles = profileIds.toSet()
        if (batchProfiles == profiles && snapshot.sourceId == sourceId) return

        val states = snapshot.profiles.toMutableMap()
        for (profileId in batchPending) {
            if (profileId !in profiles && profileId !in singles &&
                active.values.none { it.profileId == profileId } &&
                states[profileId] == MainPingState.Loading
            ) {
                states[profileId] = MainPingState.Idle
            }
        }
        for (profileId in profiles) states[profileId] = MainPingState.Loading
        batchProfiles = profiles.takeIf { it.isNotEmpty() }
        batchPending.clear()
        batchPending.addAll(profiles)
        snapshot = MainPingSnapshot(
            profiles = states,
            sourceId = sourceId.takeIf { profiles.isNotEmpty() },
            total = profiles.size,
        )
    }

    fun takeNext(): Request? {
        if (active.size >= workerCount) return null
        val singleProfile = if (active.values.none { it.single }) singles.firstOrNull() else null
        val profileId = singleProfile ?: batchPending.firstOrNull { profileId ->
            profileId !in singles && active.values.none { it.profileId == profileId }
        } ?: return null
        if (singleProfile != null) singles.remove(profileId)
        return Request(++nextId, profileId, single = singleProfile != null).also {
            active[it.id] = it
        }
    }

    fun complete(requestId: Long, state: MainPingState) {
        val request = active.remove(requestId) ?: return
        // retain() may have deleted the profile while its native worker was still occupied.
        if (request.profileId in snapshot.profiles) {
            snapshot = snapshot.copy(profiles = snapshot.profiles + (request.profileId to state))
        }
        if (batchPending.remove(request.profileId)) updateBatchProgress()
    }

    fun retain(profileIds: Set<Long>) {
        singles.retainAll(profileIds)
        batchPending.retainAll(profileIds)
        snapshot = snapshot.copy(profiles = snapshot.profiles.filterKeys { it in profileIds })
        updateBatchProgress()
    }

    private fun markLoading(profileId: Long) {
        snapshot = snapshot.copy(profiles = snapshot.profiles + (profileId to MainPingState.Loading))
    }

    private fun updateBatchProgress() {
        if (batchPending.isEmpty()) {
            batchProfiles = null
            snapshot = snapshot.copy(sourceId = null, completed = 0, total = 0)
        } else {
            snapshot = snapshot.copy(completed = snapshot.total - batchPending.size)
        }
    }
}
