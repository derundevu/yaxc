package io.github.derundevu.yaxc.repository

import android.database.sqlite.SQLiteConstraintException
import io.github.derundevu.yaxc.database.Link
import io.github.derundevu.yaxc.database.Profile
import io.github.derundevu.yaxc.testing.RegressionApplication
import io.github.derundevu.yaxc.testing.RoomTestSupport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = RegressionApplication::class, sdk = [34])
class ProfileRepositoryDatabaseTest : RoomTestSupport() {
    private val repository get() = app.profileRepository
    private val dao get() = database.profileDao()

    @Test
    fun createPrependsAtZeroEvenWhenCallerSuppliesAnIndex() = runBlocking {
        val first = repository.create(Profile(name = "First", config = "{\"tag\":\"first\"}"))
        val second = repository.create(Profile(index = 73, name = "Second", config = "{\"tag\":\"second\"}"))
        val third = repository.create(Profile(name = "Third", config = "{\"tag\":\"third\"}"))

        assertOrder(third, second, first)
        assertEquals("{\"tag\":\"second\"}", repository.find(second).config)
        assertEquals("Second", repository.find(second).name)
    }

    @Test
    fun removeUsesStoredPositionRatherThanStaleSnapshotAndRepeatedRemoveIsSafe() = runBlocking {
        val original = repository.create(Profile(name = "Original"))
        val stale = repository.find(original)
        val newer = repository.create(Profile(name = "Newer"))
        val newest = repository.create(Profile(name = "Newest"))
        repository.remove(stale)
        assertOrder(newest, newer)

        repository.remove(stale)
        repository.remove(Profile(id = 9000, index = -20))
        assertOrder(newest, newer)
        assertEquals("Newer", repository.find(newer).name)
    }

    @Test
    fun removeFirstMiddleAndLastCompactsExactlyTheSurvivingRows() = runBlocking {
        val ids = seedOrdered(5)
        repository.remove(repository.find(ids[2]))
        assertOrder(ids[0], ids[1], ids[3], ids[4])
        repository.remove(repository.find(ids[0]))
        assertOrder(ids[1], ids[3], ids[4])
        repository.remove(repository.find(ids[4]))
        assertOrder(ids[1], ids[3])
    }

    @Test
    fun normalizeOrdersNegativeGapsAndTiesDeterministicallyAndIsIdempotent() = runBlocking {
        dao.insert(Profile(id = 50, index = 90, name = "Last", config = "last-content"))
        dao.insert(Profile(id = 30, index = -7, name = "Tie later", config = "later-content"))
        dao.insert(Profile(id = 10, index = -7, name = "Tie earlier", config = "earlier-content"))
        dao.insert(Profile(id = 20, index = 12, name = "Middle", config = "middle-content"))
        repository.normalizeIndices()
        assertOrder(10, 30, 20, 50)
        repository.normalizeIndices()
        assertOrder(10, 30, 20, 50)
        assertEquals("later-content", repository.find(30).config)
        assertEquals("last-content", repository.find(50).config)
    }

    @Test
    fun moveAcrossBothEdgesShiftsOnlyTheClosedSourceDestinationSpan() = runBlocking {
        val ids = seedOrdered(5)
        repository.moveUp(start = 0, end = 4, exclude = ids[4])
        assertOrder(ids[4], ids[0], ids[1], ids[2], ids[3])
        repository.moveDown(start = 4, end = 0, exclude = ids[4])
        assertOrder(*ids.toLongArray())
        repository.moveUp(start = 2, end = 4, exclude = ids[4])
        assertOrder(ids[0], ids[1], ids[4], ids[2], ids[3])
        repository.moveDown(start = 4, end = 2, exclude = ids[4])
        assertOrder(*ids.toLongArray())
        ids.forEachIndexed { index, id -> assertEquals("config-$index", repository.find(id).config) }
    }

