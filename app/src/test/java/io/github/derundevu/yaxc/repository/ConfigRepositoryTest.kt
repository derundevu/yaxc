package io.github.derundevu.yaxc.repository

import io.github.derundevu.yaxc.database.Config as DatabaseConfig
import io.github.derundevu.yaxc.helper.CoreRoutingHelper
import io.github.derundevu.yaxc.helper.CoreRoutingRule
import io.github.derundevu.yaxc.helper.CoreRoutingTransport
import io.github.derundevu.yaxc.helper.assertJsonEquals
import io.github.derundevu.yaxc.helper.objectValues
import io.github.derundevu.yaxc.testing.RegressionApplication
import io.github.derundevu.yaxc.testing.RoomTestSupport
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = RegressionApplication::class, sdk = [34])
class ConfigRepositoryTest : RoomTestSupport() {
    @Test
    fun emptyDatabaseSeedsOneUsableConfigAndStableUiIdentities() = runTest {
        val first = app.configRepository.get()
        val firstUi = CoreRoutingHelper.parseUiRules(settings.coreRoutingUiRules)
        val second = app.configRepository.get()
        assertEquals(first, second)
        assertEquals(first, database.configDao().get())
        assertEquals(firstUi, CoreRoutingHelper.parseUiRules(settings.coreRoutingUiRules))
        assertEquals(DatabaseConfig.Mode.Merge, first.routingMode)
        assertEquals(CoreRoutingHelper.CURRENT_DEFAULT_RULES_VERSION, settings.coreRoutingDefaultsVersion)
        assertEquals(5, firstUi.size)
        assertEquals(firstUi.size, firstUi.map { it.id }.toSet().size)
        assertTrue(firstUi.any { it.protocolsText == "bittorrent" && it.outboundTag == "direct" })
        assertTrue(firstUi.any { it.portsText == "443" && it.network == CoreRoutingTransport.Udp && it.outboundTag == "block" })
        assertTrue(firstUi.any { it.ipsText == "geoip:ru" && it.outboundTag == "direct" })
        assertTrue(firstUi.any { "127.0.0.0/8" in it.ipsText && it.outboundTag == "block" })
        database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM configs").use {
            assertTrue(it.moveToFirst())
            assertEquals(1, it.getInt(0))
        }
    }

    @Test
    fun legacyMigrationPreservesProviderKeysPriorityOtherSectionsAndDisabledUiRules() = runTest {
        val legacy = CoreRoutingRule(name = "Synthetic legacy", domainsText = "geosite:ru", outboundTag = "direct")
        val disabled = CoreRoutingRule(name = "Disabled custom", enabled = false, sourcePortsText = "1024-2048", outboundTag = "custom")
        settings.coreRoutingUiRules = CoreRoutingHelper.encodeUiRules(listOf(disabled, legacy))
        val original = DatabaseConfig(
            log = """{"access":"synthetic.log"}""", logMode = DatabaseConfig.Mode.Replace,
            dns = """{"servers":["127.0.0.1"]}""", dnsMode = DatabaseConfig.Mode.Merge,
            routingMode = DatabaseConfig.Mode.Replace,
            routing = """{"domainStrategy":"AsIs","provider":{"revision":9},"rules":[
                {"domain":["full:priority.test"],"outboundTag":"priority","providerRule":[1,true,null]},
                {"domain":["geosite:ru"],"outboundTag":"direct"},
                {"outboundTag":"fallback"}
            ]}""",
        )
        database.configDao().insert(original)

        val migrated = app.configRepository.get()

        assertEquals(original.copy(routing = migrated.routing), migrated)
        val routing = JSONObject(migrated.routing)
        assertEquals(listOf("priority", "direct", "fallback"), routing.getJSONArray("rules").objectValues().map { it.getString("outboundTag") })
        assertEquals("geosite:category-ru", routing.getJSONArray("rules").getJSONObject(1).getJSONArray("domain").getString(0))
        assertJsonEquals(JSONObject(original.routing).getJSONObject("provider"), routing.getJSONObject("provider"))
        assertJsonEquals(JSONObject(original.routing).getJSONArray("rules").getJSONObject(0), routing.getJSONArray("rules").getJSONObject(0))
        val ui = CoreRoutingHelper.parseUiRules(settings.coreRoutingUiRules)
        assertEquals(listOf(disabled.id, legacy.id), ui.map { it.id })
        assertFalse(ui.first().enabled)
        assertEquals("Synthetic legacy", ui.last().name)
        assertEquals(migrated, database.configDao().get())
        assertEquals(migrated, app.configRepository.get())
    }

    @Test
    fun seededUpgradeAddsMissingRuleWithoutReplacingExistingNamesIdentitiesOrSettings() = runTest {
        val old = CoreRoutingHelper.defaultSeededRules().filterNot { it.ipsText == "geoip:ru" }
            .mapIndexed { index, rule -> rule.copy(name = "Synthetic user label $index") }
        settings.coreRoutingDefaultsVersion = 1
        settings.coreRoutingUiRules = CoreRoutingHelper.encodeUiRules(old)
        val config = DatabaseConfig(routing = CoreRoutingHelper.buildRoutingJson("AsIs", old), routingMode = DatabaseConfig.Mode.Merge)
        database.configDao().insert(config)

        val upgraded = app.configRepository.get()
        val ui = CoreRoutingHelper.parseUiRules(settings.coreRoutingUiRules)

        assertEquals(5, ui.size)
        assertEquals(old, ui.filterNot { it.ipsText == "geoip:ru" })
        assertEquals("geoip:ru", ui[2].ipsText)
        assertEquals("direct", ui[2].outboundTag)
        assertEquals("AsIs", JSONObject(upgraded.routing).getString("domainStrategy"))
        assertEquals(CoreRoutingHelper.CURRENT_DEFAULT_RULES_VERSION, settings.coreRoutingDefaultsVersion)
        assertEquals(upgraded, app.configRepository.get())
        assertEquals(ui, CoreRoutingHelper.parseUiRules(settings.coreRoutingUiRules))
    }

    @Test
    fun customizedOrReorderedOldDefaultsAreNeverReplacedByUpgrade() = runTest {
        val old = CoreRoutingHelper.defaultSeededRules().filterNot { it.ipsText == "geoip:ru" }
        val variants = listOf(
            old.mapIndexed { index, rule -> if (index == 0) rule.copy(sourcePortsText = "1024-2048") else rule },
            old.mapIndexed { index, rule -> if (index == 0) rule.copy(enabled = false) else rule },
            old.mapIndexed { index, rule -> if (index == 2) rule.copy(network = CoreRoutingTransport.Tcp) else rule },
            old.reversed(),
        )
        database.configDao().insert(DatabaseConfig())
        variants.forEach { customized ->
            settings.coreRoutingDefaultsVersion = 1
            settings.coreRoutingUiRules = CoreRoutingHelper.encodeUiRules(customized)
            database.configDao().update(DatabaseConfig(routing = CoreRoutingHelper.buildRoutingJson("AsIs", customized)))
            val loaded = app.configRepository.get()
            val ui = CoreRoutingHelper.parseUiRules(settings.coreRoutingUiRules)
            assertEquals(customized, ui)
            assertFalse(ui.any { it.ipsText == "geoip:ru" })
            assertEquals(customized.count { it.enabled }, JSONObject(loaded.routing).getJSONArray("rules").length())
            assertEquals(CoreRoutingHelper.CURRENT_DEFAULT_RULES_VERSION, settings.coreRoutingDefaultsVersion)
        }
    }

    @Test
    fun unknownRuleAlongsideOldDefaultsPreventsDestructiveDefaultUpgrade() = runTest {
        val old = CoreRoutingHelper.defaultSeededRules().filterNot { it.ipsText == "geoip:ru" }
        val root = JSONObject(CoreRoutingHelper.buildRoutingJson("AsIs", old))
        root.getJSONArray("rules").put(JSONObject("""{"inboundTag":["synthetic"],"outboundTag":"custom"}"""))
        settings.coreRoutingDefaultsVersion = 1
        settings.coreRoutingUiRules = CoreRoutingHelper.encodeUiRules(old)
        database.configDao().insert(DatabaseConfig(routing = root.toString()))
        val loaded = app.configRepository.get()
        assertEquals(old, CoreRoutingHelper.parseUiRules(settings.coreRoutingUiRules))
        assertEquals(5, JSONObject(loaded.routing).getJSONArray("rules").length())
        assertEquals("custom", JSONObject(loaded.routing).getJSONArray("rules").getJSONObject(4).getString("outboundTag"))
    }

    @Test
    fun failedMigrationDoesNotPublishUiOrDefaultVersionBeforeRoomCommit() = runTest {
        val original = DatabaseConfig(routing = """{"rules":[{"domain":["geosite:ru"],"outboundTag":"direct"}]}""")
        database.configDao().insert(original)
        val uiBefore = settings.coreRoutingUiRules
        val versionBefore = settings.coreRoutingDefaultsVersion
        rejectWrites("UPDATE")

        val result = runCatching { app.configRepository.get() }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message.orEmpty().contains("synthetic_write_failure"))
        assertEquals(original, database.configDao().get())
        assertEquals(uiBefore, settings.coreRoutingUiRules)
        assertEquals(versionBefore, settings.coreRoutingDefaultsVersion)
    }

    @Test
    fun failedInitialInsertDoesNotPublishSeedSettings() = runTest {
        val uiBefore = settings.coreRoutingUiRules
        val versionBefore = settings.coreRoutingDefaultsVersion
        rejectWrites("INSERT")
        val result = runCatching { app.configRepository.get() }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message.orEmpty().contains("synthetic_write_failure"))
        assertNull(database.configDao().get())
        assertEquals(uiBefore, settings.coreRoutingUiRules)
        assertEquals(versionBefore, settings.coreRoutingDefaultsVersion)
    }

    @Test
    fun updatePersistsWholeRowWithoutTouchingRoutingUiPreferences() = runTest {
        app.configRepository.get()
        val uiBefore = settings.coreRoutingUiRules
        val updated = DatabaseConfig(
            log = """{"loglevel":"warning"}""", dns = """{"servers":["127.0.0.1"]}""",
            inbounds = """[{"listen":"127.0.0.1","port":19080}]""", outbounds = """[{"tag":"custom"}]""",
            routing = """{"rules":[{"port":"443","outboundTag":"custom"}]}""",
            logMode = DatabaseConfig.Mode.Replace, dnsMode = DatabaseConfig.Mode.Merge,
            inboundsMode = DatabaseConfig.Mode.Replace, outboundsMode = DatabaseConfig.Mode.Merge,
            routingMode = DatabaseConfig.Mode.Replace,
        )
        app.configRepository.update(updated)
        assertEquals(updated, database.configDao().get())
        assertEquals(uiBefore, settings.coreRoutingUiRules)
    }

    private fun rejectWrites(operation: String) {
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_config_write BEFORE $operation ON configs BEGIN SELECT RAISE(ABORT, 'synthetic_write_failure'); END",
        )
    }
}
