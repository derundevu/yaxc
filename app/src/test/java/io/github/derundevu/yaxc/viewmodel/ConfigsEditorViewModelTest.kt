package io.github.derundevu.yaxc.viewmodel

import androidx.room.withTransaction
import kotlinx.coroutines.CompletableDeferred
import io.github.derundevu.yaxc.database.Config as DatabaseConfig
import io.github.derundevu.yaxc.presentation.configs.ConfigSection
import io.github.derundevu.yaxc.testing.MainDispatcherRule
import io.github.derundevu.yaxc.testing.RegressionApplication
import io.github.derundevu.yaxc.testing.RoomTestSupport
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = RegressionApplication::class, sdk = [34])
class ConfigsEditorViewModelTest : RoomTestSupport() {
    @get:Rule val mainDispatcher = MainDispatcherRule()

    @Test
    fun savePersistsAllSectionsAndModesTogetherAndReopenReadsThem() = runTest(mainDispatcher.dispatcher) {
        val editor = editor()
        val texts = mapOf(
            ConfigSection.Log to """{"loglevel":"debug","unknown":1}""",
            ConfigSection.Dns to """{"servers":["127.0.0.1"],"unknown":true}""",
            ConfigSection.Inbounds to """[{"tag":"loopback","listen":"127.0.0.1","port":19080}]""",
            ConfigSection.Outbounds to """[{"tag":"direct","protocol":"freedom"}]""",
            ConfigSection.Routing to JSONObject(editor.documents.getValue(ConfigSection.Routing).text)
                .put("unknown", "preserved").toString(4),
        )
        texts.forEach { (section, text) -> editor.documents.getValue(section).text = text }
        val modes = mapOf(
            ConfigSection.Log to DatabaseConfig.Mode.Replace,
            ConfigSection.Dns to DatabaseConfig.Mode.Merge,
            ConfigSection.Inbounds to DatabaseConfig.Mode.Disable,
            ConfigSection.Outbounds to DatabaseConfig.Mode.Replace,
            ConfigSection.Routing to DatabaseConfig.Mode.Merge,
        )
        modes.forEach { (section, mode) -> editor.selectedSection = section; editor.selectMode(mode) }

        assertTrue(editor.save())

        val stored = requireNotNull(database.configDao().get())
        assertEquals(texts.getValue(ConfigSection.Log), stored.log)
        assertEquals(texts.getValue(ConfigSection.Dns), stored.dns)
        assertEquals(texts.getValue(ConfigSection.Inbounds), stored.inbounds)
        assertEquals(texts.getValue(ConfigSection.Outbounds), stored.outbounds)
        assertEquals(texts.getValue(ConfigSection.Routing), stored.routing)
        assertFalse(editor.isSaving)
        val reopened = editor()
        assertEquals(modes, reopened.modes)
        texts.filterKeys { it != ConfigSection.Routing }.forEach { (section, text) ->
            assertEquals(text, reopened.documents.getValue(section).text)
        }
        assertEquals("preserved", JSONObject(reopened.documents.getValue(ConfigSection.Routing).text).getString("unknown"))
    }

    @Test
    fun oneInvalidSectionLeavesWholeRowAndModesUnchangedAndSelectsThatSection() = runTest(mainDispatcher.dispatcher) {
        val editor = editor()
        val before = database.configDao().get()
        editor.documents.getValue(ConfigSection.Log).text = """{"changed":1}"""
        editor.selectedSection = ConfigSection.Log
        editor.selectMode(DatabaseConfig.Mode.Replace)
        val broken = """[{"tag":"unfinished",}]"""
        editor.documents.getValue(ConfigSection.Outbounds).text = broken
        editor.selectedSection = ConfigSection.Dns
        editor.selectMode(DatabaseConfig.Mode.Merge)

        assertFalse(editor.save())

        assertEquals(before, database.configDao().get())
        assertEquals(ConfigSection.Outbounds, editor.selectedSection)
        assertEquals(broken, editor.documents.getValue(ConfigSection.Outbounds).text)
        assertFalse(editor.isSaving)
        editor.documents.getValue(ConfigSection.Outbounds).text = "[]"
        assertTrue(editor.save())
        assertEquals("""{"changed":1}""", database.configDao().get()!!.log)
        assertEquals(DatabaseConfig.Mode.Replace, database.configDao().get()!!.logMode)
    }

