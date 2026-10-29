package io.github.derundevu.yaxc.viewmodel

import androidx.lifecycle.viewModelScope
import io.github.derundevu.yaxc.database.Config as DatabaseConfig
import io.github.derundevu.yaxc.helper.CoreRoutingEditorMode
import io.github.derundevu.yaxc.helper.CoreRoutingHelper
import io.github.derundevu.yaxc.helper.CoreRoutingRule
import io.github.derundevu.yaxc.helper.CoreRoutingTransport
import io.github.derundevu.yaxc.helper.assertJsonEquals
import io.github.derundevu.yaxc.helper.objectValues
import io.github.derundevu.yaxc.testing.MainDispatcherRule
import io.github.derundevu.yaxc.testing.RegressionApplication
import io.github.derundevu.yaxc.testing.RoomTestSupport
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = RegressionApplication::class, sdk = [34])
class CoreRoutingEditorViewModelTest : RoomTestSupport() {
    @get:Rule val mainDispatcher = MainDispatcherRule()
    private val models = mutableListOf<CoreRoutingEditorViewModel>()

    @After
    fun clearModels() {
        models.forEach { it.viewModelScope.cancel() }
    }

    @Test
    fun visualSavePreservesProviderDataAndInterleavedFirstMatchPriority() = runTest(mainDispatcher.dispatcher) {
        val original = seedMixedRouting()
        val editor = editor()
        assertEquals(3, editor.unsupportedRuleCount)
        val target = editor.rules.first()
        editor.updateRule(target.copy(name = "Edited synthetic", portsText = "1,65535", sourcePortsText = "1024-2048", network = CoreRoutingTransport.Tcp, outboundTag = "edited"))
        editor.updateDomainStrategy("IPOnDemand")

        assertTrue(editor.save())

        val stored = database.configDao().get()!!
        assertEquals(original.copy(routing = stored.routing, routingMode = DatabaseConfig.Mode.Merge), stored)
        val json = JSONObject(stored.routing)
        assertEquals(listOf("first", "edited", "middle", "b", "fallback"), tags(json.getJSONArray("rules")))
        assertEquals("IPOnDemand", json.getString("domainStrategy"))
        assertJsonEquals(JSONObject(original.routing).getJSONObject("provider"), json.getJSONObject("provider"))
        assertJsonEquals(JSONObject(original.routing).getJSONArray("rules").getJSONObject(0), json.getJSONArray("rules").getJSONObject(0))
        val edited = json.getJSONArray("rules").getJSONObject(1)
        assertEquals("1,65535", edited.getString("port"))
        assertEquals("1024-2048", edited.getString("sourcePort"))
        assertEquals("tcp", edited.getString("network"))
        assertEquals(1, editor.savedRevision)
        assertFalse(editor.isBusy)
        val reopened = editor()
        assertEquals(target.id, reopened.rules.first().id)
        assertEquals("Edited synthetic", reopened.rules.first().name)
        assertEquals(3, reopened.unsupportedRuleCount)
    }

    @Test
    fun disabledRuleRetainsIdentityPositionAndAllFieldsAcrossModesSaveAndReopen() = runTest(mainDispatcher.dispatcher) {
        val first = CoreRoutingRule(name = "Before", domainsText = "full:before.test", outboundTag = "direct")
        val disabled = CoreRoutingRule(name = "Disabled synthetic", enabled = false, ipsText = "192.0.2.0/24", portsText = "443", sourcePortsText = "1024", protocolsText = "tls", network = CoreRoutingTransport.Tcp, outboundTag = "custom")
        val last = CoreRoutingRule(name = "After", portsText = "53", outboundTag = "block")
        seed(listOf(first, disabled, last))
        val editor = editor()
        assertEquals(listOf(first, disabled, last), editor.rules)
        assertTrue(editor.switchMode(CoreRoutingEditorMode.Json))
        assertEquals(listOf("direct", "block"), tags(JSONObject(editor.document.text).getJSONArray("rules")))
        assertTrue(editor.switchMode(CoreRoutingEditorMode.Visual))
        assertEquals(listOf(first, disabled, last), editor.rules)
        assertTrue(editor.save())
        val reopened = editor()
        assertEquals(listOf(first, disabled, last), reopened.rules)
        reopened.updateRule(reopened.rules[1].copy(enabled = true))
        assertTrue(reopened.save())
        assertEquals(listOf("direct", "custom", "block"), tags(JSONObject(database.configDao().get()!!.routing).getJSONArray("rules")))
        assertTrue(CoreRoutingHelper.parseUiRules(settings.coreRoutingUiRules)[1].enabled)
    }