    @Test
    fun equalAndSingleStepMovesKeepUniqueContiguousIndices() = runBlocking {
        val ids = seedOrdered(3)
        repository.moveUp(1, 1, ids[1])
        repository.moveDown(1, 1, ids[1])
        assertOrder(*ids.toLongArray())
        repository.moveUp(0, 1, ids[1])
        assertOrder(ids[1], ids[0], ids[2])
        repository.moveDown(1, 0, ids[1])
        assertOrder(*ids.toLongArray())
    }

    @Test
    fun visibilityFollowsSourceActivationButLinkQueriesRetainInactiveProfiles() = runBlocking {
        val activeId = database.linkDao().insert(Link(name = "Active", isActive = true))
        val inactiveId = database.linkDao().insert(Link(name = "Inactive", isActive = false))
        val manual = repository.create(Profile(name = "Manual"))
        val activeOld = repository.create(Profile(linkId = activeId, name = "Active old"))
        val inactive = repository.create(Profile(linkId = inactiveId, name = "Hidden"))
        val activeNew = repository.create(Profile(linkId = activeId, name = "Active new"))
        assertEquals(listOf(activeNew, activeOld, manual), repository.all.first().map { it.id })
        assertEquals(listOf(activeOld, activeNew), repository.linkProfiles(activeId).map { it.id })
        assertEquals(listOf(inactive), repository.linkProfiles(inactiveId).map { it.id })
        assertEquals(inactiveId, repository.find(inactive).linkId)

        val source = database.linkDao().find(inactiveId)!!
        database.linkDao().update(source.copy(isActive = true))
        assertEquals(listOf(activeNew, inactive, activeOld, manual), repository.all.first().map { it.id })
        database.linkDao().update(source.copy(isActive = false))
        assertEquals(listOf(activeNew, activeOld, manual), repository.all.first().map { it.id })
        assertOrder(activeNew, inactive, activeOld, manual)
    }

    @Test
    fun invalidForeignKeyCreateRollsBackIndexShiftAndExistingContent() = runBlocking {
        val ids = seedOrdered(3)
        var rejected = false
        try {
            repository.create(Profile(linkId = 404, name = "Orphan", config = "not-stored"))
        } catch (_: SQLiteConstraintException) {
            rejected = true
        }
        assertTrue("Foreign key must reject an orphan profile", rejected)
        assertOrder(*ids.toLongArray())
        assertEquals(listOf("config-0", "config-1", "config-2"), ids.map { repository.find(it).config })
    }

    @Test
    fun updateCanChangeOwnershipAndContentWithoutAlteringOtherRows() = runBlocking {
        val owner = database.linkDao().insert(Link(name = "Owner"))
        val ids = seedOrdered(2)
        val before = repository.find(ids[1])
        repository.update(before.copy(linkId = owner, name = "Renamed", config = "{\"updated\":true}"))
        assertEquals(before.copy(linkId = owner, name = "Renamed", config = "{\"updated\":true}"),
            repository.find(ids[1]))
        assertEquals(Profile(ids[0], null, 0, "Profile 0", "config-0"), repository.find(ids[0]))
        assertOrder(*ids.toLongArray())
    }

