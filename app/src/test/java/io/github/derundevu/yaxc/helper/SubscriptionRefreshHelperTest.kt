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
import kotlinx.coroutines.flow.first
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
    private fun body(vararg names: String): String =
        bodyWithIdentities(*names.map { it to it }.toTypedArray())

    private fun bodyWithIdentities(vararg entries: Pair<String, String>): String = JSONArray().apply {
        entries.forEach { (name, identity) ->
            put(JSONObject().put("remarks", name)
                .put("outbounds", JSONArray().put(JSONObject().put("protocol", "freedom")))
                .put("future", JSONObject().put("identity", identity).put("counter", 9007199254740993L)))
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
    fun reorderInsertAndRemoveKeepLogicalIdsAndDeletedLiveMembersStayMissing() = runBlocking {
        SubscriptionHttpServer().use { server ->
            val unrelatedId = profiles.create(Profile(name = "standalone", config = body("standalone")))
            val link = seed(server, "A", "B", "C")
            assertEquals(listOf("A", "B", "C", "standalone"), profiles.all().first().map { it.name })
            val initial = profiles.linkProfiles(link.id).associateBy { it.name }
            val memberId = initial.getValue("B").id
            settings.selectedProfile = memberId
            settings.liveRoutingPlan = LiveRoutingHelper.encode(LiveRoutingPlan(
                groups = listOf(ProxyGroup(id = "group", profileIds = listOf(memberId))),
            ))
            val plan = settings.liveRoutingPlan

            server.enqueue(body("C", "A", "B"))
            assertEquals(1, helper().refreshLinks(link.id).refreshed)
            assertEquals(initial.mapValues { it.value.id },
                profiles.linkProfiles(link.id).associate { it.name to it.id })
            assertEquals(listOf("C", "A", "B", "standalone"), profiles.all().first().map { it.name })
            assertEquals(memberId, settings.selectedProfile)
            assertEquals(plan, settings.liveRoutingPlan)
            assertDenseIndices()

            server.enqueue(body("C", "D", "A", "B"))
            assertEquals(1, helper().refreshLinks(link.id).refreshed)
            assertEquals(listOf("C", "D", "A", "B", "standalone"), profiles.all().first().map { it.name })
            val grown = profiles.linkProfiles(link.id).associateBy { it.name }
            initial.forEach { (name, profile) -> assertEquals(profile.id, grown.getValue(name).id) }
            assertTrue(grown.getValue("D").id !in initial.values.map { it.id })
            assertDenseIndices()

            server.enqueue(body("C", "D", "E", "A"))
            assertEquals(1, helper().refreshLinks(link.id).refreshed)
            assertEquals(listOf("C", "D", "E", "A", "standalone"), profiles.all().first().map { it.name })
            val remaining = profiles.linkProfiles(link.id).associateBy { it.name }
            listOf("A", "C", "D").forEach { name ->
                assertEquals(grown.getValue(name).id, remaining.getValue(name).id)
            }
            assertTrue(remaining.getValue("E").id !in grown.values.map { it.id })
            assertFalse(profiles.orderedPositions().any { it.id == memberId })
            assertEquals(0L, settings.selectedProfile)
            assertEquals(plan, settings.liveRoutingPlan)
            assertEquals(listOf(memberId), LiveRoutingHelper.decode(plan).groups.single().profileIds)
            val missing = expectFailure {
                LiveRoutingHelper.compile(
                    base = """{"outbounds":[]}""",
                    plan = LiveRoutingHelper.decode(plan),
                    profiles = remaining.values.associateBy { it.id },
                    packageUids = emptyMap(),
                    socksUsername = "",
                    socksPassword = "",
                )
            }
            assertTrue(missing.message.orEmpty().contains("missing profile $memberId"))
            assertEquals(unrelatedId, profiles.orderedPositions().last().id)
            assertDenseIndices()

            server.enqueue(body("unrelated replacement"))
            helper().refreshLinks(link.id)
            assertTrue(profiles.linkProfiles(link.id).single().id !in remaining.values.map { it.id })
            assertEquals(plan, settings.liveRoutingPlan)
            assertDenseIndices()
        }
    }

    @Test
    fun unchangedConfigRenameAndUniqueNameConfigEditKeepIdsEvenWhenLabelsSwap() = runBlocking {
        SubscriptionHttpServer().use { server ->
            val link = seed(server, "A", "B", "C")
            val initial = profiles.linkProfiles(link.id).associateBy { it.name }
            profiles.updateContent(initial.getValue("A").id, link.id, "A", """
                {"future":{"counter":9007199254740993.0,"identity":"A"},
                 "outbounds":[{"protocol":"freedom"}]}
            """.trimIndent())
            settings.selectedProfile = initial.getValue("A").id
            server.enqueue(bodyWithIdentities("C" to "C", "Renamed" to "A", "B" to "edited"))
            helper().refreshLinks(link.id)
            val changed = profiles.linkProfiles(link.id).associateBy { it.name }
            assertEquals(initial.getValue("A").id, changed.getValue("Renamed").id)
            assertEquals(initial.getValue("B").id, changed.getValue("B").id)
            assertEquals(initial.getValue("C").id, changed.getValue("C").id)
            assertEquals(initial.getValue("A").id, settings.selectedProfile)

            server.enqueue(bodyWithIdentities("Renamed" to "edited", "B" to "A", "C" to "C"))
            helper().refreshLinks(link.id)
            val swapped = profiles.linkProfiles(link.id).associateBy { it.name }
            assertEquals(initial.getValue("A").id, swapped.getValue("B").id)
            assertEquals(initial.getValue("B").id, swapped.getValue("Renamed").id)
            assertDenseIndices()
        }
    }

    @Test
    fun refreshUsesTransactionLocalSlotsAndOnlyShiftsOtherSourcesOnGrowthOrShrink() = runBlocking {
        SubscriptionHttpServer().use { server ->
            val first = seed(server, "A", "B")
            val second = seed(server, "A", "B")
            val standaloneId = profiles.create(Profile(name = "standalone", config = "{}"))
            val held = server.enqueue(body("B", "A"), held = true)
            val pending = async(Dispatchers.IO) { helper().refreshLinks(first.id) }
            held.awaitRequest()
            val old = profiles.linkProfiles(first.id).associateBy { it.name }
            val a = old.getValue("A")
            val b = old.getValue("B")
            profiles.moveUp(1, a.index, a.id)
            profiles.moveUp(3, b.index, b.id)
            profiles.updateContent(b.id, first.id, "local rename", b.config)
            val secondBefore = profiles.linkProfiles(second.id)
            val standaloneBefore = profiles.find(standaloneId)
            val (secondA, secondB) = secondBefore.asReversed().map { it.id }
            assertEquals(listOf(standaloneId, a.id, secondA, b.id, secondB), profiles.all().first().map { it.id })
            held.release()
            val refreshed = pending.await()
            assertEquals(1, refreshed.refreshed)
            assertEquals(listOf("local rename → B"), refreshed.changes.single().modified)
            assertEquals(listOf(standaloneId, b.id, secondA, a.id, secondB), profiles.all().first().map { it.id })
            assertEquals(secondBefore, profiles.linkProfiles(second.id))
            assertEquals(standaloneBefore, profiles.find(standaloneId))
            assertDenseIndices()

            server.enqueue(body("A", "C", "B"))
            assertEquals(1, helper().refreshLinks(first.id).refreshed)
            val c = profiles.linkProfiles(first.id).single { it.name == "C" }
            assertEquals(listOf(standaloneId, a.id, secondA, c.id, b.id, secondB), profiles.all().first().map { it.id })
            assertDenseIndices()

            server.enqueue(body("B"))
            assertEquals(1, helper().refreshLinks(first.id).refreshed)
            assertEquals(listOf(standaloneId, b.id, secondA, secondB), profiles.all().first().map { it.id })
            assertDenseIndices()
        }
    }

    @Test
    fun tiedIndicesUseStableIdsAndOnlyCompactWhenOrderChanges() = runBlocking {
        SubscriptionHttpServer().use { server ->
            val unrelated = profiles.create(Profile(name = "standalone", config = "{}"))
            val link = seed(server, "A", "B")
            val a = profiles.linkProfiles(link.id).single { it.name == "A" }.id
            val b = profiles.linkProfiles(link.id).single { it.name == "B" }.id
            listOf(unrelated, a, b).forEach { profiles.updateIndex(7, it) }
            val tied = profiles.orderedPositions()
            assertEquals(listOf(unrelated, a, b), profiles.all().first().map { it.id })

            server.enqueue(bodyWithIdentities("A" to "A", "B" to "edited"))
            assertEquals(1, helper().refreshLinks(link.id).refreshed)
            assertEquals(tied, profiles.orderedPositions())

            server.enqueue(bodyWithIdentities("B" to "edited", "A" to "A"))
            assertEquals(1, helper().refreshLinks(link.id).refreshed)
            assertEquals(listOf(unrelated, b, a), profiles.all().first().map { it.id })
            assertDenseIndices()
        }
    }

    @Test
    fun duplicateExactMatchesAreDeterministicButDuplicateNameConfigChangesNeedNewIds() = runBlocking {
        SubscriptionHttpServer().use { server ->
            val link = source(server.url)
            server.enqueue(bodyWithIdentities("duplicate" to "a", "duplicate" to "a", "duplicate" to "b"))
            helper().saveResolvedLink(link, helper().resolveProfiles(link))
            val before = profiles.all().first().filter { it.link == link.id }
            assertEquals(listOf("duplicate", "duplicate", "duplicate"), before.map { it.name })
            val memberId = before.first().id
            settings.selectedProfile = memberId
            val plan = LiveRoutingHelper.encode(LiveRoutingPlan(
                groups = listOf(ProxyGroup(id = "group", profileIds = listOf(memberId))),
            ))
            settings.liveRoutingPlan = plan
            server.enqueue(bodyWithIdentities("duplicate" to "changed", "duplicate" to "b", "duplicate" to "a"))
            assertEquals(1, helper().refreshLinks(link.id).refreshed)
            val after = profiles.all().first().filter { it.link == link.id }
            assertEquals(listOf("duplicate", "duplicate", "duplicate"), after.map { it.name })
            assertEquals(listOf("changed", "b", "a"), after.map {
                JSONObject(it.config).getJSONObject("future").getString("identity")
            })
            assertEquals(listOf(before[2].id, memberId), after.drop(1).map { it.id })
            assertTrue(after.first().id !in before.map { it.id })
            assertFalse(after.any { it.id == before[1].id })
            assertEquals(memberId, settings.selectedProfile)
            assertEquals(plan, settings.liveRoutingPlan)

            server.enqueue(bodyWithIdentities("duplicate" to "a", "duplicate" to "changed", "duplicate" to "b"))
            assertEquals(1, helper().refreshLinks(link.id).refreshed)
            assertEquals(listOf(memberId, after.first().id, before[2].id), profiles.all().first().map { it.id })
            assertEquals(memberId, settings.selectedProfile)
            assertEquals(plan, settings.liveRoutingPlan)
            assertDenseIndices()
        }
    }

    @Test
    fun duplicateNamesWithAllChangedConfigsAndAmbiguousRenamesNeverReuseIds() = runBlocking {
        val cases = listOf(
            listOf("duplicate" to "a", "duplicate" to "b") to
                listOf("duplicate" to "c", "duplicate" to "d"),
            listOf("A" to "shared", "B" to "shared") to
                listOf("C" to "shared", "D" to "shared"),
            listOf("duplicate" to "a", "duplicate" to "b") to
                listOf("duplicate" to "c"),
            listOf("duplicate" to "a") to
                listOf("duplicate" to "b", "duplicate" to "c"),
        )
        SubscriptionHttpServer().use { server ->
            for ((before, after) in cases) {
                val link = source(server.url)
                server.enqueue(bodyWithIdentities(*before.toTypedArray()))
                helper().saveResolvedLink(link, helper().resolveProfiles(link))
                val oldIds = profiles.linkProfiles(link.id).map { it.id }.toSet()
                server.enqueue(bodyWithIdentities(*after.toTypedArray()))
                helper().refreshLinks(link.id)
                assertTrue(profiles.linkProfiles(link.id).none { it.id in oldIds })
                assertDenseIndices()
            }
        }
    }

    @Test
    fun manualChangesMatchContentRatherThanPositionAndKeepExactLargeNumbers() = runBlocking {
        SubscriptionHttpServer().use { server ->
            val link = seed(server, "removed", "same", "keep")
            val response = JSONArray(body("keep", "added", "same"))
            response.getJSONObject(2).getJSONObject("future").put("counter", 9007199254740992L)
            server.enqueue(response.toString(), headers = mapOf("Profile-Title" to "Current title"))

            assertEquals(SubscriptionRefreshHelper.RefreshResult(1, 1, 0, listOf(
                SubscriptionRefreshHelper.SourceChanges(link.id, "Current title",
                    added = listOf("added"), removed = listOf("removed"), modified = listOf("same")),
            )), helper().refreshLinks(link.id))
            assertEquals(9007199254740992L,
                JSONObject(profiles.linkProfiles(link.id).single { it.name == "same" }.config)
                    .getJSONObject("future").getLong("counter"))
        }
    }

    @Test
    fun manualChangesIgnoreProfileOrderWhitespaceAndObjectKeyOrderButNotArrayOrder() = runBlocking {
        SubscriptionHttpServer().use { server ->
            val link = seed(server, "Alpha", "Beta")
            val initialIds = profiles.linkProfiles(link.id).associate { it.name to it.id }
            profiles.linkProfiles(link.id).forEach { profile ->
                profiles.update(profile.copy(config = """
                    {
                      "outbounds": [ { "protocol": "freedom" } ],
                      "future": {
                        "optional": null, "enabled": true, "limit": 1.0,
                        "features": ["one", "two"], "counter": 9007199254740993,
                        "identity": "${profile.name}"
                      }
                    }
                """.trimIndent()))
            }
            val response = """
                [
                  {
                    "future": {
                      "identity": "Beta", "counter": 9007199254740993, "features": ["one", "two"],
                      "limit": 1, "enabled": true, "optional": null
                    },
                    "remarks": "Beta", "outbounds": [{"protocol": "freedom"}]
                  },
                  {
                    "future": {
                      "identity": "Alpha", "counter": 9007199254740993, "features": ["one", "two"],
                      "limit": 1, "enabled": true, "optional": null
                    },
                    "outbounds": [{"protocol": "freedom"}], "remarks": "Alpha"
                  }
                ]
            """.trimIndent()
            server.enqueue(response)
            assertEquals(SubscriptionRefreshHelper.RefreshResult(1, 1, 0, listOf(
                SubscriptionRefreshHelper.SourceChanges(link.id, link.name,
                    added = emptyList(), removed = emptyList(), modified = emptyList()),
            )), helper().refreshLinks(link.id))
            assertEquals(initialIds, profiles.linkProfiles(link.id).associate { it.name to it.id })

            val changedArray = JSONArray(response)
            changedArray.getJSONObject(1).getJSONObject("future")
                .put("features", JSONArray().put("two").put("one"))
            server.enqueue(changedArray.toString())
            assertEquals(listOf(SubscriptionRefreshHelper.SourceChanges(link.id, link.name,
                added = emptyList(), removed = emptyList(), modified = listOf("Alpha"))),
                helper().refreshLinks(link.id).changes)
            assertEquals(initialIds, profiles.linkProfiles(link.id).associate { it.name to it.id })
        }
    }

    @Test
    fun manualChangesPreserveDuplicateOccurrencesAndMatchExactPairsBeforeChangesAndRenames() = runBlocking {
        SubscriptionHttpServer().use { server ->
            val link = source(server.url)
            server.enqueue(bodyWithIdentities(
                "duplicate" to "a", "duplicate" to "a", "duplicate" to "b",
                "Old" to "rename", "Old" to "rename",
            ))
            helper().saveResolvedLink(link, helper().resolveProfiles(link))
            server.enqueue(bodyWithIdentities(
                "duplicate" to "b", "duplicate" to "changed", "New" to "rename", "New" to "rename",
                "added" to "first-addition", "added" to "second-addition",
            ))

            assertEquals(SubscriptionRefreshHelper.RefreshResult(1, 1, 0, listOf(
                SubscriptionRefreshHelper.SourceChanges(link.id, link.name,
                    added = listOf("added", "added"), removed = listOf("duplicate"),
                    modified = listOf("Old → New", "Old → New", "duplicate")),
            )), helper().refreshLinks(link.id))
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
                refreshIntervalMinutes = 45
            }
            links.update(link)
            val exchange = server.enqueue(body("Тест 🌍"), headers = mapOf(
                "Profile-Title" to "base64:" + Base64.getEncoder().encodeToString("План 🌍".toByteArray()),
                "Profile-Update-Interval" to "2",
                "Subscription-Userinfo" to "upload=2147483648; download=4294967296; total=1099511627776; expire=1893456000",
                "Support-Url" to "http://127.0.0.1/support",
                "Profile-Web-Page-Url" to "http://127.0.0.1/account",
                "Announce" to "base64:" + Base64.getEncoder().encodeToString("План обновлён 🌍".toByteArray()),
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
            assertEquals(45, saved.refreshIntervalMinutes)
            val metadata = SubscriptionMetadata.fromJsonString(saved.subscriptionMetadata)!!
            assertEquals(120, metadata.updateIntervalMinutes)
            assertEquals(6_442_450_944L, metadata.usedBytes)
            assertEquals(1_099_511_627_776L, metadata.totalBytes)
            assertEquals(1_893_456_000L, metadata.expireAtEpochSeconds)
            assertEquals("http://127.0.0.1/support", metadata.supportUrl)
            assertEquals("http://127.0.0.1/account", metadata.profileWebPageUrl)
            assertEquals("План обновлён 🌍", metadata.announce)
            val config = JSONObject(profiles.linkProfiles(link.id).single().config)
            assertEquals(9007199254740993L, config.getJSONObject("future").getLong("counter"))
            assertFalse(config.has("remarks"))
        }
    }

    @Test
    fun announceOnlyMetadataIsPersistedAndSuccessfulRefreshClearsMissingOrBlankAnnouncements() = runBlocking {
        SubscriptionHttpServer().use { server ->
            val replacements = listOf(
                emptyMap(),
                mapOf("Announce" to " "),
                mapOf("Announce" to "base64:IA==", "Support-Url" to "http://127.0.0.1/support"),
            )
            replacements.forEach { headers ->
                val link = source(server.url)
                server.enqueue(body("profile"), headers = mapOf(
                    "Announce" to "base64:" + Base64.getEncoder().encodeToString("Тест 🌍".toByteArray()),
                ))
                assertEquals(1, helper().refreshLinks(link.id).refreshed)
                assertEquals("Тест 🌍",
                    SubscriptionMetadata.fromJsonString(links.find(link.id)!!.subscriptionMetadata)?.announce)

                server.enqueue(body("profile"), headers = headers)
                assertEquals(1, helper().refreshLinks(link.id).refreshed)
                val saved = links.find(link.id)!!
                val metadata = SubscriptionMetadata.fromJsonString(saved.subscriptionMetadata)
                assertNull(metadata?.announce)
                if (headers.containsKey("Support-Url")) {
                    assertEquals("http://127.0.0.1/support", metadata?.supportUrl)
                } else {
                    assertNull(saved.subscriptionMetadata)
                }
            }
        }
    }

    @Test
    fun creatingAResolvedSubscriptionPersistsItsLocalIntervalWithoutUsingServerAdvice() = runBlocking {
        SubscriptionHttpServer().use { server ->
            val link = Link(address = server.url, name = "new source", refreshIntervalMinutes = 30)
            server.enqueue(body("created"), headers = mapOf("Profile-Update-Interval" to "4"))
            helper().saveResolvedLink(link, helper().resolveProfiles(link), 123_456L)
            assertTrue(link.id > 0L)
            assertEquals(link, links.find(link.id))
            assertEquals(30, links.find(link.id)!!.refreshIntervalMinutes)
            assertEquals(240,
                SubscriptionMetadata.fromJsonString(links.find(link.id)!!.subscriptionMetadata)!!.updateIntervalMinutes)
            assertEquals("created", profiles.linkProfiles(link.id).single().name)
        }
    }

    @Test
    fun dueBoundariesUseLocalThenGlobalAndIgnoreServerMetadata() = runBlocking {
        SubscriptionHttpServer().use { server ->
            settings.refreshLinksIntervalMinutes = 60
            val now = 200_000_000_000_000L
            val globalInterval = 60 * 60_000L
            val minimumInterval = Settings.MIN_REFRESH_LINKS_INTERVAL_MINUTES * 60_000L
            val maximumInterval = Int.MAX_VALUE * 60_000L
            val cases = listOf(
                Link(name = "before", lastRefreshedAt = now - globalInterval + 1),
                Link(name = "exact", lastRefreshedAt = now - globalInterval),
                Link(name = "after", lastRefreshedAt = now - globalInterval - 1),
                Link(name = "never", lastRefreshedAt = 0),
                Link(name = "future", lastRefreshedAt = now + globalInterval),
                Link(name = "server-slow", lastRefreshedAt = now - globalInterval,
                    subscriptionMetadata = SubscriptionMetadata(updateIntervalMinutes = 120).toJsonString()),
                Link(name = "server-fast", lastRefreshedAt = now - globalInterval + 1,
                    subscriptionMetadata = SubscriptionMetadata(updateIntervalMinutes = 15).toJsonString()),
                Link(name = "minimum-before", lastRefreshedAt = now - minimumInterval + 1,
                    refreshIntervalMinutes = Settings.MIN_REFRESH_LINKS_INTERVAL_MINUTES),
                Link(name = "minimum-exact", lastRefreshedAt = now - minimumInterval,
                    refreshIntervalMinutes = Settings.MIN_REFRESH_LINKS_INTERVAL_MINUTES,
                    subscriptionMetadata = SubscriptionMetadata(updateIntervalMinutes = 120).toJsonString()),
                Link(name = "local-before", lastRefreshedAt = now - 2 * globalInterval + 1,
                    refreshIntervalMinutes = 120),
                Link(name = "local-exact", lastRefreshedAt = now - 2 * globalInterval,
                    refreshIntervalMinutes = 120,
                    subscriptionMetadata = SubscriptionMetadata(updateIntervalMinutes = 15).toJsonString()),
                Link(name = "maximum-before", lastRefreshedAt = now - maximumInterval + 1,
                    refreshIntervalMinutes = Int.MAX_VALUE),
                Link(name = "maximum-exact", lastRefreshedAt = now - maximumInterval,
                    refreshIntervalMinutes = Int.MAX_VALUE),
                Link(name = "inactive", isActive = false),
                Link(name = "blank", address = "  \r\n"),
            )
            cases.forEach { link ->
                if (link.name != "blank") link.address = server.url
                link.id = links.insertAtEnd(link)
            }
            val dueNames = setOf("exact", "after", "never", "server-slow", "minimum-exact",
                "local-exact", "maximum-exact")
            repeat(dueNames.size) { server.enqueue(body("due-$it")) }
            assertEquals(SubscriptionRefreshHelper.RefreshResult(dueNames.size, dueNames.size, 0),
                helper().refreshDueLinks(now))
            assertEquals(dueNames,
                cases.filter { links.find(it.id)!!.lastRefreshedAt == now }.map { it.name }.toSet())
            assertEquals(dueNames.size, server.requestCount.get())
            val before = cases.first()
            server.enqueue(body("manual"))
            assertEquals(SubscriptionRefreshHelper.RefreshResult(1, 1, 0, listOf(
                SubscriptionRefreshHelper.SourceChanges(before.id, before.name,
                    added = listOf("manual"), removed = emptyList(), modified = emptyList()),
            )), helper().refreshLinks(before.id))
            assertEquals("manual", profiles.linkProfiles(before.id).single().name)
            for (name in listOf("inactive", "blank")) {
                assertEquals(SubscriptionRefreshHelper.RefreshResult(0, 0, 0),
                    helper().refreshLinks(cases.single { it.name == name }.id))
            }
        }
    }

    @Test
    fun independentSubscriptionsKeepTheirSchedulesAndClearingOverrideTracksGlobalChanges() = runBlocking {
        SubscriptionHttpServer().use { localServer ->
            SubscriptionHttpServer().use { globalServer ->
                val start = 10_000_000L
                settings.refreshLinksIntervalMinutes = 60
                val local = source(localServer.url, "local").copy(
                    refreshIntervalMinutes = 15,
                    lastRefreshedAt = start,
                )
                val global = source(globalServer.url, "global").copy(
                    lastRefreshedAt = start,
                    subscriptionMetadata = SubscriptionMetadata(updateIntervalMinutes = 120).toJsonString(),
                )
                links.update(local)
                links.update(global)
                localServer.enqueue(body("local-15"), headers = mapOf("Profile-Update-Interval" to "4"))
                assertEquals(SubscriptionRefreshHelper.RefreshResult(1, 1, 0),
                    helper().refreshDueLinks(start + 15 * 60_000L))
                assertEquals(15, links.find(local.id)!!.refreshIntervalMinutes)
                assertEquals(global, links.find(global.id))
                assertEquals(0, globalServer.requestCount.get())

                settings.refreshLinksIntervalMinutes = 30
                localServer.enqueue(body("local-30"))
                globalServer.enqueue(body("global-30"))
                assertEquals(SubscriptionRefreshHelper.RefreshResult(2, 2, 0),
                    helper().refreshDueLinks(start + 30 * 60_000L))
                links.update(links.find(local.id)!!.copy(refreshIntervalMinutes = null))
                settings.refreshLinksIntervalMinutes = 60
                assertEquals(SubscriptionRefreshHelper.RefreshResult(0, 0, 0),
                    helper().refreshDueLinks(start + 45 * 60_000L))

                settings.refreshLinksIntervalMinutes = 15
                localServer.enqueue(body("cleared-follows-global"))
                globalServer.enqueue(body("global-15"))
                assertEquals(SubscriptionRefreshHelper.RefreshResult(2, 2, 0),
                    helper().refreshDueLinks(start + 45 * 60_000L))
                assertNull(links.find(local.id)!!.refreshIntervalMinutes)
                assertNull(links.find(global.id)!!.refreshIntervalMinutes)
                assertEquals(listOf(local.id, global.id), links.orderedIds())
                assertEquals(3, localServer.requestCount.get())
                assertEquals(2, globalServer.requestCount.get())
            }
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
            server.enqueue(body("second-original"))
            helper().saveResolvedLink(second, helper().resolveProfiles(second), 1_000L)
            val secondBefore = profiles.linkProfiles(second.id)
            val secondLinkBefore = links.find(second.id)
            server.enqueue(body("first-profile"))
            server.enqueue("unavailable", 500)
            server.enqueue(body("third-profile"))
            assertEquals(SubscriptionRefreshHelper.RefreshResult(3, 2, 1, listOf(
                SubscriptionRefreshHelper.SourceChanges(first.id, first.name,
                    added = listOf("first-profile"), removed = emptyList(), modified = emptyList()),
                SubscriptionRefreshHelper.SourceChanges(third.id, third.name,
                    added = listOf("third-profile"), removed = emptyList(), modified = emptyList()),
            )), helper().refreshLinks())
            assertEquals("first-profile", profiles.linkProfiles(first.id).single().name)
            assertEquals(secondBefore.map { Triple(it.id, it.name, it.config) },
                profiles.linkProfiles(second.id).map { Triple(it.id, it.name, it.config) })
            assertEquals(secondLinkBefore, links.find(second.id))
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
            server.enqueue(bodyWithIdentities("changed-before-failure" to "second", "reject" to "first"),
                headers = mapOf("Profile-Title" to "must rollback"))
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
                WHEN OLD.name = 'third' BEGIN SELECT RAISE(ABORT, 'synthetic rejection'); END
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
    fun failedIndexShiftRollsBackEarlierReorderAndInsert() = runBlocking {
        SubscriptionHttpServer().use { server ->
            val unrelated = profiles.create(Profile(name = "standalone", config = "{}"))
            val link = seed(server, "A", "B")
            val before = profiles.all().first()
            val beforeLink = links.find(link.id)
            settings.selectedProfile = before.first().id
            database.openHelper.writableDatabase.execSQL("""
                CREATE TRIGGER reject_unrelated_index_shift BEFORE UPDATE OF `index` ON profiles
                WHEN OLD.id = $unrelated BEGIN SELECT RAISE(ABORT, 'synthetic rejection'); END
            """.trimIndent())
            server.enqueue(body("B", "added", "A"))

            assertEquals(SubscriptionRefreshHelper.RefreshResult(1, 0, 1), helper().refreshLinks(link.id))
            assertEquals(before, profiles.all().first())
            assertEquals(beforeLink, links.find(link.id))
            assertEquals(before.first().id, settings.selectedProfile)
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
            { it.copy(refreshIntervalMinutes = 90) },
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
    fun cancellationAfterPartialSourceCommitKeepsItsSkippedReportDurable() = runBlocking {
        SubscriptionHttpServer().use { server ->
            val first = source(server.url, "partial")
            val second = source(server.url, "cancelled")
            server.enqueue("${vless("supported")}\nanytls://test-pass@proxy.example.org#Unavailable")
            val held = server.enqueue(body("must not apply"), held = true)
            val pending = async(Dispatchers.IO) { helper().refreshLinks() }
            held.awaitRequest()
            val warning = SubscriptionRefreshHelper.SourceWarning(first.id, first.name,
                listOf(SubscriptionRefreshHelper.SkippedProfile("Unavailable", "anytls")))
            assertEquals(listOf(warning), settings.pendingSubscriptionWarnings())
            pending.cancel(CancellationException("synthetic cancellation"))
            held.release()
            assertTrue(expectFailure { pending.await() } is CancellationException)
            pending.join()
            assertEquals("supported", profiles.linkProfiles(first.id).single().name)
            assertTrue(profiles.linkProfiles(second.id).isEmpty())
            assertEquals(listOf(warning), settings.pendingSubscriptionWarnings())
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
                val saved = profiles.all().first().filter { it.link == link.id }
                assertEquals(2, saved.size)
                if (index < 2) assertEquals(listOf("Первый 🌍", "Second"), saved.map { it.name })
                else {
                    assertEquals(listOf("First", "Тест"), saved.map { it.name })
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

    @Test
    fun mixedNaiveSubscriptionsReportEveryUnsupportedRecordWithoutNativeFallback() = runBlocking {
        SubscriptionHttpServer().use { server ->
            val link = source(server.url)
            val mixed = """
                http2://test-user:test-pass@proxy.example.org:443
                naive+https://test-user:test%3Apass%2Bword@proxy.example.org?padding=1&tfo=0#HTTPS
                naive+quic://test-user:test-pass@proxy.example.org?padding=1&tfo=0#HTTP3
                ${vless("VLESS")}
                anytls://test-pass@proxy.example.org#Unsupported%20TLS
                tuic://test-user:test-pass@proxy.example.org#Unsupported%20QUIC
                naive+https://test-user:test-pass@proxy.example.org?padding=0#Custom%20padding
            """.trimIndent()
            server.enqueue(Base64.getEncoder().encodeToString(mixed.toByteArray()))
            val parsed = helper().resolveProfiles(link)
            assertEquals(listOf("HTTPS", "HTTP3", "VLESS"), parsed.profiles.map { it.name })
            assertEquals(listOf(
                SubscriptionRefreshHelper.SkippedProfile("http2 1", "http2"),
                SubscriptionRefreshHelper.SkippedProfile("Unsupported TLS", "anytls"),
                SubscriptionRefreshHelper.SkippedProfile("Unsupported QUIC", "tuic"),
                SubscriptionRefreshHelper.SkippedProfile("Custom padding", "naive+https"),
            ), parsed.skippedProfiles)
            helper().saveResolvedLink(link, parsed)
            val before = profiles.linkProfiles(link.id)
            server.enqueue(mixed)
            val refreshed = helper().refreshLinks(link.id)
            assertEquals(1, refreshed.refreshed)
            assertEquals(parsed.skippedProfiles, refreshed.warnings.single().skippedProfiles)
            assertTrue(refreshed.changes.all { it.added.isEmpty() && it.removed.isEmpty() && it.modified.isEmpty() })
            assertEquals(before, profiles.linkProfiles(link.id))
            server.enqueue(mixed)
            assertEquals(parsed.skippedProfiles,
                helper().refreshDueLinks(System.currentTimeMillis() + 86_400_000L).warnings.single().skippedProfiles)
        }
    }

    @Test
    fun singBoxJsonConvertsNaiveBbrAndVlessIntoIndependentUsableProfiles() = runBlocking {
        val value = """
            {"inbounds":[],"route":{"final":"select"},"outbounds":[
              {"type":"selector","tag":"select","outbounds":["HTTP3","VLESS"]},
              {"type":"urltest","tag":"test","outbounds":["HTTP3","VLESS"]},
              {"type":"direct","tag":"direct"},
              {"type":"naive","tag":"HTTP3","server":"proxy.example.org","server_port":8443,
               "username":"test-user","password":"test:pass+word","quic":true,
               "quic_congestion_control":"bbr","tls":{"enabled":true,"server_name":"tls.example.org"}},
              {"type":"vless","tag":"VLESS","server":"proxy.example.org","server_port":443,
               "uuid":"00000000-0000-4000-8000-000000000001","flow":"xtls-rprx-vision",
               "tls":{"enabled":true,"server_name":"tls.example.org",
                 "utls":{"enabled":true,"fingerprint":"chrome"},
                 "reality":{"enabled":true,"public_key":"synthetic-key","short_id":"0123abcd"}}},
              {"type":"anytls","tag":"Unsupported TLS"},
              {"type":"tuic","tag":"Unsupported QUIC"},
              {"type":"vless","tag":"WebSocket","server":"proxy.example.org","server_port":443,
               "uuid":"00000000-0000-4000-8000-000000000002","tls":{"enabled":true},
               "transport":{"type":"ws","path":"/socket?token=a+b","headers":{"Host":"front.example.org"}}}
            ]}
        """.trimIndent()
        val parsed = helper().parseProfiles(Link(), value)
        assertEquals(listOf("HTTP3", "VLESS", "WebSocket"), parsed.profiles.map { it.name })
        assertEquals(listOf("selector", "urltest", "direct", "anytls", "tuic"), parsed.skippedProfiles.map { it.protocol })
        val naive = JSONObject(parsed.profiles.first().config).getJSONArray("outbounds").getJSONObject(0)
        assertEquals("naive", naive.getString("protocol"))
        assertEquals("bbr", naive.getJSONObject("settings").getString("quicCongestionControl"))
        assertEquals("test:pass+word", naive.getJSONObject("settings").getString("pass"))
        assertTrue(naive.getJSONObject("settings").getBoolean("udpOverTcp"))
        assertEquals("tls.example.org", naive.getJSONObject("streamSettings").getJSONObject("tlsSettings").getString("serverName"))
        val vless = JSONObject(parsed.profiles[1].config).getJSONArray("outbounds").getJSONObject(0)
        assertEquals("reality", vless.getJSONObject("streamSettings").getString("security"))
        assertEquals("synthetic-key", vless.getJSONObject("streamSettings").getJSONObject("realitySettings").getString("publicKey"))
        val websocket = JSONObject(parsed.profiles.last().config).getJSONArray("outbounds").getJSONObject(0)
            .getJSONObject("streamSettings").getJSONObject("wsSettings")
        assertEquals("/socket?token=a+b", websocket.getString("path"))
        assertEquals("front.example.org", websocket.getJSONObject("headers").getString("Host"))
        assertFalse(JSONObject(parsed.profiles.first().config).has("route"))
    }

    @Test
    fun subscriptionRefreshImportsSingBoxNaiveExtraHeadersWithSingBoxValueSemantics() = runBlocking {
        SubscriptionHttpServer().use { server ->
            val link = source(server.url)
            server.enqueue("""
                {"outbounds":[{"type":"naive","tag":"Naive","server":"proxy.example.org","server_port":443,
                  "username":"test-user","password":"test-pass","tls":{"enabled":true},"extra_headers":{
                    "X-API-Key":"synthetic-value","X-Test-Order":["first","second"],
                    "X-Test-Empty-String":"","X-Test-Empty-List":[],"X-Test-Null":null
                  }}]}
            """.trimIndent())
            val result = helper().refreshLinks(link.id)
            assertEquals(1, result.refreshed)
            assertTrue(result.warnings.isEmpty())
            val headers = JSONObject(profiles.linkProfiles(link.id).single().config)
                .getJSONArray("outbounds").getJSONObject(0).getJSONObject("settings").getJSONObject("headers")
            assertEquals(mapOf("X-API-Key" to "synthetic-value", "X-Test-Order" to "first", "X-Test-Empty-String" to ""),
                headers.keys().asSequence().associateWith(headers::getString))
        }
    }

    @Test
    fun unsupportedSingBoxOptionsAreReportedButMalformedSupportedEntriesAbortImport() = runBlocking {
        val base = JSONObject("""{"type":"naive","tag":"Naive","server":"proxy.example.org","server_port":443,
            "username":"test-user","password":"test-pass","tls":{"enabled":true}}""")
        val overrides = listOf(
            JSONObject(base.toString()).put("tls", JSONObject("""{"enabled":true,"insecure":true}""")),
            JSONObject(base.toString()).put("tls", JSONObject("""{"enabled":true,"alpn":["h2"]}""")),
            JSONObject(base.toString()).put("tls", JSONObject("""{"enabled":true,"utls":{"enabled":true}}""")),
            JSONObject(base.toString()).put("quic", true).put("quic_congestion_control", "unknown"),
            JSONObject(base.toString()).put("transport", JSONObject("""{"type":"ws"}""")),
            JSONObject(base.toString()).put("extra_headers", JSONObject("""{"Host":"other.example.org"}""")),
            JSONObject(base.toString()).put("extra_headers", JSONObject("""{"X-Test":"one","x-test":"two"}""")),
            JSONObject(base.toString()).put("headers", JSONObject("""{"X-Test":"one"}"""))
                .put("extra_headers", JSONObject("""{"X-Test":"two"}""")),
        )
        for (unsupported in overrides) {
            val parsed = helper().parseProfiles(Link(), JSONObject().put("outbounds",
                JSONArray().put(base).put(unsupported)).toString())
            assertEquals(1, parsed.profiles.size)
            assertEquals(listOf(SubscriptionRefreshHelper.SkippedProfile("Naive", "naive")), parsed.skippedProfiles)
        }
        val malformed = JSONObject(base.toString()).put("server_port", "invalid")
        val error = expectFailure { helper().parseProfiles(Link(), JSONObject().put("outbounds",
            JSONArray().put(base).put(malformed)).toString()) }
        assertTrue(error is IllegalArgumentException)
        assertFalse(error is UnsupportedProfileException)

        val malformedHeaders = JSONObject(base.toString())
            .put("extra_headers", JSONObject().put("X-Test", JSONArray().put("first").put(2)))
        assertTrue(expectFailure { helper().parseProfiles(Link(), JSONObject().put("outbounds",
            JSONArray().put(base).put(malformedHeaders)).toString()) } is IllegalArgumentException)
    }

    @Test
    fun allUnsupportedRefreshPreservesSourceSelectionProfilesAndReportsSkippedEntries() = runBlocking {
        SubscriptionHttpServer().use { server ->
            val link = seed(server, "existing")
            val beforeLink = links.find(link.id)
            val before = profiles.linkProfiles(link.id)
            settings.selectedProfile = before.single().id
            val unsupported = "anytls://test-pass@proxy.example.org#Unavailable"
            server.enqueue(unsupported)
            val result = helper().refreshLinks(link.id)
            assertEquals(0, result.refreshed)
            assertEquals(1, result.failed)
            assertEquals(listOf(SubscriptionRefreshHelper.SkippedProfile("Unavailable", "anytls")),
                result.warnings.single().skippedProfiles)
            assertEquals(beforeLink, links.find(link.id))
            assertEquals(before, profiles.linkProfiles(link.id))
            assertEquals(before.single().id, settings.selectedProfile)

            val unsaved = Link(name = "unsaved")
            val parsed = helper().parseProfiles(unsaved, unsupported)
            assertTrue(parsed.profiles.isEmpty())
            expectFailure { helper().saveResolvedLink(unsaved, parsed) }
            assertEquals(0L, unsaved.id)
            assertEquals(listOf(link.id), links.orderedIds())
            server.enqueue("${vless("valid")}\nnaive+https://test-user@proxy.example.org")
            assertEquals(1, helper().refreshLinks(link.id).failed)
            assertEquals(before, profiles.linkProfiles(link.id))
        }
    }

    @Test
    fun lineSourceSurvivesRawEmojiFragmentsAndUnusableLines() = runBlocking {
        val body = listOf(
            "naive+https://test-user:test-pass@proxy.example.org#Naive-h2 ♾",
            "naive+quic://test-user:test-pass@proxy.example.org:8443#Naive-h3 \uD83D\uDE80",
            "anytls://00000000-0000-4000-8000-000000000003@proxy.example.org:9443#AnyTLS ♾",
        ).joinToString("\n")

        val parsed = helper().parseProfiles(Link(), body)

        assertEquals(listOf("Naive-h2 ♾", "Naive-h3 \uD83D\uDE80"), parsed.profiles.map { it.name })
        assertEquals(setOf("anytls"), parsed.skippedProfiles.map { it.protocol }.toSet())
        assertTrue(expectFailure { helper().parseProfiles(Link(), "vless://not-a-valid-uri") } is IllegalArgumentException)
    }

    @Test
    fun singBoxUdpOverTcpDefaultsOnAndHonorsExplicitOptOut() = runBlocking {
        val outbound = JSONObject("""{"type":"naive","server":"proxy.example.org","server_port":443,
            "username":"test-user","password":"test-pass","quic":true,"tls":{"enabled":true},
            "udp_over_tcp":{"enabled":true,"version":2}}""")
        val parsed = helper().parseProfiles(Link(), JSONObject().put("outbounds", JSONArray().put(outbound)).toString())
        val settings = JSONObject(parsed.profiles.single().config).getJSONArray("outbounds")
            .getJSONObject(0).getJSONObject("settings")
        assertTrue(settings.getBoolean("udpOverTcp"))
        outbound.remove("udp_over_tcp")
        val defaulted = helper().parseProfiles(Link(), JSONObject().put("outbounds", JSONArray().put(outbound)).toString())
        assertTrue(JSONObject(defaulted.profiles.single().config).getJSONArray("outbounds")
            .getJSONObject(0).getJSONObject("settings").getBoolean("udpOverTcp"))
        outbound.put("udp_over_tcp", JSONObject().put("enabled", false))
        val disabled = helper().parseProfiles(Link(), JSONObject().put("outbounds", JSONArray().put(outbound)).toString())
        assertFalse(JSONObject(disabled.profiles.single().config).getJSONArray("outbounds")
            .getJSONObject(0).getJSONObject("settings").getBoolean("udpOverTcp"))
        outbound.put("udp_over_tcp", JSONObject("""{"enabled":true,"version":1}"""))
        val unsupported = helper().parseProfiles(Link(), JSONObject().put("outbounds", JSONArray().put(outbound)).toString())
        assertTrue(unsupported.profiles.isEmpty())
        assertEquals(listOf(SubscriptionRefreshHelper.SkippedProfile("naive 1", "naive")), unsupported.skippedProfiles)
    }

    private fun vless(name: String) =
        "vless://00000000-0000-4000-8000-000000000001@127.0.0.1:9443?security=tls&type=ws&path=%2Fsynthetic#$name"
}
