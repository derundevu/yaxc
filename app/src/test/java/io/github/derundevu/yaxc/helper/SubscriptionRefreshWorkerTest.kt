package io.github.derundevu.yaxc.helper

import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import io.github.derundevu.yaxc.database.Link
import io.github.derundevu.yaxc.testing.MainDispatcherRule
import io.github.derundevu.yaxc.testing.RegressionApplication
import io.github.derundevu.yaxc.testing.RoomTestSupport
import io.github.derundevu.yaxc.worker.SubscriptionRefreshWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = RegressionApplication::class, sdk = [34])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SubscriptionRefreshWorkerTest : RoomTestSupport() {
    @get:Rule val mainDispatcher = MainDispatcherRule(UnconfinedTestDispatcher())
    private fun worker() = TestListenableWorkerBuilder<SubscriptionRefreshWorker>(app).build()

    @Test
    fun disabledRefreshLeavesAnOtherwiseDueSourceUntouchedWithoutRequestingIt() = runBlocking {
        SubscriptionHttpServer().use { server ->
            settings.refreshLinksOnOpen = false
            val link = Link(address = server.url, name = "disabled")
            link.id = database.linkDao().insertAtEnd(link)
            assertEquals(ListenableWorker.Result.success(), worker().doWork())
            assertEquals(0, server.requestCount.get())
            assertEquals(link, database.linkDao().find(link.id))
            assertTrue(database.profileDao().linkProfiles(link.id).isEmpty())
        }
    }

    @Test
    fun enabledWorkerRefreshesOnlyDueSourcesAndPersistsTheirProfiles() = runBlocking {
        SubscriptionHttpServer().use { server ->
            settings.refreshLinksOnOpen = true
            val due = Link(address = server.url, name = "due")
            due.id = database.linkDao().insertAtEnd(due)
            val future = Link(address = server.url, name = "future", lastRefreshedAt = Long.MAX_VALUE)
            future.id = database.linkDao().insertAtEnd(future)
            server.enqueue("""{"remarks":"worker profile","outbounds":[{"protocol":"freedom"}]}""")
            assertEquals(ListenableWorker.Result.success(), worker().doWork())
            assertEquals("worker profile", database.profileDao().linkProfiles(due.id).single().name)
            assertTrue(database.linkDao().find(due.id)!!.lastRefreshedAt > 0L)
            assertEquals(future, database.linkDao().find(future.id))
            assertEquals(1, server.requestCount.get())
        }
    }

    @Test
    fun individualHttpFailureDoesNotRetryAnOtherwiseCompletedBatchOrChangeItsSource() = runBlocking {
        SubscriptionHttpServer().use { server ->
            settings.refreshLinksOnOpen = true
            val link = Link(address = server.url, name = "unavailable")
            link.id = database.linkDao().insertAtEnd(link)
            server.enqueue("synthetic unavailable", 503)
            assertEquals(ListenableWorker.Result.success(), worker().doWork())
            assertEquals(link, database.linkDao().find(link.id))
            assertTrue(database.profileDao().linkProfiles(link.id).isEmpty())
        }
    }

    @Test
    fun infrastructureFailureBeforeBatchReturnsRetry() = runBlocking {
        settings.refreshLinksOnOpen = true
        database.openHelper.writableDatabase.execSQL("DROP TABLE links")
        assertEquals(ListenableWorker.Result.retry(), worker().doWork())
    }

    @Test
    fun cancellationDuringHttpRemainsCancellationRatherThanRetryOrSuccess() = runBlocking {
        SubscriptionHttpServer().use { server ->
            settings.refreshLinksOnOpen = true
            val link = Link(address = server.url, name = "cancelled")
            link.id = database.linkDao().insertAtEnd(link)
            val exchange = server.enqueue("""{"outbounds":[{"protocol":"freedom"}]}""", held = true)
            val pending = async(Dispatchers.IO) { worker().doWork() }
            exchange.awaitRequest()
            pending.cancel(CancellationException("synthetic worker cancellation"))
            exchange.release()
            val failure = try { pending.await(); null } catch (error: CancellationException) { error }
            assertNotNull(failure)
            pending.join()
            assertEquals(link, database.linkDao().find(link.id))
            assertTrue(database.profileDao().linkProfiles(link.id).isEmpty())
        }
    }
}