    @Test
    fun jsonSaveKeepsVisibleSourceAndSwitchToVisualRetainsUnknownRulesAndTopLevelSettings() = runTest(mainDispatcher.dispatcher) {
        seed(listOf(CoreRoutingRule(portsText = "80", outboundTag = "old")))
        val editor = editor()
        assertTrue(editor.switchMode(CoreRoutingEditorMode.Json))
        val raw = """{ "provider": {"revision": 42}, "domainStrategy": "AsIs", "rules": [
            {"domain":["full:priority.test"],"outboundTag":"priority","futureRule":{"x":true}},
            {"port":65535,"network":["udp"],"outboundTag":"custom"}
        ] }"""
        editor.document.text = raw
        assertTrue(editor.save())
        assertEquals(raw, editor.document.text)
        assertEquals(raw, database.configDao().get()!!.routing)
        assertEquals(1, editor.unsupportedRuleCount)
        assertTrue(editor.switchMode(CoreRoutingEditorMode.Visual))
        assertEquals("65535", editor.rules.single().portsText)
        assertEquals(CoreRoutingTransport.Udp, editor.rules.single().network)
        editor.updateRule(editor.rules.single().copy(outboundTag = "edited"))
        assertTrue(editor.save())
        val routing = JSONObject(database.configDao().get()!!.routing)
        assertEquals(listOf("priority", "edited"), tags(routing.getJSONArray("rules")))
        assertEquals(42, routing.getJSONObject("provider").getInt("revision"))
        assertJsonEquals(JSONObject(raw).getJSONArray("rules").getJSONObject(0), routing.getJSONArray("rules").getJSONObject(0))
    }

    @Test
    fun invalidJsonDraftCannotSwitchSaveExportOrOverwriteLastVisualState() = runTest(mainDispatcher.dispatcher) {
        seedMixedRouting()
        val editor = editor()
        val rulesBefore = editor.rules
        val strategyBefore = editor.domainStrategy
        val rowBefore = database.configDao().get()
        val uiBefore = settings.coreRoutingUiRules
        assertTrue(editor.switchMode(CoreRoutingEditorMode.Json))
        val broken = """{"rules":[{"port":"443"},]}"""
        editor.document.text = broken

        assertFalse(editor.switchMode(CoreRoutingEditorMode.Visual))
        assertFalse(editor.save())
        assertNull(editor.exportRules())
        assertNull(editor.importRules("false"))

        assertEquals(CoreRoutingEditorMode.Json, editor.editorMode)
        assertEquals(broken, editor.document.text)
        assertEquals(rulesBefore, editor.rules)
        assertEquals(strategyBefore, editor.domainStrategy)
        assertEquals(rowBefore, database.configDao().get())
        assertEquals(uiBefore, settings.coreRoutingUiRules)
        assertEquals(0, editor.savedRevision)
        assertFalse(editor.isBusy)
        assertEquals(3, editor.unsupportedRuleCount)
    }

    @Test
    fun wrongRootJsonDraftCannotReplaceRoutingObject() = runTest(mainDispatcher.dispatcher) {
        seedMixedRouting()
        val editor = editor()
        val before = database.configDao().get()
        assertTrue(editor.switchMode(CoreRoutingEditorMode.Json))
        editor.document.text = """[{"port":"443","outboundTag":"custom"}]"""
        assertFalse(editor.switchMode(CoreRoutingEditorMode.Visual))
        assertFalse(editor.save())
        assertEquals(before, database.configDao().get())
    }

