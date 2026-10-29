package io.github.derundevu.yaxc.viewmodel

import android.content.Context
import io.github.derundevu.yaxc.database.Link
import io.github.derundevu.yaxc.database.Profile
import io.github.derundevu.yaxc.testing.MainDispatcherRule
import io.github.derundevu.yaxc.testing.RegressionApplication
import io.github.derundevu.yaxc.testing.RoomTestSupport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
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
class ProfileEditorViewModelTest : RoomTestSupport() {
    @get:Rule val mainDispatcher = MainDispatcherRule()

    @Test
    fun checkedCreateValidatesMergedConfigButPersistsOriginalDocumentAndGeneratedIdentity() = runTest(mainDispatcher.dispatcher) {
        val raw = """{"outbounds":[{"tag":"custom","protocol":"freedom"}],"unknown":{"keep":true}}"""
        var validated = ""
        val editor = editor(text = raw) { _, merged -> validated = merged; "" }
        editor.name = "Synthetic profile"

        editor.save(check = true)

        val rows = app.profileRepository.all.first()
        assertEquals(1, rows.size)
        val stored = app.profileRepository.find(rows.single().id)
        assertTrue(stored.id > 0)
        assertEquals(0, stored.index)
        assertEquals("Synthetic profile", stored.name)
        assertEquals(raw, stored.config)
        assertEquals(editor.selectedSourceId, stored.linkId)
        val runtime = JSONObject(validated)
        assertTrue(runtime.getJSONObject("unknown").getBoolean("keep"))
        assertTrue(runtime.getJSONArray("inbounds").length() > 0)
        assertEquals("127.0.0.1", runtime.getJSONArray("inbounds").getJSONObject(0).getString("listen"))
        assertEquals("custom", runtime.getJSONArray("outbounds").getJSONObject(0).getString("tag"))
    }

    @Test
    fun repeatedNewProfileSaveUpdatesTheSameRowWithoutReorderingOtherProfiles() = runTest(mainDispatcher.dispatcher) {
        val existingId = app.profileRepository.create(Profile(name = "Existing", config = "{}"))
        val editor = editor(text = "{}")
        editor.name = "First"
        editor.save(check = false)
        val inserted = app.profileRepository.all.first().single { it.id != existingId }.id
        editor.name = "Second"
        editor.document.text = """{"unknown":2}"""

        editor.save(check = false)
        editor.save(check = false)

        assertEquals(2, app.profileRepository.all.first().size)
        assertEquals("Second", app.profileRepository.find(inserted).name)
        assertEquals("""{"unknown":2}""", app.profileRepository.find(inserted).config)
        assertEquals(0, app.profileRepository.find(inserted).index)
        assertEquals(1, app.profileRepository.find(existingId).index)
    }

    @Test
    fun existingSubscriptionOwnershipIsPreservedAndCanBeMovedToManualGroup() = runTest(mainDispatcher.dispatcher) {
        val remote = source("Subscription", "http://127.0.0.1/subscription")
        val unrelated = source("Other subscription", "http://127.0.0.1/other")
        val manual = source("Manual", "")
        val original = Profile(name = "From subscription", config = "{}", linkId = remote)
        val id = app.profileRepository.create(original)
        val editor = editor(id = id)

        assertEquals(remote, editor.selectedSourceId)
        assertEquals(setOf(manual, remote), editor.sources.map { it.linkId }.toSet())
        assertFalse(editor.sources.any { it.linkId == unrelated })
        editor.name = "Locally edited"
        editor.save(check = false)
        assertEquals(remote, app.profileRepository.find(id).linkId)
        editor.selectedSourceId = manual
        editor.save(check = false)

        assertEquals(manual, app.profileRepository.find(id).linkId)
        assertEquals("Locally edited", app.profileRepository.find(id).name)
        assertEquals(1, app.profileRepository.all.first().size)
    }

