package io.github.derundevu.yaxc.repository

import io.github.derundevu.yaxc.database.Config as DatabaseConfig
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = RegressionApplication::class, sdk = [34])
class LinkRepositoryDatabaseTest : RoomTestSupport() {
    private val repository get() = app.linkRepository
    private val dao get() = database.linkDao()

    @Test
    fun insertsAppendAndRoundTripEverySourceField() = runBlocking {
        val first = richLink("First")
        val firstId = repository.insertAndGetId(first)
        val secondId = repository.insertAndGetId(richLink("Second").copy(position = -40))
        repository.insert(richLink("Third").copy(position = 80))
        val links = repository.all.first()
        assertEquals(listOf("First", "Second", "Third"), links.map { it.name })
        assertEquals(listOf(0, 1, 2), links.map { it.position })
        assertEquals(first.copy(id = firstId, position = 0), dao.find(firstId))
        assertEquals(secondId, links[1].id)
        assertEquals(3, links.map { it.id }.toSet().size)
    }

    @Test
    fun explicitIdentityInsertPreservesItsStoredPosition() = runBlocking {
        repository.insert(Link(id = 80, position = 11, name = "Imported"))
        val appended = repository.insertAndGetId(Link(name = "After import"))
        assertEquals(listOf(80L, appended), repository.all.first().map { it.id })
        assertEquals(listOf(11, 12), repository.all.first().map { it.position })
    }

    @Test
    fun activeQueriesAndTabsFollowUpdatesInTheSamePersistentOrder() = runBlocking {
        val first = repository.insertAndGetId(Link(name = "First", isActive = false))
        val second = repository.insertAndGetId(Link(name = "Second", isActive = true))
        val third = repository.insertAndGetId(Link(name = "Third", isActive = true))
        repository.reorder(listOf(third, first, second))
        assertEquals(listOf(third, second), repository.tabs.first().map { it.id })
        assertEquals(listOf(third, second), repository.activeLinks().map { it.id })
        repository.update(dao.find(first)!!.copy(isActive = true))
        repository.update(dao.find(third)!!.copy(isActive = false))
        assertEquals(listOf(first, second), repository.tabs.first().map { it.id })
        assertEquals(listOf(first, second), repository.activeLinks().map { it.id })
        assertOrder(third, first, second)
    }

    @Test
    fun reorderHandlesReverseEqualAndEmptyRequestsWithoutLosingSources() = runBlocking {
        val ids = insertSources(4)
        repository.reorder(ids.reversed())
        assertOrder(*ids.reversed().toLongArray())
        repository.reorder(ids.reversed())
        assertOrder(*ids.reversed().toLongArray())
        repository.reorder(emptyList())
        assertOrder(*ids.reversed().toLongArray())
        assertEquals((0 until 4).map { "Source $it" }.toSet(), repository.all.first().map { it.name }.toSet())
    }

    @Test
    fun stalePartialReorderDeduplicatesRequestedIdsAndRetainsUnmentionedSources() = runBlocking {
        val ids = insertSources(4)
        repository.delete(dao.find(ids[1])!!)
        repository.reorder(listOf(ids[2], ids[1], ids[2], 99999))
        assertOrder(ids[2], ids[0], ids[3])
        assertEquals(setOf("Source 0", "Source 2", "Source 3"),
            repository.all.first().map { it.name }.toSet())
    }

