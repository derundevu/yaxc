package io.github.derundevu.yaxc.database

import androidx.room.Room
import io.github.derundevu.yaxc.Yaxc
import io.github.derundevu.yaxc.repository.LinkRepository
import io.github.derundevu.yaxc.repository.ProfileRepository
import io.github.derundevu.yaxc.testing.RegressionApplication
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config as RobolectricConfig

@RunWith(RobolectricTestRunner::class)
@RobolectricConfig(application = RegressionApplication::class, sdk = [34])
class YaxcDatabasePersistenceTest {
    @Test
    fun reopenRetainsRepositoryOrderOwnershipContentAndConfiguration() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as Yaxc
        val name = "repository-reopen.db"
        app.deleteDatabase(name)
        var database = open(app, name)
        try {
            val links = LinkRepository(database.linkDao())
            val profiles = ProfileRepository(database.profileDao())
            val active = links.insertAndGetId(Link(name = "Active", address = "http://127.0.0.1/active",
                type = Link.Type.Subscription, userAgent = "reopen-agent/1", xHwid = "synthetic-reopen-hwid",
                customHeaders = "X-Regression: file", subscriptionMetadata = "download=1024; total=4096",
                lastRefreshedAt = 987654321L))
            val inactive = links.insertAndGetId(Link(name = "Inactive", address = "http://127.0.0.1/inactive",
                isActive = false))
            val manual = profiles.create(Profile(name = "Manual", config = "{\"manual\":true}"))
            val activeChild = profiles.create(Profile(linkId = active, name = "Linked α",
                config = "{\"outbounds\":[{\"tag\":\"retained-linked\"}]}"))
            val hiddenChild = profiles.create(Profile(linkId = inactive, name = "Hidden β",
                config = "{\"hidden\":true}"))
            links.reorder(listOf(inactive, active))
            profiles.moveDown(start = 2, end = 0, exclude = hiddenChild)
            val config = Config(
                dns = "{\"servers\":[\"127.0.0.1\"]}", dnsMode = Config.Mode.Merge,
                outbounds = "[{\"protocol\":\"freedom\",\"tag\":\"direct\"}]", outboundsMode = Config.Mode.Replace,
                routing = "{\"rules\":[{\"type\":\"field\",\"outboundTag\":\"direct\"}]}",
                routingMode = Config.Mode.Merge,
            )
            database.configDao().insert(config)
            val expectedLinks = links.all.first()
            val expectedProfiles = listOf(activeChild, manual, hiddenChild).map { profiles.find(it) }
            database.close()

            database = open(app, name)
            val reopenedLinks = LinkRepository(database.linkDao())
            val reopenedProfiles = ProfileRepository(database.profileDao())
            assertEquals(expectedLinks, reopenedLinks.all.first())
            assertEquals(listOf(active), reopenedLinks.activeLinks().map { it.id })
            assertEquals(expectedProfiles, expectedProfiles.map { reopenedProfiles.find(it.id) })
            assertEquals(listOf(activeChild, manual), reopenedProfiles.all.first().map { it.id })
            assertEquals(listOf(activeChild, manual, hiddenChild), database.profileDao().orderedPositions().map { it.id })
            assertEquals(listOf(0, 1, 2), database.profileDao().orderedPositions().map { it.index })
            assertEquals(config, database.configDao().get())

            reopenedLinks.delete(database.linkDao().find(active)!!)
            assertEquals(listOf(manual, hiddenChild), database.profileDao().orderedPositions().map { it.id })
            assertEquals(expectedProfiles[1], reopenedProfiles.find(manual))
            assertEquals(expectedProfiles[2], reopenedProfiles.find(hiddenChild))
            assertEquals(config, database.configDao().get())
            database.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use {
                assertFalse(it.moveToFirst())
            }
        } finally {
            database.close()
            app.deleteDatabase(name)
        }
    }

    private fun open(app: Yaxc, name: String) =
        Room.databaseBuilder(app, YaxcDatabase::class.java, name)
            .allowMainThreadQueries()
            .addMigrations(*YaxcDatabase.migrations)
            .build()
}