    @Test
    fun concurrentCreatesHaveNoLostRowsOrDuplicateIndices() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val inserted = coroutineScope {
            val jobs = (0 until 24).map { number ->
                async(Dispatchers.Default) {
                    gate.await()
                    repository.create(Profile(name = "Concurrent $number", config = "payload-$number"))
                }
            }
            gate.complete(Unit)
            jobs.awaitAll()
        }
        val positions = dao.orderedPositions()
        assertEquals(24, positions.size)
        assertEquals(inserted.toSet(), positions.map { it.id }.toSet())
        assertEquals((0 until 24).toList(), positions.map { it.index })
        assertEquals((0 until 24).map { "payload-$it" }.toSet(),
            inserted.map { repository.find(it).config }.toSet())
    }

    @Test
    fun staleMoveUpAfterPrependUsesPersistedSourceIndex() = runBlocking {
        val ids = seedOrdered(4)
        val new = repository.create(Profile(name = "New"))
        repository.moveUp(start = 0, end = 3, exclude = ids[3])
        assertOrder(ids[3], new, ids[0], ids[1], ids[2])
    }

    @Test
    fun staleMoveDownAfterMultiplePrependsDoesNotShiftRowsBeforeItsActualSource() = runBlocking {
        val ids = seedOrdered(5)
        val newer = repository.create(Profile(name = "Newer"))
        val newest = repository.create(Profile(name = "Newest"))
        repository.moveDown(start = 5, end = 1, exclude = ids[1])
        assertOrder(newest, newer, ids[0], ids[2], ids[3], ids[1], ids[4])
    }

    @Test
    fun movesOfDeletedRowsCannotShiftSurvivingRows() = runBlocking {
        val ids = seedOrdered(4)
        repository.remove(repository.find(ids[1]))
        repository.moveUp(start = 0, end = 1, exclude = ids[1])
        repository.moveDown(start = 3, end = 1, exclude = ids[1])
        assertOrder(ids[0], ids[2], ids[3])
    }

    @Test
    fun createRacingAStaleMoveRetainsAContiguousPermutation() = runBlocking {
        val ids = seedOrdered(5)
        val gate = CompletableDeferred<Unit>()
        val new = coroutineScope {
            val create = async(Dispatchers.Default) {
                gate.await()
                repository.create(Profile(name = "Concurrent prepend", config = "new-content"))
            }
            val move = async(Dispatchers.Default) {
                gate.await()
                repository.moveUp(start = 0, end = 4, exclude = ids[4])
            }
            gate.complete(Unit)
            move.await()
            create.await()
        }
        val positions = dao.orderedPositions()
        assertEquals((0 until 6).toList(), positions.map { it.index })
        assertEquals((ids + new).toSet(), positions.map { it.id }.toSet())
        val actual = positions.map { it.id }
        assertTrue(actual == listOf(new, ids[4], ids[0], ids[1], ids[2], ids[3]) ||
            actual == listOf(ids[4], new, ids[0], ids[1], ids[2], ids[3]))
        assertEquals("new-content", repository.find(new).config)
    }

    @Test
    fun equalSnapshotIndicesStillMoveWhenPersistedSourceHasChanged() = runBlocking {
        val ids = seedOrdered(3)
        val new = repository.create(Profile(name = "New"))
        repository.moveUp(start = 1, end = 1, exclude = ids[1])
        assertOrder(new, ids[1], ids[0], ids[2])
        repository.moveDown(start = 2, end = 2, exclude = ids[1])
        assertOrder(new, ids[0], ids[1], ids[2])
    }

    @Test
    fun destinationsOutsideTheListClampToFirstAndLastRows() = runBlocking {
        val ids = seedOrdered(3)
        repository.moveUp(start = Int.MIN_VALUE, end = 2, exclude = ids[2])
        assertOrder(ids[2], ids[0], ids[1])
        repository.moveDown(start = Int.MAX_VALUE, end = 0, exclude = ids[2])
        assertOrder(*ids.toLongArray())
    }

    @Test
    fun emptyListMovesAreNoOpsEvenWithOutOfRangeDestinations() = runBlocking {
        repository.moveUp(start = Int.MIN_VALUE, end = 0, exclude = 404)
        repository.moveDown(start = Int.MAX_VALUE, end = 0, exclude = 404)
        assertOrder()
    }

    private suspend fun seedOrdered(count: Int): List<Long> = (0 until count).map { index ->
        dao.insert(Profile(index = index, name = "Profile $index", config = "config-$index"))
    }

    private suspend fun assertOrder(vararg ids: Long) {
        val positions = dao.orderedPositions()
        assertEquals(ids.toList(), positions.map { it.id })
        assertEquals(ids.indices.toList(), positions.map { it.index })
    }
}