    @Test
    fun newProfileCannotInheritSubscriptionSourceAndCreatesOnlyOneManualGroup() = runTest(mainDispatcher.dispatcher) {
        val remote = source("Subscription", "http://127.0.0.1/subscription")
        val editor = editor(sourceId = remote, text = "{}")
        val groupId = requireNotNull(editor.selectedSourceId)
        val group = app.linkRepository.all.first().single { it.id == groupId }
        assertTrue(group.address.isBlank())
        assertEquals(listOf(groupId), editor.sources.map { it.linkId })
        editor.initialize(0, "Must not overwrite", "bad", remote, null)
        assertEquals("{}", editor.document.text)
        editor.save(check = false)

        assertEquals(groupId, app.profileRepository.all.first().single().link)
        val reopened = editor(id = app.profileRepository.all.first().single().id)
        assertEquals(groupId, reopened.selectedSourceId)
        assertEquals(1, app.linkRepository.all.first().count { it.address.isBlank() })
    }

    @Test
    fun rejectionAndValidatorExceptionLeaveEveryStoredFieldUnchanged() = runTest(mainDispatcher.dispatcher) {
        val manual = source("Manual", "")
        val id = app.profileRepository.create(Profile(name = "Original", config = "{}", linkId = manual))
        val before = app.profileRepository.find(id)
        val editor = editor(id = id) { _, _ -> "native diagnostic" }
        editor.name = "Rejected"
        editor.document.text = """{"changed":true}"""

        assertTrue(editor.save(check = true) is ProfileSaveResult.Invalid)
        assertEquals(before, app.profileRepository.find(id))
        assertFalse(editor.isSaving)
        val throwing = editor(id = id) { _, _ -> throw IllegalArgumentException("validator unavailable") }
        throwing.document.text = """{"outbounds":[{"protocol":"synthetic-invalid"}]}"""
        assertTrue(throwing.save(check = true) is ProfileSaveResult.Invalid)
        assertEquals(before, app.profileRepository.find(id))
        assertFalse(throwing.isSaving)
    }

    @Test
    fun explicitUncheckedSavePersistsRejectedRawDraftWithoutCallingNativeValidatorAgain() = runTest(mainDispatcher.dispatcher) {
        var validations = 0
        val editor = editor(text = """{"outbounds":[{"protocol":"synthetic-invalid"}]}""") { _, _ -> validations++; "rejected" }
        assertTrue(editor.save(check = true) is ProfileSaveResult.Invalid)
        assertTrue(app.profileRepository.all.first().isEmpty())
        editor.document.text = "{incomplete"

        editor.save(check = false)

        assertEquals(1, validations)
        assertEquals("{incomplete", app.profileRepository.find(app.profileRepository.all.first().single().id).config)
    }

    @Test
    fun saveCapturesMetadataAndTextBeforeValidationAndRejectsOverlappingSave() = runTest(mainDispatcher.dispatcher) {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val manual = source("Manual", "")
        val other = source("Other manual", "")
        val editor = editor(sourceId = manual, text = """{"revision":1}""") { _, _ ->
            entered.complete(Unit)
            release.await()
            ""
        }
        editor.name = "Captured"
        var completed: ProfileSaveResult? = null
        val save = launch { completed = editor.save(check = true) }
        entered.await()
        assertTrue(editor.isSaving)
        editor.name = "New draft"
        editor.selectedSourceId = other
        editor.document.text = """{"revision":2}"""
        assertEquals(ProfileSaveResult.NotReady, editor.save(check = false))
        assertTrue(app.profileRepository.all.first().isEmpty())
        release.complete(Unit)
        save.join()

        assertEquals(ProfileSaveResult.Saved, completed)
        val stored = app.profileRepository.find(app.profileRepository.all.first().single().id)
        assertEquals("Captured", stored.name)
        assertEquals(manual, stored.linkId)
        assertEquals("""{"revision":1}""", stored.config)
        assertEquals("New draft", editor.name)
        assertEquals("""{"revision":2}""", editor.document.text)
        assertFalse(editor.isSaving)
        editor.save(check = false)
        assertEquals(1, app.profileRepository.all.first().size)
        assertEquals("New draft", app.profileRepository.find(stored.id).name)
        assertEquals(other, app.profileRepository.find(stored.id).linkId)
    }

