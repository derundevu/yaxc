package io.github.derundevu.yaxc.viewmodel

import io.github.derundevu.yaxc.presentation.main.MainPingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MainPingTrackerTest {
    @Test
    fun fiveHundredClicksAdmitOnlyOneRunningAndTenWaitingSingles() {
        val tracker = MainPingTracker()
        val running = mutableListOf<MainPingTracker.Request>()
        val admitted = mutableListOf<Long>()
        for (profileId in 1L..500L) {
            if (tracker.requestSingle(profileId)) admitted.add(profileId)
            running.addAll(tracker.reserveAvailable())
            assertTrue(running.size <= 1)
        }
        assertEquals((1L..11L).toList(), admitted)
        assertEquals(1L, running.single().profileId)
        assertEquals(admitted.toSet(), tracker.snapshot.profiles.keys)
        assertTrue(tracker.requestSingle(1L))
        assertTrue(tracker.requestSingle(11L))
        assertFalse(tracker.requestSingle(501L))

        val measured = mutableListOf<Long>()
        while (running.isNotEmpty()) {
            val request = running.removeAt(0)
            assertTrue(request.single)
            measured.add(request.profileId)
            tracker.complete(request.id, MainPingState.Success("1 ms"))
            running.addAll(tracker.reserveAvailable())
            assertTrue(running.size <= 1)
        }
        assertEquals(admitted, measured)
        assertTrue(tracker.snapshot.profiles.values.all { it == MainPingState.Success("1 ms") })
        assertTrue(tracker.requestSingle(501L))
        assertEquals(501L, tracker.takeNext()!!.profileId)
    }

    @Test
    fun fullNativePoolBoundsClickFloodAndKeepsBatchWorkersUseful() {
        val tracker = MainPingTracker()
        val batchProfiles = (1000L..1019L).toList()
        tracker.requestBatch(batchProfiles, sourceId = 7L)
        val running = tracker.reserveAvailable().toMutableList()
        assertEquals(5, running.size)
        assertTrue(running.none { it.single })
        val admitted = (1L..500L).filter { tracker.requestSingle(it) }
        assertEquals((1L..10L).toList(), admitted)
        assertNull(tracker.takeNext())

        val first = running.removeAt(0)
        tracker.complete(first.id, MainPingState.Success("1 ms"))
        val single = tracker.takeNext()!!
        running.add(single)
        assertEquals(1L, single.profileId)
        assertTrue(single.single)
        val second = running.removeAt(0)
        tracker.complete(second.id, MainPingState.Success("1 ms"))
        val usefulBatchWork = tracker.takeNext()!!
        assertFalse(usefulBatchWork.single)
        running.add(usefulBatchWork)
        assertEquals(2, tracker.snapshot.completed)
        assertEquals(20, tracker.snapshot.total)

        val measured = mutableListOf(first.profileId, second.profileId)
        while (running.isNotEmpty()) {
            assertTrue(running.size <= 5)
            assertTrue(running.count { it.single } <= 1)
            val request = running.removeAt(0)
            measured.add(request.profileId)
            tracker.complete(request.id, MainPingState.Success("1 ms"))
            running.addAll(tracker.reserveAvailable())
        }
        assertEquals((batchProfiles + admitted).toSet(), measured.toSet())
        assertEquals(30, measured.size)
        assertEquals(0, tracker.snapshot.total)
    }

    @Test
    fun standaloneSingleDoesNotCancelOrAdvanceBatchProgress() {
        val tracker = MainPingTracker()
        tracker.requestBatch((1L..8L).toList(), sourceId = 10L)
        val batch = tracker.reserveAvailable()
        assertTrue(tracker.requestSingle(100L))
        assertNull(tracker.takeNext())
        tracker.complete(batch[0].id, MainPingState.Error("failed"))
        val single = tracker.takeNext()!!
        assertEquals(100L, single.profileId)
        assertTrue(single.single)
        assertEquals(1, tracker.snapshot.completed)
        tracker.complete(single.id, MainPingState.Success("3 ms"))
        assertEquals(10L, tracker.snapshot.sourceId)
        assertEquals(1, tracker.snapshot.completed)
        assertEquals(8, tracker.snapshot.total)
        val nextBatch = tracker.takeNext()!!
        assertEquals(6L, nextBatch.profileId)
        assertFalse(nextBatch.single)
        tracker.complete(batch[1].id, MainPingState.Success("4 ms"))
        assertEquals(2, tracker.snapshot.completed)
        assertEquals(MainPingState.Error("failed"), tracker.snapshot.profiles[1L])
        assertEquals(MainPingState.Success("3 ms"), tracker.snapshot.profiles[100L])
    }

    @Test
    fun batchJoinsExistingSinglesAndSkipsWaitingSingleWhileOtherSingleRuns() {
        val tracker = MainPingTracker()
        assertTrue(tracker.requestSingle(1L))
        val firstSingle = tracker.takeNext()!!
        assertTrue(tracker.requestSingle(2L))
        tracker.requestBatch((1L..6L).toList(), sourceId = 10L)
        val batch = tracker.reserveAvailable()
        assertEquals(listOf(3L, 4L, 5L, 6L), batch.map { it.profileId })
        assertTrue(batch.none { it.single })
        assertNull(tracker.takeNext())
        tracker.complete(firstSingle.id, MainPingState.Success("1 ms"))
        assertEquals(1, tracker.snapshot.completed)
        val nextSingle = tracker.takeNext()!!
        assertEquals(2L, nextSingle.profileId)
        assertTrue(nextSingle.single)
        tracker.complete(nextSingle.id, MainPingState.Success("2 ms"))
        assertEquals(2, tracker.snapshot.completed)
        for (request in batch) tracker.complete(request.id, MainPingState.Success("3 ms"))
        assertNull(tracker.takeNext())
        assertNull(tracker.snapshot.sourceId)
        assertEquals(0, tracker.snapshot.total)
        assertEquals(6, tracker.snapshot.profiles.size)
    }

    @Test
    fun queuedBatchProfilesPromoteToSinglePriorityWithoutDuplicateProbes() {
        val tracker = MainPingTracker()
        tracker.requestBatch((1L..10L).toList(), sourceId = 10L)
        val firstBatch = tracker.takeNext()!!
        assertTrue(tracker.requestSingle(1L))
        assertFalse(firstBatch.single)
        assertTrue(tracker.requestSingle(9L))
        assertTrue(tracker.requestSingle(9L))
        val promoted = tracker.takeNext()!!
        assertEquals(9L, promoted.profileId)
        assertTrue(promoted.single)
        assertTrue(tracker.requestSingle(10L))
        val batch = tracker.reserveAvailable()
        assertEquals(listOf(2L, 3L, 4L), batch.map { it.profileId })
        tracker.complete(firstBatch.id, MainPingState.Success("1 ms"))
        val nextBatch = tracker.takeNext()!!
        assertEquals(5L, nextBatch.profileId)
        assertFalse(nextBatch.single)
        tracker.complete(promoted.id, MainPingState.Success("2 ms"))
        assertEquals(2, tracker.snapshot.completed)
        val nextPromoted = tracker.takeNext()!!
        assertEquals(10L, nextPromoted.profileId)
        assertTrue(nextPromoted.single)
        tracker.complete(nextPromoted.id, MainPingState.Success("3 ms"))
        assertEquals(3, tracker.snapshot.completed)
        val followingBatch = tracker.takeNext()!!
        assertEquals(6L, followingBatch.profileId)
        assertFalse(followingBatch.single)
        assertNull(tracker.takeNext())
    }

    @Test
    fun repeatingActiveBatchUsesFullMembershipIncludingCompletedProfiles() {
        val tracker = MainPingTracker()
        tracker.requestBatch((1L..6L).toList(), sourceId = 10L)
        val active = tracker.reserveAvailable()
        tracker.complete(active[0].id, MainPingState.Error("failed"))
        val beforeRepeat = tracker.snapshot
        tracker.requestBatch(listOf(6L, 5L, 4L, 3L, 2L, 1L, 1L), sourceId = 10L)
        assertEquals(beforeRepeat, tracker.snapshot)
        val remaining = tracker.takeNext()!!
        assertEquals(6L, remaining.profileId)
        assertFalse(remaining.single)
        assertNull(tracker.takeNext())
    }

    @Test
    fun remeasuringCompletedBatchMemberDoesNotCountItTwice() {
        val tracker = MainPingTracker()
        tracker.requestBatch(listOf(1L, 2L, 3L), sourceId = 10L)
        val active = tracker.reserveAvailable()
        tracker.complete(active[0].id, MainPingState.Error("failed"))
        assertTrue(tracker.requestSingle(1L))
        val retry = tracker.takeNext()!!
        assertTrue(retry.single)
        tracker.complete(retry.id, MainPingState.Success("1 ms"))
        assertEquals(1, tracker.snapshot.completed)
        assertEquals(3, tracker.snapshot.total)
        assertEquals(MainPingState.Success("1 ms"), tracker.snapshot.profiles[1L])
        tracker.complete(active[1].id, MainPingState.Success("2 ms"))
        assertEquals(2, tracker.snapshot.completed)
        tracker.complete(active[2].id, MainPingState.Success("3 ms"))
        assertEquals(0, tracker.snapshot.completed)
        assertEquals(0, tracker.snapshot.total)
        assertNull(tracker.snapshot.sourceId)
    }

    @Test
    fun replacementPreservesSinglesAndNativeOwnershipButDropsAbandonedQueuedBatch() {
        val tracker = MainPingTracker()
        tracker.requestBatch((1L..8L).toList(), sourceId = 10L)
        val old = tracker.reserveAvailable()
        assertTrue(tracker.requestSingle(7L))
        assertTrue(tracker.requestSingle(100L))
        tracker.requestBatch(listOf(3L, 9L, 10L), sourceId = 20L)
        assertEquals(MainPingState.Idle, tracker.snapshot.profiles[6L])
        assertEquals(MainPingState.Idle, tracker.snapshot.profiles[8L])
        for (profileId in listOf(1L, 2L, 3L, 4L, 5L, 7L, 100L)) {
            assertEquals(MainPingState.Loading, tracker.snapshot.profiles[profileId])
        }
        assertNull(tracker.takeNext())
        tracker.complete(old[0].id, MainPingState.Success("1 ms"))
        assertEquals(20L, tracker.snapshot.sourceId)
        assertEquals(0, tracker.snapshot.completed)
        val preservedPromotion = tracker.takeNext()!!
        assertEquals(7L, preservedPromotion.profileId)
        assertTrue(preservedPromotion.single)
        tracker.complete(old[1].id, MainPingState.Error("failed"))
        val newBatch = tracker.takeNext()!!
        assertEquals(9L, newBatch.profileId)
        assertFalse(newBatch.single)
        tracker.complete(old[2].id, MainPingState.Success("3 ms"))
        assertEquals(1, tracker.snapshot.completed)
        tracker.complete(preservedPromotion.id, MainPingState.Success("7 ms"))
        assertEquals(1, tracker.snapshot.completed)
        val preservedSingle = tracker.takeNext()!!
        assertEquals(100L, preservedSingle.profileId)
        assertTrue(preservedSingle.single)
        val lastBatch = tracker.takeNext()!!
        assertEquals(10L, lastBatch.profileId)
        assertNull(tracker.takeNext())
        tracker.complete(newBatch.id, MainPingState.Success("9 ms"))
        assertEquals(2, tracker.snapshot.completed)
        tracker.complete(lastBatch.id, MainPingState.Success("10 ms"))
        assertEquals(0, tracker.snapshot.total)
        assertNull(tracker.snapshot.sourceId)
        assertEquals(MainPingState.Loading, tracker.snapshot.profiles[100L])
        // Orphaned native probes still occupy their slots after the new batch is done.
        tracker.requestBatch(listOf(200L, 201L, 202L, 203L), sourceId = 30L)
        assertEquals(2, tracker.reserveAvailable().size)
        tracker.complete(old[3].id, MainPingState.Success("4 ms"))
        assertEquals(0, tracker.snapshot.completed)
        assertEquals(MainPingState.Success("4 ms"), tracker.snapshot.profiles[4L])
        assertEquals(202L, tracker.takeNext()!!.profileId)
        tracker.complete(preservedSingle.id, MainPingState.Success("100 ms"))
        assertEquals(0, tracker.snapshot.completed)
    }

    @Test
    fun emptyBatchClearsOnlyPendingBatchLoading() {
        val tracker = MainPingTracker(workerCount = 1)
        tracker.requestBatch(listOf(1L, 2L, 3L), sourceId = 10L)
        val active = tracker.takeNext()!!
        assertTrue(tracker.requestSingle(3L))
        tracker.requestBatch(emptyList(), sourceId = 20L)
        assertEquals(MainPingState.Loading, tracker.snapshot.profiles[1L])
        assertEquals(MainPingState.Idle, tracker.snapshot.profiles[2L])
        assertEquals(MainPingState.Loading, tracker.snapshot.profiles[3L])
        assertEquals(0, tracker.snapshot.total)
        assertNull(tracker.snapshot.sourceId)
        assertNull(tracker.takeNext())
        tracker.complete(active.id, MainPingState.Success("1 ms"))
        val single = tracker.takeNext()!!
        assertEquals(3L, single.profileId)
        assertTrue(single.single)
        tracker.complete(single.id, MainPingState.Success("3 ms"))
        assertNull(tracker.takeNext())
    }

    @Test
    fun deletedActiveProfileKeepsItsSlotAndCannotResurrectFromCompletion() {
        val tracker = MainPingTracker(workerCount = 2)
        assertTrue(tracker.requestSingle(1L))
        val removed = tracker.takeNext()!!
        tracker.requestBatch(listOf(2L, 3L), sourceId = 10L)
        val batch = tracker.takeNext()!!
        tracker.retain(setOf(2L, 3L))
        assertFalse(tracker.snapshot.profiles.containsKey(1L))
        assertTrue(tracker.requestSingle(4L))
        assertNull(tracker.takeNext())
        tracker.complete(removed.id, MainPingState.Success("1 ms"))
        assertFalse(tracker.snapshot.profiles.containsKey(1L))
        assertEquals(0, tracker.snapshot.completed)
        val nextSingle = tracker.takeNext()!!
        assertEquals(4L, nextSingle.profileId)
        assertTrue(nextSingle.single)
        tracker.complete(batch.id, MainPingState.Success("2 ms"))
        assertEquals(1, tracker.snapshot.completed)
        assertEquals(3L, tracker.takeNext()!!.profileId)
    }

    @Test
    fun retainingProfilesDropsQueuedSinglesAndCountsRemovedBatchMembersAsSettled() {
        val tracker = MainPingTracker(workerCount = 1, singleQueueCapacity = 2)
        assertTrue(tracker.requestSingle(1L))
        val active = tracker.takeNext()!!
        assertTrue(tracker.requestSingle(2L))
        assertTrue(tracker.requestSingle(3L))
        assertFalse(tracker.requestSingle(4L))
        tracker.requestBatch(listOf(10L, 11L), sourceId = 10L)
        tracker.retain(setOf(1L, 3L, 11L))
        assertFalse(tracker.snapshot.profiles.containsKey(2L))
        assertFalse(tracker.snapshot.profiles.containsKey(10L))
        assertEquals(1, tracker.snapshot.completed)
        assertEquals(2, tracker.snapshot.total)
        assertTrue(tracker.requestSingle(4L))
        tracker.complete(active.id, MainPingState.Success("1 ms"))
        val nextSingle = tracker.takeNext()!!
        assertEquals(3L, nextSingle.profileId)
        tracker.retain(setOf(1L, 3L, 4L))
        assertNull(tracker.snapshot.sourceId)
        assertEquals(0, tracker.snapshot.total)
        tracker.complete(nextSingle.id, MainPingState.Success("3 ms"))
        assertEquals(4L, tracker.takeNext()!!.profileId)
        assertNull(tracker.takeNext())
    }

    @Test
    fun deletedBatchProbeRetainsCapacityButItsLateResultCannotSettleTwice() {
        val tracker = MainPingTracker(workerCount = 1)
        tracker.requestBatch(listOf(1L, 2L), sourceId = 10L)
        val removed = tracker.takeNext()!!
        tracker.retain(setOf(2L))
        assertEquals(1, tracker.snapshot.completed)
        assertNull(tracker.takeNext())
        tracker.complete(removed.id, MainPingState.Error("deleted"))
        assertFalse(tracker.snapshot.profiles.containsKey(1L))
        assertEquals(1, tracker.snapshot.completed)
        val remaining = tracker.takeNext()!!
        assertEquals(2L, remaining.profileId)
        tracker.complete(remaining.id, MainPingState.Success("2 ms"))
        assertEquals(0, tracker.snapshot.total)
        assertNull(tracker.snapshot.sourceId)
    }

    @Test
    fun duplicateAndStaleCompletionsAreIgnoredAndErrorsReleaseWorkers() {
        val tracker = MainPingTracker(workerCount = 2)
        tracker.requestBatch(listOf(1L, 2L, 3L), sourceId = 10L)
        val initial = tracker.reserveAvailable()
        tracker.complete(initial[0].id, MainPingState.Error("failed"))
        val afterError = tracker.snapshot
        tracker.complete(initial[0].id, MainPingState.Success("stale"))
        tracker.complete(Long.MAX_VALUE, MainPingState.Error("unknown"))
        assertEquals(afterError, tracker.snapshot)
        val finalBatch = tracker.takeNext()!!
        assertEquals(3L, finalBatch.profileId)
        tracker.complete(initial[1].id, MainPingState.Error("also failed"))
        assertEquals(2, tracker.snapshot.completed)
        tracker.complete(finalBatch.id, MainPingState.Error("last failed"))
        assertEquals(0, tracker.snapshot.completed)
        assertEquals(0, tracker.snapshot.total)
        assertNull(tracker.snapshot.sourceId)
        assertTrue(tracker.requestSingle(1L))
        val retry = tracker.takeNext()!!
        assertNotEquals(initial[0].id, retry.id)
        tracker.complete(initial[0].id, MainPingState.Success("stale"))
        assertEquals(MainPingState.Loading, tracker.snapshot.profiles[1L])
        tracker.complete(retry.id, MainPingState.Success("1 ms"))
        assertEquals(MainPingState.Success("1 ms"), tracker.snapshot.profiles[1L])
        assertNull(tracker.takeNext())
    }

    @Test
    fun finishedBatchCanBeRequestedAgainAndDeduplicatesInputProfiles() {
        val tracker = MainPingTracker()
        tracker.requestBatch(listOf(1L, 1L, 2L), sourceId = 10L)
        assertEquals(2, tracker.snapshot.total)
        val first = tracker.reserveAvailable()
        assertEquals(listOf(1L, 2L), first.map { it.profileId })
        for (request in first) tracker.complete(request.id, MainPingState.Success("1 ms"))
        assertEquals(0, tracker.snapshot.total)
        tracker.requestBatch(listOf(2L, 1L), sourceId = 10L)
        assertEquals(0, tracker.snapshot.completed)
        assertEquals(2, tracker.snapshot.total)
        assertTrue(tracker.snapshot.profiles.values.all { it == MainPingState.Loading })
        val second = tracker.reserveAvailable()
        assertEquals(listOf(2L, 1L), second.map { it.profileId })
        assertTrue(second.all { next -> first.none { it.id == next.id } })
    }

    private fun MainPingTracker.reserveAvailable(): List<MainPingTracker.Request> = buildList {
        while (true) add(takeNext() ?: break)
    }
}
