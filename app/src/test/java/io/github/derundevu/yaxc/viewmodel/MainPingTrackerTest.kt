package io.github.derundevu.yaxc.viewmodel

import io.github.derundevu.yaxc.presentation.main.MainPingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MainPingTrackerTest {
    @Test
    fun replacementIgnoresOldResultsAndOldCompletion() {
        val tracker = MainPingTracker()
        val old = tracker.start(listOf(1L, 2L), sourceId = 10L, batch = true)
        val current = tracker.start(listOf(2L, 3L), sourceId = 20L, batch = true)
        tracker.result(old, 2L, MainPingState.Success("1 ms"))
        tracker.finish(old)
        assertEquals(20L, tracker.snapshot.sourceId)
        assertEquals(MainPingState.Idle, tracker.snapshot.profiles[1L])
        assertEquals(MainPingState.Loading, tracker.snapshot.profiles[2L])
        assertEquals(0, tracker.snapshot.completed)
        tracker.result(current, 3L, MainPingState.Success("2 ms"))
        assertEquals(1, tracker.snapshot.completed)
        assertEquals(MainPingState.Loading, tracker.snapshot.profiles[2L])
    }

    @Test
    fun singleReplacementKeepsResultsAttributedAndClearsCancelledLoading() {
        val tracker = MainPingTracker()
        val old = tracker.start(listOf(1L))
        val current = tracker.start(listOf(2L))
        tracker.result(old, 1L, MainPingState.Success("9 ms"))
        tracker.result(current, 2L, MainPingState.Success("3 ms"))
        tracker.finish(current)
        assertEquals(MainPingState.Idle, tracker.snapshot.profiles[1L])
        assertEquals(MainPingState.Success("3 ms"), tracker.snapshot.profiles[2L])
        assertEquals(0, tracker.snapshot.total)
    }

    @Test
    fun deletedProfilesAndDuplicateResultsDoNotCorruptProgress() {
        val tracker = MainPingTracker()
        val id = tracker.start(listOf(1L, 2L, 3L), sourceId = 10L, batch = true)
        tracker.result(id, 1L, MainPingState.Error("failed"))
        tracker.result(id, 1L, MainPingState.Success("4 ms"))
        assertEquals(1, tracker.snapshot.completed)
        assertEquals(MainPingState.Error("failed"), tracker.snapshot.profiles[1L])
        tracker.retain(setOf(1L, 3L))
        tracker.result(id, 2L, MainPingState.Success("5 ms"))
        assertFalse(tracker.snapshot.profiles.containsKey(2L))
        assertEquals(2, tracker.snapshot.completed)
        tracker.finish(id)
        assertEquals(MainPingState.Idle, tracker.snapshot.profiles[3L])
        assertEquals(null, tracker.snapshot.sourceId)
    }
}