    @Test
    fun deleteCascadesOnlyOwnedProfilesAndRetainsGlobalConfiguration() = runBlocking {
        val deleted = repository.insertAndGetId(Link(name = "Deleted"))
        val retained = repository.insertAndGetId(Link(name = "Retained"))
        val deletedChild = database.profileDao().insert(Profile(linkId = deleted, index = 0, name = "Owned"))
        val retainedChild = database.profileDao().insert(Profile(linkId = retained, index = 1,
            name = "Other source", config = "other-source-content"))
        val manual = database.profileDao().insert(Profile(index = 2, name = "Manual", config = "manual-content"))
        val configuration = DatabaseConfig(routing = "{\"retained\":true}", routingMode = DatabaseConfig.Mode.Merge)
        database.configDao().insert(configuration)
        repository.delete(dao.find(deleted)!!)

        assertNull(dao.find(deleted))
        assertEquals(listOf(retained), repository.all.first().map { it.id })
        assertEquals(listOf(retainedChild, manual), database.profileDao().orderedPositions().map { it.id })
        assertTrue(database.profileDao().orderedPositions().none { it.id == deletedChild })
        assertEquals("other-source-content", database.profileDao().find(retainedChild).config)
        assertEquals("manual-content", database.profileDao().find(manual).config)
        assertEquals(configuration, database.configDao().get())
        app.profileRepository.normalizeIndices()
        assertEquals(listOf(0, 1), database.profileDao().orderedPositions().map { it.index })
    }

    @Test
    fun concurrentInsertsAllocateDistinctContiguousPositions() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val inserted = coroutineScope {
            val jobs = (0 until 30).map { number ->
                async(Dispatchers.Default) {
                    gate.await()
                    repository.insertAndGetId(Link(name = "Concurrent $number",
                        address = "http://127.0.0.1/source/$number"))
                }
            }
            gate.complete(Unit)
            jobs.awaitAll()
        }
        val links = repository.all.first()
        assertEquals(inserted.toSet(), links.map { it.id }.toSet())
        assertEquals((0 until 30).toList(), links.map { it.position })
        assertEquals((0 until 30).map { "http://127.0.0.1/source/$it" }.toSet(), links.map { it.address }.toSet())
    }

    @Test
    fun insertRacingAStaleReorderRetainsAllRowsAndUniquePositions() = runBlocking {
        val ids = insertSources(5)
        val gate = CompletableDeferred<Unit>()
        val inserted = coroutineScope {
            val add = async(Dispatchers.Default) {
                gate.await()
                repository.insertAndGetId(Link(name = "Concurrent append"))
            }
            val reorder = async(Dispatchers.Default) {
                gate.await()
                repository.reorder(ids.reversed())
            }
            gate.complete(Unit)
            reorder.await()
            add.await()
        }
        assertOrder(*(ids.reversed() + inserted).toLongArray())
        assertEquals("Concurrent append", dao.find(inserted)!!.name)
    }

    @Test
    fun concurrentReordersCommitAsWholePermutations() = runBlocking {
        val ids = insertSources(8)
        val reversed = ids.reversed()
        val rotated = ids.drop(3) + ids.take(3)
        val gate = CompletableDeferred<Unit>()
        coroutineScope {
            val jobs = listOf(reversed, rotated).map { order ->
                async(Dispatchers.Default) {
                    gate.await()
                    repository.reorder(order)
                }
            }
            gate.complete(Unit)
            jobs.awaitAll()
        }
        val actual = repository.all.first()
        assertTrue(actual.map { it.id } == reversed || actual.map { it.id } == rotated)
        assertEquals((0 until 8).toList(), actual.map { it.position })
        assertEquals(ids.toSet(), actual.map { it.id }.toSet())
    }

    private suspend fun insertSources(count: Int): List<Long> = (0 until count).map { number ->
        repository.insertAndGetId(Link(name = "Source $number"))
    }

    private suspend fun assertOrder(vararg ids: Long) {
        val links = repository.all.first()
        assertEquals(ids.toList(), links.map { it.id })
        assertEquals(ids.indices.toList(), links.map { it.position })
    }

    private fun richLink(name: String) = Link(
        name = name, address = "http://127.0.0.1:18080/subscription", type = Link.Type.Subscription,
        isActive = false, userAgent = "fixture-agent/1", xHwid = "synthetic-link-hwid",
        customHeaders = "X-Fixture: synthetic", subscriptionMetadata = "upload=7; total=512",
        lastRefreshedAt = 123456789L,
    )
}