    @Test
    fun failedRoomSaveDoesNotPartiallyPersistUiOrAdvanceRevisionAndCanRetry() = runTest(mainDispatcher.dispatcher) {
        seedMixedRouting()
        val editor = editor()
        val rowBefore = database.configDao().get()
        val uiBefore = settings.coreRoutingUiRules
        val documentBefore = editor.document.text
        val changed = editor.rules.first().copy(name = "Unsaved label", outboundTag = "unsaved")
        editor.updateRule(changed)
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_routing_save BEFORE UPDATE ON configs BEGIN SELECT RAISE(ABORT, 'synthetic_save_failure'); END",
        )

        val failed = runCatching { editor.save() }

        assertTrue(failed.isFailure)
        assertTrue(failed.exceptionOrNull()!!.message.orEmpty().contains("synthetic_save_failure"))
        assertEquals(rowBefore, database.configDao().get())
        assertEquals(uiBefore, settings.coreRoutingUiRules)
        assertEquals(documentBefore, editor.document.text)
        assertEquals(changed, editor.rules.first())
        assertEquals(0, editor.savedRevision)
        assertFalse(editor.isBusy)
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_routing_save")
        assertTrue(editor.save())
        assertEquals(1, editor.savedRevision)
        assertEquals("unsaved", JSONObject(database.configDao().get()!!.routing).getJSONArray("rules").getJSONObject(1).getString("outboundTag"))
        assertEquals("Unsaved label", CoreRoutingHelper.parseUiRules(settings.coreRoutingUiRules).first().name)
    }

    @Test
    fun importExportPreservesDisabledMetadataAndUnsupportedPriorityWithoutPersistingUntilSave() = runTest(mainDispatcher.dispatcher) {
        seedMixedRouting()
        val editor = editor()
        val rowBefore = database.configDao().get()
        val raw = """{"domainStrategy":"AsIs","provider":{"revision":8},"rules":[
            {"inboundTag":["synthetic"],"outboundTag":"priority"},
            {"remarks":"Synthetic disabled","enabled":false,"port":"65535","network":"udp","outboundTag":"disabled"},
            {"protocol":["tls"],"outboundTag":"active"},
            {"balancerTag":"fallback","futureRule":true}
        ]}"""
        assertEquals(2, editor.importRules(raw))
        assertEquals(CoreRoutingEditorMode.Visual, editor.editorMode)
        assertEquals(rowBefore, database.configDao().get())
        assertEquals("AsIs", editor.domainStrategy)
        assertEquals(2, editor.unsupportedRuleCount)
        assertFalse(editor.rules.first().enabled)
        val exported = JSONArray(editor.exportRules()!!)
        assertEquals(listOf("priority", "disabled", "active", "fallback"), tags(exported))
        assertFalse(exported.getJSONObject(1).getBoolean("enabled"))
        assertEquals("Synthetic disabled", exported.getJSONObject(1).getString("remarks"))
        assertJsonEquals(JSONObject(raw).getJSONArray("rules").getJSONObject(0), exported.getJSONObject(0))
        assertTrue(editor.switchMode(CoreRoutingEditorMode.Json))
        assertEquals(listOf("priority", "disabled", "active", "fallback"), tags(JSONArray(editor.exportRules()!!)))
        assertTrue(editor.save())
        assertEquals(listOf("priority", "active", "fallback"), tags(JSONObject(database.configDao().get()!!.routing).getJSONArray("rules")))
        val reopened = editor()
        assertFalse(reopened.rules.first().enabled)
        assertEquals("Synthetic disabled", reopened.rules.first().name)
        assertEquals(8, JSONObject(database.configDao().get()!!.routing).getJSONObject("provider").getInt("revision"))
    }

    @Test
    fun ruleEditingBoundariesAndVisualToJsonRespectExplicitSupportedRuleOrder() = runTest(mainDispatcher.dispatcher) {
        seedMixedRouting()
        val editor = editor()
        val before = editor.rules
        editor.moveRule(-1, 0)
        editor.moveRule(0, before.size)
        editor.moveRule(0, 0)
        editor.deleteRule("missing-id")
        assertEquals(before, editor.rules)
        editor.moveRule(1, 0)
        assertEquals(before.reversed().map { it.id }, editor.rules.map { it.id })
        editor.addRule()
        val added = editor.rules.last()
        editor.updateRule(added.copy(name = "Added synthetic", sourcePortsText = "65535", outboundTag = "new"))
        editor.deleteRule(before.first().id)
        assertTrue(editor.switchMode(CoreRoutingEditorMode.Json))
        assertEquals(listOf("first", "middle", "b", "fallback", "new"), tags(JSONObject(editor.document.text).getJSONArray("rules")))
        assertTrue(editor.save())
        val reopened = editor()
        assertEquals(listOf(before.last().id, added.id), reopened.rules.map { it.id })
        assertEquals("65535", reopened.rules.last().sourcePortsText)
        assertEquals(3, reopened.unsupportedRuleCount)
    }

    @Test
    fun emptyEffectiveRoutingDisablesOverlayButRetainsDisabledUiDraft() = runTest(mainDispatcher.dispatcher) {
        val disabled = CoreRoutingRule(name = "Disabled draft", enabled = false, portsText = "443", outboundTag = "block")
        seed(listOf(disabled), "IPIfNonMatch")
        val editor = editor()
        assertTrue(editor.save())
        assertEquals(DatabaseConfig.Mode.Disable, database.configDao().get()!!.routingMode)
        assertEquals("{}", database.configDao().get()!!.routing)
        assertEquals(listOf(disabled), editor().rules)
        val reopened = editor()
        reopened.updateDomainStrategy("AsIs")
        assertTrue(reopened.save())
        assertEquals(DatabaseConfig.Mode.Merge, database.configDao().get()!!.routingMode)
        assertEquals("AsIs", JSONObject(database.configDao().get()!!.routing).getString("domainStrategy"))
    }

    @Test
    fun modeConversionRejectsStaleParsedSnapshotAndKeepsNewerDraft() = runTest(mainDispatcher.dispatcher) {
        seed(listOf(CoreRoutingRule(portsText = "80", outboundTag = "old")))
        val editor = editor()
        val before = editor.rules
        assertTrue(editor.switchMode(CoreRoutingEditorMode.Json))
        editor.document.text = """{"rules":[{"port":"443","outboundTag":"snapshot"}]}"""
        val switching = async(start = CoroutineStart.UNDISPATCHED) { editor.switchMode(CoreRoutingEditorMode.Visual) }
        assertTrue(editor.isBusy)
        val newer = """{"rules":[{"port":"65535","outboundTag":"newer"}]}"""
        editor.document.text = newer
        assertFalse(switching.await())
        assertEquals(CoreRoutingEditorMode.Json, editor.editorMode)
        assertEquals(newer, editor.document.text)
        assertEquals(before, editor.rules)
        assertFalse(editor.isBusy)
        assertTrue(editor.switchMode(CoreRoutingEditorMode.Visual))
        assertEquals("newer", editor.rules.single().outboundTag)
    }

    @Test
    fun jsonSavePersistsCapturedSnapshotWithoutErasingNewerVisibleDraft() = runTest(mainDispatcher.dispatcher) {
        seed(listOf(CoreRoutingRule(portsText = "80", outboundTag = "old")))
        val editor = editor()
        assertTrue(editor.switchMode(CoreRoutingEditorMode.Json))
        val snapshot = """{"rules":[{"port":"443","outboundTag":"snapshot"}]}"""
        val newer = """{"rules":[{"port":"65535","outboundTag":"newer"}]}"""
        editor.document.text = snapshot
        val saving = async(start = CoroutineStart.UNDISPATCHED) { editor.save() }
        assertTrue(editor.isBusy)
        editor.document.text = newer
        editor.addRule()
        editor.updateRule(editor.rules.single().copy(outboundTag = "ignored-while-busy"))
        assertTrue(saving.await())
        assertEquals(snapshot, database.configDao().get()!!.routing)
        assertEquals(newer, editor.document.text)
        assertTrue(editor.switchMode(CoreRoutingEditorMode.Visual))
        assertEquals("newer", editor.rules.single().outboundTag)
        assertTrue(editor.save())
        assertEquals("newer", JSONObject(database.configDao().get()!!.routing).getJSONArray("rules").getJSONObject(0).getString("outboundTag"))
    }

    @Test
    fun enablingHiddenRuleAfterReopenDoesNotMoveItAcrossAnOpaqueRule() = runTest(mainDispatcher.dispatcher) {
        settings.coreRoutingDefaultsVersion = CoreRoutingHelper.CURRENT_DEFAULT_RULES_VERSION
        database.configDao().insert(DatabaseConfig(
            routingMode = DatabaseConfig.Mode.Merge,
            routing = """{"domainStrategy":"AsIs","rules":[
                {"port":"443","outboundTag":"disabled-first","enabled":false},
                {"inboundTag":["local"],"outboundTag":"opaque-second"}
            ]}""",
        ))
        val first = editor()
        val identity = first.rules.single().id
        assertTrue(first.save())
        val reopened = editor()
        assertEquals(identity, reopened.rules.single().id)
        assertFalse(reopened.rules.single().enabled)
        reopened.updateRule(reopened.rules.single().copy(enabled = true))
        assertTrue(reopened.save())
        assertEquals(listOf("disabled-first", "opaque-second"),
            tags(JSONObject(database.configDao().get()!!.routing).getJSONArray("rules")))
    }

    private suspend fun editor(): CoreRoutingEditorViewModel = CoreRoutingEditorViewModel(app).also {
        models += it
        it.viewModelScope.coroutineContext[Job]!!.children.toList().joinAll()
        assertFalse(it.isLoading)
    }

    private suspend fun seed(rules: List<CoreRoutingRule>, strategy: String = "AsIs") {
        settings.coreRoutingDefaultsVersion = CoreRoutingHelper.CURRENT_DEFAULT_RULES_VERSION
        settings.coreRoutingUiRules = CoreRoutingHelper.encodeUiRules(rules)
        database.configDao().insert(DatabaseConfig(routing = CoreRoutingHelper.buildRoutingJson(strategy, rules), routingMode = DatabaseConfig.Mode.Merge))
    }

    private suspend fun seedMixedRouting(): DatabaseConfig {
        val config = DatabaseConfig(
            log = """{"loglevel":"warning"}""", logMode = DatabaseConfig.Mode.Replace,
            dns = """{"servers":["127.0.0.1"]}""", dnsMode = DatabaseConfig.Mode.Merge,
            routingMode = DatabaseConfig.Mode.Merge,
            routing = """{"domainStrategy":"AsIs","provider":{"revision":3,"values":[true,null,{"kept":"yes"}]},"rules":[
                {"domain":["full:priority.test"],"outboundTag":"first","providerRule":{"enabled":true}},
                {"domain":["domain:test"],"outboundTag":"a"},
                {"inboundTag":["local"],"outboundTag":"middle"},
                {"port":"443","outboundTag":"b"},
                {"outboundTag":"fallback"}
            ]}""",
        )
        settings.coreRoutingDefaultsVersion = CoreRoutingHelper.CURRENT_DEFAULT_RULES_VERSION
        database.configDao().insert(config)
        return config
    }

    private fun tags(array: JSONArray): List<String> = array.objectValues().map {
        it.optString("outboundTag").ifBlank { it.getString("balancerTag") }
    }
}