    @Test
    fun disabledSectionsStillRejectWrongJsonRootRatherThanPersistingBrokenDrafts() = runTest(mainDispatcher.dispatcher) {
        val editor = editor()
        val before = database.configDao().get()
        editor.selectedSection = ConfigSection.Inbounds
        editor.selectMode(DatabaseConfig.Mode.Disable)
        editor.documents.getValue(ConfigSection.Inbounds).text = "{}"
        editor.selectedSection = ConfigSection.Routing

        assertFalse(editor.save())

        assertEquals(ConfigSection.Inbounds, editor.selectedSection)
        assertEquals(before, database.configDao().get())
    }

    @Test
    fun cancellationWhileDatabaseIsBusyDoesNotPersistOrReportSuccessAndAllowsRetry() = runTest(mainDispatcher.dispatcher) {
        val editor = editor()
        val before = database.configDao().get()
        editor.documents.getValue(ConfigSection.Log).text = """{"draft":true}"""
        val locked = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val transaction = launch {
            database.withTransaction { locked.complete(Unit); release.await() }
        }
        locked.await()
        var result: Boolean? = null
        val save = launch(start = CoroutineStart.UNDISPATCHED) { result = editor.save() }
        try {
            assertTrue(editor.isSaving)
            assertFalse(editor.save())
            save.cancel()
        } finally {
            release.complete(Unit)
        }
        transaction.join()
        save.join()

        assertTrue(save.isCancelled)
        assertEquals(null, result)
        assertFalse(editor.isSaving)
        assertEquals(before, database.configDao().get())
        assertTrue(editor.save())
        assertEquals("""{"draft":true}""", database.configDao().get()!!.log)
    }

    @Test
    fun saveUsesCapturedTextsAndModesWithoutErasingNewerUnsavedEdits() = runTest(mainDispatcher.dispatcher) {
        val editor = editor()
        editor.documents.getValue(ConfigSection.Dns).text = """{"snapshot":1}"""
        editor.selectedSection = ConfigSection.Dns
        editor.selectMode(DatabaseConfig.Mode.Merge)
        val locked = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val transaction = launch {
            database.withTransaction { locked.complete(Unit); release.await() }
        }
        locked.await()
        var result = false
        val save = launch(start = CoroutineStart.UNDISPATCHED) { result = editor.save() }
        try {
            editor.documents.getValue(ConfigSection.Dns).text = """{"snapshot":2}"""
            editor.selectMode(DatabaseConfig.Mode.Replace)
        } finally {
            release.complete(Unit)
        }
        transaction.join()
        save.join()

        assertTrue(result)
        assertEquals("""{"snapshot":1}""", database.configDao().get()!!.dns)
        assertEquals(DatabaseConfig.Mode.Merge, database.configDao().get()!!.dnsMode)
        assertEquals("""{"snapshot":2}""", editor.documents.getValue(ConfigSection.Dns).text)
        assertEquals(DatabaseConfig.Mode.Replace, editor.modes.getValue(ConfigSection.Dns))
        assertTrue(editor.save())
        assertEquals("""{"snapshot":2}""", database.configDao().get()!!.dns)
        assertEquals(DatabaseConfig.Mode.Replace, database.configDao().get()!!.dnsMode)
    }

    @Test
    fun repeatedSaveKeepsOneConfigRowAndPersistsLatestSectionEdits() = runTest(mainDispatcher.dispatcher) {
        val editor = editor()
        repeat(3) { revision ->
            editor.documents.getValue(ConfigSection.Log).text = """{"revision":$revision}"""
            editor.selectedSection = ConfigSection.Log
            editor.selectMode(DatabaseConfig.Mode.entries[revision])
            assertTrue(editor.save())
        }

        val cursor = database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM configs")
        cursor.use { assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)) }
        assertEquals("""{"revision":2}""", database.configDao().get()!!.log)
        assertEquals(DatabaseConfig.Mode.Merge, database.configDao().get()!!.logMode)
    }

    private suspend fun editor(): ConfigsEditorViewModel = ConfigsEditorViewModel(app).also {
        withContext(Dispatchers.Default) {
            withTimeout(10_000) { while (it.isLoading) delay(1) }
        }
    }
}
