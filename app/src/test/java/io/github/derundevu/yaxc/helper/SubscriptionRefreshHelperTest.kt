package io.github.derundevu.yaxc.helper

import io.github.derundevu.yaxc.Settings
import io.github.derundevu.yaxc.database.Link
import io.github.derundevu.yaxc.database.Profile
import io.github.derundevu.yaxc.dto.SubscriptionMetadata
import io.github.derundevu.yaxc.testing.MainDispatcherRule
import io.github.derundevu.yaxc.testing.RegressionApplication
import io.github.derundevu.yaxc.testing.RoomTestSupport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(application = RegressionApplication::class, sdk = [34])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SubscriptionRefreshHelperTest : RoomTestSupport() {
    @get:Rule val mainDispatcher = MainDispatcherRule(UnconfinedTestDispatcher())

    private val links get() = database.linkDao()
    private val profiles get() = database.profileDao()
    private fun helper() = SubscriptionRefreshHelper(app, settings)
    private fun body(vararg names: String): String = JSONArray().apply {
        names.forEach { name ->
            put(JSONObject().put("remarks", name)
                .put("outbounds", JSONArray().put(JSONObject().put("protocol", "freedom")))
                .put("future", JSONObject().put("identity", name).put("counter", 9007199254740993L)))
        }
    }.toString()

    private suspend fun source(url: String, name: String = "local source"): Link =
        Link(address = url, name = name).also { it.id = links.insertAtEnd(it) }

    private suspend fun seed(server: SubscriptionHttpServer, vararg names: String): Link {
        val link = source(server.url)
        server.enqueue(body(*names))
        helper().saveResolvedLink(link, helper().resolveProfiles(link), 1_000L)
        return link
    }

    private suspend fun expectFailure(block: suspend () -> Unit): Throwable {
        try { block() } catch (error: Throwable) { return error }
        throw AssertionError("Expected operation to fail")
    }

    private suspend fun assertDenseIndices() {
        val positions = profiles.orderedPositions()
        assertEquals(positions.indices.toList(), positions.map { it.index })
        assertEquals(positions.size, positions.map { it.id }.distinct().size)
    }

    @Test
    fun equalGrowAndShrinkKeepPositionalIdsGlobalOrderAndSelection() = runBlocking {
        SubscriptionHttpServer().use { server ->
            val unrelated = Profile(name = "standalone", config = body("standalone"))
            val unrelatedId = profiles.create(unrelated)
            val link = seed(server, "A", "B")
            val initial = profiles.linkProfiles(link.id)
            assertEquals(listOf("B", "A"), initial.map { it.name })
            settings.selectedProfile = initial.first().id

            server.enqueue(body("C", "D"))
            assertEquals(SubscriptionRefreshHelper.RefreshResult(1, 1, 0), helper().refreshLinks(link.id))
            val equal = profiles.linkProfiles(link.id)
            assertEquals(initial.map { it.id }, equal.map { it.id })
            assertEquals(listOf("D", "C"), equal.map { it.name })
            assertEquals(initial.first().id, settings.selectedProfile)
            assertDenseIndices()

            server.enqueue(body("E", "F", "G"))
            helper().refreshLinks(link.id)
            val grown = profiles.linkProfiles(link.id)
            assertEquals(listOf("G", "F", "E"), grown.map { it.name })
            assertEquals(initial.map { it.id }, grown.take(2).map { it.id })
            assertTrue(grown.last().id !in initial.map { it.id })
            assertEquals("standalone", profiles.find(unrelatedId).name)
            assertDenseIndices()

            settings.selectedProfile = grown.last().id
            server.enqueue(body("H"))
            helper().refreshLinks(link.id)
            val shrunk = profiles.linkProfiles(link.id)
            assertEquals(listOf(initial.first().id), shrunk.map { it.id })
            assertEquals("H", shrunk.single().name)
            assertEquals(0L, settings.selectedProfile)
            assertEquals(unrelatedId, profiles.orderedPositions().last().id)
            assertDenseIndices()
            assertEquals(4, server.requestCount.get())
        }
    }

    @Test
    fun requestOverridesAndResponseMetadataTravelThroughRealHttpIntoRoom() = runBlocking {
        SubscriptionHttpServer().use { server ->
            settings.userAgent = "global-agent"
            val link = source(server.url).apply {
                userAgent = "  source-agent  "
                xHwid = "  synthetic-source-device  "
                customHeaders = "X-HWID: superseded\r\nX-Device-OS: TestAndroid\r\nX-Test: value:with:colons\r\ninvalid\r\n: ignored"
            }
            links.update(link)
            val exchange = server.enqueue(body("Тест 🌍"), headers = mapOf(
                "Profile-Title" to "base64:" + Base64.getEncoder().encodeToString("План 🌍".toByteArray()),
                "Profile-Update-Interval" to "2",
                "Subscription-Userinfo" to "upload=2147483648; download=4294967296; total=1099511627776; expire=1893456000",
                "Support-Url" to "http://127.0.0.1/support",
                "Profile-Web-Page-Url" to "http://127.0.0.1/account",
            ))
            assertEquals(1, helper().refreshLinks(link.id).refreshed)
            val request = exchange.awaitRequest()
            assertEquals("/subscription", request.path)
            assertEquals("source-agent", request.headers["user-agent"])
            assertEquals("synthetic-source-device", request.headers["x-hwid"])
            assertEquals("TestAndroid", request.headers["x-device-os"])
            assertEquals("value:with:colons", request.headers["x-test"])
            assertFalse(request.headers["x-ver-os"].isNullOrBlank())
            val saved = links.find(link.id)!!
            assertEquals("План 🌍", saved.name)
            assertTrue(saved.lastRefreshedAt > 1_000L)
            val metadata = SubscriptionMetadata.fromJsonString(saved.subscriptionMetadata)!!
            assertEquals(120, metadata.updateIntervalMinutes)
            assertEquals(6_442_450_944L, metadata.usedBytes)
            assertEquals(1_099_511_627_776L, metadata.totalBytes)
            assertEquals(1_893_456_000L, metadata.expireAtEpochSeconds)
            assertEquals("http://127.0.0.1/support", metadata.supportUrl)
            assertEquals("http://127.0.0.1/account", metadata.profileWebPageUrl)
            val config = JSONObject(profiles.linkProfiles(link.id).single().config)
            assertEquals(9007199254740993L, config.getJSONObject("future").getLong("counter"))
            assertFalse(config.has("remarks"))
        }
    }

    @Test
    fun dueBoundariesHonorServerIntervalMinimumNeverRefreshedAndFutureClock() = runBlocking {
        SubscriptionHttpServer().use { server ->
            settings.refreshLinksIntervalMinutes = 60
            val now = 10_000_000L
            val interval = 60 * 60_000L
            val cases = listOf(
                Link(name = "before", lastRefreshedAt = now - interval + 1),
                Link(name = "exact", lastRefreshedAt = now - interval),
                Link(name = "after", lastRefreshedAt = now - interval - 1),
                Link(name = "never", lastRefreshedAt = 0),
                Link(name = "future", lastRefreshedAt = now + interval),
                Link(name = "server-not-yet", lastRefreshedAt = now - interval,
                    subscriptionMetadata = SubscriptionMetadata(updateIntervalMinutes = 120).toJsonString()),
                Link(name = "server-exact", lastRefreshedAt = now - 2 * interval,
                    subscriptionMetadata = SubscriptionMetadata(updateIntervalMinutes = 120).toJsonString()),
                Link(name = "minimum-exact", lastRefreshedAt = now - Settings.MIN_REFRESH_LINKS_INTERVAL_MINUTES * 60_000L,
                    subscriptionMetadata = SubscriptionMetadata(updateIntervalMinutes = 1).toJsonString()),
                Link(name = "inactive", isActive = false),
                Link(name = "blank", address = "  \r\n"),
            )
            cases.forEach { link ->
                if (link.name != "blank") link.address = server.url
                link.id = links.insertAtEnd(link)
            }
            repeat(5) { server.enqueue(body("due-$it")) }
            assertEquals(SubscriptionRefreshHelper.RefreshResult(5, 5, 0), helper().refreshDueLinks(now))
            assertEquals(setOf("before", "future", "server-not-yet", "inactive", "blank"),
                cases.filter { links.find(it.id)!!.lastRefreshedAt != now }.map { it.name }.toSet())
            assertEquals(5, server.requestCount.get())
            val before = cases.first()
            server.enqueue(body("manual"))
            assertEquals(SubscriptionRefreshHelper.RefreshResult(1, 1, 0), helper().refreshLinks(before.id))
            assertEquals("manual", profiles.linkProfiles(before.id).single().name)
            assertEquals(SubscriptionRefreshHelper.RefreshResult(0, 0, 0), helper().refreshLinks(cases[8].id))
            assertEquals(SubscriptionRefreshHelper.RefreshResult(0, 0, 0), helper().refreshLinks(cases[9].id))
        }
    }

    @Test
    fun httpEmptyMalformedAndMixedBatchesFailWithoutDestroyingStoredProfiles() = runBlocking {
        SubscriptionHttpServer().use { server ->
            val link = seed(server, "original")
            val original = profiles.linkProfiles(link.id)
            val saved = links.find(link.id)
            val failures = listOf(
                503 to body("must not apply"), 204 to "", 200 to "", 200 to " \r\n\t ",
                200 to "{not-json", 200 to "[]", 200 to "{}", 200 to "null", 200 to "not a profile",
                200 to "[${body("valid").removeSurrounding("[", "]")},null]",
                200 to "[${body("valid").removeSurrounding("[", "]")},7]",
                200 to "${vless("valid")}\r\nnot a profile",
            )
            failures.forEach { (status, response) ->
                server.enqueue(response, status)
                assertEquals("status=$status response=$response", SubscriptionRefreshHelper.RefreshResult(1, 0, 1),
                    helper().refreshLinks(link.id))
                assertEquals(original, profiles.linkProfiles(link.id))
                assertEquals(saved, links.find(link.id))
            }
        }
    }

    @Test
    fun batchCountsAndRowsAttributeFailuresToTheirOwnSource() = runBlocking {
        SubscriptionHttpServer().use { server ->
            val first = source(server.url, "first")
            val second = source(server.url, "second")
            val third = source(server.url, "third")
            server.enqueue(body("first-profile"))
            server.enqueue("unavailable", 500)
            server.enqueue(body("third-profile"))
            assertEquals(SubscriptionRefreshHelper.RefreshResult(3, 2, 1), helper().refreshLinks())
            assertEquals("first-profile", profiles.linkProfiles(first.id).single().name)
            assertTrue(profiles.linkProfiles(second.id).isEmpty())
            assertEquals(0L, links.find(second.id)!!.lastRefreshedAt)
            assertEquals("third-profile", profiles.linkProfiles(third.id).single().name)
            assertDenseIndices()
        }
    }

    @Test
    fun failedProfileUpdateRollsBackSourceMetadataAndEveryPriorProfileWrite() = runBlocking {
        SubscriptionHttpServer().use { server ->
            val link = seed(server, "first", "second")
            val beforeLink = links.find(link.id)
            val beforeProfiles = profiles.linkProfiles(link.id)
            settings.selectedProfile = beforeProfiles.first().id
            database.openHelper.writableDatabase.execSQL("""
                CREATE TRIGGER reject_profile_update BEFORE UPDATE ON profiles
                WHEN NEW.name = 'reject' BEGIN SELECT RAISE(ABORT, 'synthetic rejection'); END
            """.trimIndent())
            server.enqueue(body("reject", "changed-before-failure"), headers = mapOf("Profile-Title" to "must rollback"))
            val parsed = helper().resolveProfiles(link)
            expectFailure { helper().saveResolvedLink(link, parsed, 90_000L) }
            assertEquals(beforeLink, links.find(link.id))
            assertEquals(beforeLink, link)
            assertEquals(beforeProfiles, profiles.linkProfiles(link.id))
            assertEquals(beforeProfiles.first().id, settings.selectedProfile)
        }
    }

    @Test
    fun failedShrinkRollsBackEarlierDeletionAndDoesNotClearSelection() = runBlocking {
        SubscriptionHttpServer().use { server ->
            val link = seed(server, "first", "second", "third")
            val before = profiles.linkProfiles(link.id)
            val beforeLink = links.find(link.id)
            settings.selectedProfile = before[1].id
            database.openHelper.writableDatabase.execSQL("""
                CREATE TRIGGER reject_profile_delete BEFORE DELETE ON profiles
                WHEN OLD.name = 'first' BEGIN SELECT RAISE(ABORT, 'synthetic rejection'); END
            """.trimIndent())
            server.enqueue(body("remaining"))
            assertEquals(SubscriptionRefreshHelper.RefreshResult(1, 0, 1), helper().refreshLinks(link.id))
            assertEquals(before, profiles.linkProfiles(link.id))
            assertEquals(beforeLink, links.find(link.id))
            assertEquals(before[1].id, settings.selectedProfile)
            assertDenseIndices()
        }
    }

    @Test
    fun failedNewSourceInsertDoesNotLeaveAnOrphanSourceOrMutateCaller() = runBlocking {
        SubscriptionHttpServer().use { server ->
            val link = Link(address = server.url, name = "unsaved")
            val before = link.copy()
            database.openHelper.writableDatabase.execSQL("""
                CREATE TRIGGER reject_profile_insert BEFORE INSERT ON profiles
                BEGIN SELECT RAISE(ABORT, 'synthetic rejection'); END
            """.trimIndent())
            server.enqueue(body("new"))
            expectFailure { helper().saveResolvedLink(link, helper().resolveProfiles(link), 99L) }
            assertEquals(before, link)
            assertTrue(links.activeLinks().isEmpty())
            assertTrue(profiles.orderedPositions().isEmpty())
        }
    }

    @Test
    fun editedSourceCannotBeOverwrittenByAnInFlightOldResponse() = runBlocking {
        val edits: List<(Link) -> Link> = listOf(
            { it.copy(address = "http://127.0.0.1:1/new-source") },
            { it.copy(name = "user renamed") },
            { it.copy(userAgent = "edited-agent") },
            { it.copy(xHwid = "synthetic-new-device") },
            { it.copy(customHeaders = "X-Test: edited") },
            { it.copy(isActive = false) },
        )
        edits.forEach { edit ->
            SubscriptionHttpServer().use { server ->
                val link = seed(server, "original")
                val before = profiles.linkProfiles(link.id)
                val exchange = server.enqueue(body("stale"), held = true)
                val pending = async(Dispatchers.IO) { helper().refreshLinks(link.id) }
                exchange.awaitRequest()
                val changed = edit(links.find(link.id)!!)
                links.update(changed)
                exchange.release()
                assertEquals(SubscriptionRefreshHelper.RefreshResult(1, 0, 1), pending.await())
                assertEquals(changed, links.find(link.id))
                assertEquals(before, profiles.linkProfiles(link.id))
            }
        }
    }

    @Test
    fun deletedSourceIsNotRecreatedByAnInFlightResponse() = runBlocking {
        SubscriptionHttpServer().use { server ->
            val link = seed(server, "original")
            val exchange = server.enqueue(body("stale"), held = true)
            val pending = async(Dispatchers.IO) { helper().refreshLinks(link.id) }
            exchange.awaitRequest()
            links.delete(link)
            exchange.release()
            assertEquals(SubscriptionRefreshHelper.RefreshResult(1, 0, 1), pending.await())
            assertNull(links.find(link.id))
            assertTrue(profiles.linkProfiles(link.id).isEmpty())
        }
    }

    @Test
    fun overlappingHelpersKeepNewestRequestEvenWithIdenticalApplyTimestamps() = runBlocking {
        SubscriptionHttpServer().use { server ->
            val link = seed(server, "original")
            val old = server.enqueue(body("older"), held = true)
            val pending = async(Dispatchers.IO) {
                runCatching {
                    val helper = helper()
                    helper.saveResolvedLink(link.copy(), helper.resolveProfiles(link.copy()), 1_000L)
                }
            }
            old.awaitRequest()
            server.enqueue(body("newer"))
            val latestHelper = helper()
            latestHelper.saveResolvedLink(link.copy(), latestHelper.resolveProfiles(link.copy()), 1_000L)
            old.release()
            val failure = pending.await().exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertEquals("newer", profiles.linkProfiles(link.id).single().name)
            assertEquals(1_000L, links.find(link.id)!!.lastRefreshedAt)
        }
    }

    @Test
    fun cancellingNetworkRefreshPropagatesAndNeverAppliesOrStartsTheNextSource() = runBlocking {
        SubscriptionHttpServer().use { server ->
            val first = source(server.url)
            source(server.url, "must not start")
            val exchange = server.enqueue(body("cancelled"), held = true)
            val pending = async(Dispatchers.IO) { helper().refreshLinks() }
            exchange.awaitRequest()
            pending.cancel(CancellationException("synthetic cancellation"))
            exchange.release()
            assertTrue(expectFailure { pending.await() } is CancellationException)
            pending.join()
            assertEquals(1, server.requestCount.get())
            assertEquals(0L, links.find(first.id)!!.lastRefreshedAt)
            assertTrue(profiles.orderedPositions().isEmpty())
        }
    }

    @Test
    fun rawBase64AndWhitespaceVlessBatchesPreserveUnicodeAndAllMembers() = runBlocking {
        SubscriptionHttpServer().use { server ->
            val responses = listOf(
                "\uFEFF \r\n${body("Первый 🌍", "Second")}\r\n",
                Base64.getMimeEncoder(24, "\r\n".toByteArray()).encodeToString(body("Первый 🌍", "Second").toByteArray()),
                " \r\n \t${vless("First")} \r\n\r\n\t${vless("%D0%A2%D0%B5%D1%81%D1%82")} \t\r\n",
            )
            responses.forEachIndexed { index, response ->
                val link = source(server.url, "batch-$index")
                server.enqueue(response)
                assertEquals(1, helper().refreshLinks(link.id).refreshed)
                val saved = profiles.linkProfiles(link.id)
                assertEquals(2, saved.size)
                assertTrue(saved.all { it.linkId == link.id })
                if (index < 2) assertEquals(listOf("Second", "Первый 🌍"), saved.map { it.name })
                else {
                    assertEquals(listOf("Тест", "First"), saved.map { it.name })
                    saved.forEach {
                        val outbound = JSONObject(it.config).getJSONArray("outbounds").getJSONObject(0)
                        assertEquals("vless", outbound.getString("protocol"))
                        assertEquals("127.0.0.1", outbound.getJSONObject("settings").getJSONArray("vnext")
                            .getJSONObject(0).getString("address"))
                    }
                }
                assertEquals(if (index == 0) Link.Type.Json else Link.Type.Subscription, links.find(link.id)!!.type)
            }
        }
    }

    private fun vless(name: String) =
        "vless://00000000-0000-4000-8000-000000000001@127.0.0.1:9443?security=tls&type=ws&path=%2Fsynthetic#$name"
}
