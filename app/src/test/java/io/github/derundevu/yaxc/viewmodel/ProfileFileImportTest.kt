package io.github.derundevu.yaxc.viewmodel

import android.net.Uri
import io.github.derundevu.yaxc.testing.MainDispatcherRule
import io.github.derundevu.yaxc.testing.RegressionApplication
import io.github.derundevu.yaxc.testing.RoomTestSupport
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = RegressionApplication::class, sdk = [34])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ProfileFileImportTest : RoomTestSupport() {
    @get:Rule val mainDispatcher = MainDispatcherRule(UnconfinedTestDispatcher())

    @Test
    fun mixedFileImportsSupportedRecordsOnceAndRetainsSkippedReportAcrossRepeatedInitialization() = runBlocking {
        val file = File(app.cacheDir, "synthetic-mixed-import.txt")
        file.writeText("naive+https://test-user:test-pass@proxy.example.org:443#Supported\nanytls://test-pass@proxy.example.org:443#Unsupported")
        try {
            val state = ProfileEditorViewModel(app) { _, _ -> "" }
            state.initialize(0, "", "", null, Uri.fromFile(file))
            withTimeout(10_000) { while (state.importReport == null) delay(10) }
            assertTrue(state.fileImportPending)
            val report = requireNotNull(state.importReport)
            assertEquals(1, report.refreshed)
            assertEquals("anytls", report.warnings.single().skippedProfiles.single().protocol)
            assertEquals(1, database.profileDao().count())
            state.initialize(0, "", "", null, Uri.fromFile(file))
            assertSame(report, state.importReport)
            assertEquals(1, database.profileDao().count())
            assertEquals(report.warnings, settings.pendingSubscriptionWarnings())
            state.acknowledgeImportReport()
            assertTrue(settings.pendingSubscriptionWarnings().isEmpty())
        } finally {
            file.delete()
        }
    }

    @Test
    fun singleNaiveFileStillOpensAnUnsavedEditableProfileRatherThanAutoImportingIt() = runBlocking {
        val file = File(app.cacheDir, "synthetic-single-import.txt")
        file.writeText("naive+https://test-user:test-pass@proxy.example.org:443#Supported")
        try {
            val state = ProfileEditorViewModel(app) { _, _ -> "" }
            state.initialize(0, "", "", null, Uri.fromFile(file))
            withTimeout(10_000) { while (state.isLoading) delay(10) }
            assertFalse(state.fileImportPending)
            assertNull(state.importReport)
            assertEquals(0, database.profileDao().count())
            assertEquals("Supported", state.name)
            assertEquals("naive", org.json.JSONObject(state.document.text).getJSONArray("outbounds")
                .getJSONObject(0).getString("protocol"))
        } finally {
            file.delete()
        }
    }
}