    @Test
    fun cancellationDuringValidationResetsBusyDoesNotPersistOrReportSuccessAndAllowsRetry() = runTest(mainDispatcher.dispatcher) {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val editor = editor(text = "{}") { _, _ -> entered.complete(Unit); release.await(); "" }
        var result: ProfileSaveResult? = null
        val save = launch { result = editor.save(check = true) }
        entered.await()
        save.cancelAndJoin()

        assertTrue(save.isCancelled)
        assertEquals(null, result)
        assertFalse(editor.isSaving)
        assertTrue(app.profileRepository.all.first().isEmpty())
        editor.save(check = false)
        assertEquals(1, app.profileRepository.all.first().size)
    }

    @Test
    fun saveBeforeInitializationCannotReportSuccessOrCreateARow() = runTest(mainDispatcher.dispatcher) {
        val editor = ProfileEditorViewModel(app) { _, _ -> error("must not validate") }
        assertEquals(ProfileSaveResult.NotReady, editor.save(check = true))
        assertTrue(app.profileRepository.all.first().isEmpty())
        assertFalse(editor.isSaving)
    }

    @Test
    fun reopenReadsPersistedNameSourceAndLiteralDocumentRatherThanIntentDefaults() = runTest(mainDispatcher.dispatcher) {
        val manual = source("Manual", "")
        val raw = """{ "duplicate":1e+007,"duplicate":-0,"unknown":[true,null] }"""
        val editor = editor(sourceId = manual, text = raw)
        editor.name = "Persisted"
        editor.save(check = false)
        val id = app.profileRepository.all.first().single().id

        val reopened = editor(id = id, text = "wrong intent", sourceId = null)

        assertEquals("Persisted", reopened.name)
        assertEquals(manual, reopened.selectedSourceId)
        assertEquals(raw, reopened.document.text)
    }

    @Test
    fun concurrentPrependCannotCorruptOrderWhenAnOpenProfileIsSaved() = runTest(mainDispatcher.dispatcher) {
        val manual = source("Manual", "")
        val id = app.profileRepository.create(Profile(name = "Original", config = "{}", linkId = manual))
        val opened = editor(id = id)
        val newest = app.profileRepository.create(Profile(name = "Newer", config = "{}", linkId = manual))
        opened.name = "Edited"
        assertEquals(ProfileSaveResult.Saved, opened.save(check = false))
        assertEquals(listOf(newest to 0, id to 1), database.profileDao().orderedPositions().map { it.id to it.index })
        assertEquals("Edited", app.profileRepository.find(id).name)
        assertEquals("Newer", app.profileRepository.find(newest).name)
    }

    @Test
    fun deletedEditingTargetCannotReportSuccessOrRecreateTheRemovedRecord() = runTest(mainDispatcher.dispatcher) {
        val manual = source("Manual", "")
        val id = app.profileRepository.create(Profile(name = "Deleted", config = "{}", linkId = manual))
        val opened = editor(id = id)
        app.profileRepository.remove(app.profileRepository.find(id))
        opened.name = "Unsaved newer draft"
        assertTrue(opened.save(check = false) is ProfileSaveResult.Invalid)
        assertTrue(app.profileRepository.all.first().isEmpty())
        assertEquals("Unsaved newer draft", opened.name)
        assertFalse(opened.isSaving)
    }

    private suspend fun source(name: String, address: String): Long = app.linkRepository.insertAndGetId(
        Link(name = name, address = address, type = if (address.isBlank()) Link.Type.Json else Link.Type.Subscription),
    )

    private suspend fun editor(
        id: Long = 0,
        text: String = "",
        sourceId: Long? = null,
        validator: suspend (Context, String) -> String = { _, _ -> "" },
    ): ProfileEditorViewModel = ProfileEditorViewModel(app, validator).also {
        it.initialize(id, "Intent name", text, sourceId, null)
        withContext(Dispatchers.Default) {
            withTimeout(10_000) { while (it.isLoading) delay(1) }
        }
    }
}
