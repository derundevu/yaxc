package io.github.derundevu.yaxc.viewmodel

import io.github.derundevu.yaxc.database.Link
import io.github.derundevu.yaxc.helper.SubscriptionHttpServer
import io.github.derundevu.yaxc.testing.MainDispatcherRule
import io.github.derundevu.yaxc.testing.RegressionApplication
import io.github.derundevu.yaxc.testing.RoomTestSupport
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = RegressionApplication::class, sdk = [34])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LinksManagerViewModelTest : RoomTestSupport() {
    @get:Rule val mainDispatcher = MainDispatcherRule(UnconfinedTestDispatcher())

    @Test
    fun sourceCreationWaitsForSkippedReportAcknowledgementInsteadOfReturningUnqualifiedSuccess() = runBlocking {
        SubscriptionHttpServer().use { server ->
            server.enqueue("naive+https://test-user:test-pass@proxy.example.org:443#Supported\nanytls://test-pass@proxy.example.org:443#Unsupported")
            val state = LinksManagerViewModel(app)
            state.save(Link(name = "Synthetic source", address = server.url))
            withTimeout(10_000) { while (state.busy) delay(10) }
            val report = requireNotNull(state.refreshResult)
            assertNull(state.outcome.value)
            assertEquals(1, report.refreshed)
            assertEquals("anytls", report.warnings.single().skippedProfiles.single().protocol)
            assertEquals("Unsupported", report.warnings.single().skippedProfiles.single().name)
            val saved = database.linkDao().all().first().single()
            assertEquals(1, database.profileDao().linkProfiles(saved.id).size)
            assertEquals(report.warnings, settings.pendingSubscriptionWarnings())
            state.acknowledgeReport()
            assertNull(state.refreshResult)
            assertTrue(requireNotNull(state.outcome.value).completed)
            assertTrue(settings.pendingSubscriptionWarnings().isEmpty())
        }
    }

    @Test
    fun allUnsupportedCreationShowsReportAndReturnsToDraftWithoutSavingAnEmptySource() = runBlocking {
        SubscriptionHttpServer().use { server ->
            server.enqueue("anytls://test-pass@proxy.example.org:443#Unsupported")
            val state = LinksManagerViewModel(app)
            state.save(Link(name = "Synthetic source", address = server.url))
            withTimeout(10_000) { while (state.busy) delay(10) }
            assertEquals(0, requireNotNull(state.refreshResult).refreshed)
            assertTrue(database.linkDao().all().first().isEmpty())
            assertEquals(0, database.profileDao().count())
            state.acknowledgeReport()
            assertNull(state.refreshResult)
            assertNull(state.outcome.value)
            assertNotNull(state.formError)
        }
    }
}
