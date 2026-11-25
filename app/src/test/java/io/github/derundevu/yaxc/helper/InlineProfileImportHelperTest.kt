package io.github.derundevu.yaxc.helper

import io.github.derundevu.yaxc.database.Link
import io.github.derundevu.yaxc.database.Profile
import io.github.derundevu.yaxc.testing.RegressionApplication
import io.github.derundevu.yaxc.testing.RoomTestSupport
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = RegressionApplication::class, sdk = [34])
class InlineProfileImportHelperTest : RoomTestSupport() {
    @Test
    fun mixedBatchAppendsToManualGroupWithoutReplacingProfilesOrTouchingSelectedSubscription() = runBlocking {
        val subscription = Link(name = "Remote", address = "https://subscription.example.org/profiles")
        subscription.id = database.linkDao().insertAtEnd(subscription)
        val manual = Link(name = "Manual")
        manual.id = database.linkDao().insertAtEnd(manual)
        val remoteProfile = Profile(name = "Remote saved", linkId = subscription.id, config = "{}")
        remoteProfile.id = database.profileDao().create(remoteProfile)
        val manualProfile = Profile(name = "Manual saved", linkId = manual.id, config = "{}")
        manualProfile.id = database.profileDao().create(manualProfile)
        settings.selectedProfile = remoteProfile.id
        val skipped = listOf(SubscriptionRefreshHelper.SkippedProfile("Unsupported", "anytls"))
        val parsed = SubscriptionRefreshHelper.ParsedLinkSource(
            Link.Type.Subscription,
            listOf(Profile(name = "First", config = "{\"first\":true}"), Profile(name = "Second", config = "{\"second\":true}")),
            skippedProfiles = skipped,
        )
        val result = InlineProfileImportHelper.append(app, settings, parsed, subscription.id)
        assertEquals(listOf("Remote saved"), database.profileDao().linkProfiles(subscription.id).map { it.name })
        assertEquals(setOf("Manual saved", "First", "Second"), database.profileDao().linkProfiles(manual.id).map { it.name }.toSet())
        assertEquals(listOf("First", "Second", "Manual saved", "Remote saved"), database.profileDao().all().first().map { it.name })
        assertEquals(listOf("First", "Second"), result.changes.single().added)
        assertEquals(remoteProfile.id, settings.selectedProfile)
        assertEquals(skipped, result.warnings.single().skippedProfiles)
        assertEquals(manual.id, result.warnings.single().sourceId)
        assertEquals(result.warnings, settings.pendingSubscriptionWarnings())
        assertEquals(subscription, database.linkDao().find(subscription.id))
    }

    @Test
    fun allUnsupportedBatchCannotCreateAnEmptyManualGroup() = runBlocking {
        val parsed = SubscriptionRefreshHelper.ParsedLinkSource(
            Link.Type.Subscription, emptyList(),
            skippedProfiles = listOf(SubscriptionRefreshHelper.SkippedProfile("Unsupported", "tuic")),
        )
        val error = try { InlineProfileImportHelper.append(app, settings, parsed, null); null }
            catch (error: IllegalArgumentException) { error }
        assertNotNull(error)
        assertTrue(database.linkDao().all().first().isEmpty())
        assertEquals(0, database.profileDao().count())
    }
}
