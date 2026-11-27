package io.github.derundevu.yaxc.transfer

import android.content.SharedPreferences
import io.github.derundevu.yaxc.Settings
import io.github.derundevu.yaxc.database.Config
import io.github.derundevu.yaxc.helper.AppDomainRule
import io.github.derundevu.yaxc.helper.CoreRoutingHelper
import io.github.derundevu.yaxc.helper.CoreRoutingMatchType
import io.github.derundevu.yaxc.helper.CoreRoutingTransport
import io.github.derundevu.yaxc.helper.LiveRoutingHelper
import io.github.derundevu.yaxc.helper.LiveRoutingPlan
import io.github.derundevu.yaxc.helper.ProxyGroup
import io.github.derundevu.yaxc.helper.ProxyGroupStrategy
import org.json.JSONArray
import org.json.JSONObject

internal class DeviceTransferRouting private constructor(
    private val appsMode: Settings.AppsRoutingMode,
    private val appPackages: List<String>,
    private val tunRoutes: Set<String>,
    private val antifilterEnabled: Boolean,
    private val antifilterAddress: String,
    private val coreRouting: JSONObject,
    private val coreMode: Config.Mode,
    private val coreUiRules: JSONArray,
    private val healthInterval: Int,
    val live: PortableLiveRouting,
) {
    fun updateConfig(config: Config?): Config = (config ?: Config()).copy(
        routing = coreRouting.toString(), routingMode = coreMode,
    )

    fun writeTo(editor: SharedPreferences.Editor, livePlan: String) {
        editor.putString("appsRoutingModeV2", appsMode.value)
            .putString("excludedApps", appPackages.joinToString("\n"))
            .putStringSet("tunRoutes", tunRoutes)
            .putBoolean("antifilterEnabled", antifilterEnabled)
            .putString("antifilterAddress", antifilterAddress)
            .putString("coreRoutingUiRules", coreUiRules.toString())
            .putInt("coreRoutingDefaultsVersion", CoreRoutingHelper.CURRENT_DEFAULT_RULES_VERSION)
            .putInt("liveRoutingHealthIntervalSeconds", healthInterval)
            .putString("liveRoutingPlan", livePlan)
    }

    fun encode(): JSONObject = JSONObject()
        .put("appsMode", appsMode.value).put("appPackages", JSONArray(appPackages))
        .put("tunRoutes", JSONArray(tunRoutes.sorted()))
        .put("antifilterEnabled", antifilterEnabled).put("antifilterAddress", antifilterAddress)
        .put("core", coreRouting).put("coreMode", coreMode.name).put("coreUi", coreUiRules)
        .put("healthInterval", healthInterval).put("live", live.encode())

    companion object {
        fun capture(settings: Settings, config: Config?, live: PortableLiveRouting): DeviceTransferRouting {
            val defaults = if (config == null) CoreRoutingHelper.defaultSeededRules() else emptyList()
            val routing = config?.routing?.ifBlank { "{}" }
                ?: CoreRoutingHelper.buildRoutingJson("IPIfNonMatch", defaults)
            val sidecar = if (config == null) CoreRoutingHelper.encodeUiRules(defaults) else settings.coreRoutingUiRules
            val appPackages = settings.appsRouting.lineSequence().map(String::trim).filter(String::isNotEmpty).toSortedSet()
            return decodeFields(JSONObject()
                .put("appsMode", settings.appsRoutingMode.value).put("appPackages", JSONArray(appPackages))
                .put("tunRoutes", JSONArray(settings.tunRoutes))
                .put("antifilterEnabled", settings.antifilterEnabled).put("antifilterAddress", settings.antifilterAddress)
                .put("core", DeviceTransferJson.parse(routing, DeviceTransferJson.MAX_CONFIG))
                .put("coreMode", (config?.routingMode ?: Config.Mode.Merge).name)
                .put("coreUi", DeviceTransferJson.parse(sidecar, DeviceTransferJson.MAX_CONFIG))
                .put("healthInterval", settings.liveRoutingHealthIntervalSeconds), live)
        }

        fun decode(json: JSONObject): DeviceTransferRouting {
            json.transferKeys("appsMode", "appPackages", "tunRoutes", "antifilterEnabled", "antifilterAddress",
                "core", "coreMode", "coreUi", "healthInterval", "live")
            return decodeFields(json, PortableLiveRouting.decode(json.transferValue("live").transferObject("live routing")))
        }

        private val packageName = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*")
        private val coreUiTextFields = setOf("id", "name", "domainsText", "ipsText", "portsText", "sourcePortsText",
            "protocolsText", "outboundTag", "valuesText")
        private val coreUiOptions = coreUiTextFields + setOf("enabled", "network", "transport", "matchType",
            "opaqueRulesBefore", "originalJson", "preserveOriginalMatchers")

        private fun decodeFields(json: JSONObject, live: PortableLiveRouting): DeviceTransferRouting {
            val mode = json.transferText("appsMode", 32)
            val appsMode = Settings.AppsRoutingMode.entries.firstOrNull { it.value == mode }
                ?: throw IllegalArgumentException("Unknown application routing mode.")
            val appPackages = readStrings(json, "appPackages", max = 255)
            require(appPackages.all(packageName::matches) && appPackages.toSet().size == appPackages.size) {
                "Invalid or duplicate package in application routing."
            }
            val routeList = readStrings(json, "tunRoutes", max = 256)
            require(routeList.all { it.isNotBlank() && it.none(Char::isWhitespace) }) { "Invalid VPN route." }
            val tunRoutes = routeList.toSet()
            require(tunRoutes.size == routeList.size) { "Duplicate VPN route." }
            val antifilterAddress = json.transferText("antifilterAddress")
            require(antifilterAddress.none(Char::isISOControl)) { "Invalid address for Antifilter." }
            val coreRouting = json.transferValue("core").transferObject("core routing")
            DeviceTransferJson.checkSize(coreRouting.toString(), DeviceTransferJson.MAX_CONFIG)
            if (coreRouting.has("domainStrategy")) coreRouting.transferText("domainStrategy", 64)
            for (key in listOf("rules", "balancers")) {
                if (!coreRouting.has(key)) continue
                val entries = coreRouting.transferValue(key).transferArray(key, DeviceTransferJson.MAX_ROUTES)
                for (i in 0 until entries.length()) entries.get(i).transferObject("core routing $key entry")
            }
            val rawMode = json.transferText("coreMode", 32)
            val coreMode = Config.Mode.entries.firstOrNull { it.name == rawMode }
                ?: throw IllegalArgumentException("Unknown core routing mode.")
            val coreUi = json.transferValue("coreUi").transferArray("core editor rules", DeviceTransferJson.MAX_ROUTES)
            DeviceTransferJson.checkSize(coreUi.toString(), DeviceTransferJson.MAX_CONFIG)
            val ids = HashSet<String>()
            for (i in 0 until coreUi.length()) {
                val rule = coreUi.get(i).transferObject("core editor rule")
                rule.transferKeys("id", optional = coreUiOptions)
                require(rule.transferText("id", 128).let { it.isNotBlank() && ids.add(it) }) { "Invalid core rule ID." }
                coreUiTextFields.filter { it != "id" && rule.has(it) }.forEach { rule.transferText(it) }
                if (rule.has("enabled")) rule.transferBoolean("enabled")
                if (rule.has("preserveOriginalMatchers")) rule.transferBoolean("preserveOriginalMatchers")
                if (rule.has("opaqueRulesBefore")) rule.transferValue("opaqueRulesBefore")
                    .transferInt("opaqueRulesBefore", 0..DeviceTransferJson.MAX_ROUTES)
                for (key in listOf("network", "transport")) if (rule.has(key)) {
                    val value = rule.transferText(key, 64)
                    require(CoreRoutingTransport.entries.any { it.name == value }) { "Unknown core routing transport." }
                }
                if (rule.has("matchType")) {
                    val value = rule.transferText("matchType", 32)
                    require(CoreRoutingMatchType.entries.any { it.name == value }) { "Unknown core rule match type." }
                }
                if (rule.has("originalJson")) DeviceTransferJson.parse(
                    rule.transferText("originalJson", DeviceTransferJson.MAX_CONFIG), DeviceTransferJson.MAX_CONFIG,
                ).transferObject("original core rule")
            }
            val health = json.transferValue("healthInterval").transferInt("healthInterval",
                Settings.MIN_LIVE_ROUTING_HEALTH_INTERVAL_SECONDS..Settings.MAX_LIVE_ROUTING_HEALTH_INTERVAL_SECONDS)
            return DeviceTransferRouting(appsMode, appPackages, tunRoutes, json.transferBoolean("antifilterEnabled"),
                antifilterAddress, coreRouting, coreMode, coreUi, health, live)
        }
    }
}

/** Live Routing rules keep their own IDs; member pointers are zero-based indexes into portable references. */
internal class PortableLiveRouting private constructor(
    val profiles: List<TransferProfileRef>,
    private val indexedPlan: LiveRoutingPlan,
) {
    fun resolve(ids: List<Long>): String {
        check(ids.size == profiles.size)
        return LiveRoutingHelper.encode(remap(indexedPlan) { ids[(it - 1).toInt()] })
    }

    fun encode(): JSONObject = JSONObject()
        .put("profiles", JSONArray().apply { profiles.forEach { put(it.encode()) } })
        .put("defaultGroupId", indexedPlan.defaultGroupId ?: JSONObject.NULL)
        .put("groups", JSONArray().apply {
            indexedPlan.groups.forEach { group ->
                put(JSONObject().put("id", group.id).put("name", group.name).put("strategy", group.strategy.name)
                    .put("checkAvailability", group.checkAvailability)
                    .put("members", JSONArray(group.profileIds.map { it - 1 }))
                    .put("selected", group.selectedProfileId?.minus(1) ?: JSONObject.NULL))
            }
        })
        .put("rules", JSONArray().apply {
            indexedPlan.rules.forEach { rule ->
                put(JSONObject().put("id", rule.id).put("name", rule.name).put("enabled", rule.enabled)
                    .put("packages", JSONArray(rule.packages)).put("domains", JSONArray(rule.domains))
                    .put("target", rule.target))
            }
        })

    companion object {
        fun capture(plan: LiveRoutingPlan, resolve: (Long) -> TransferProfileRef): PortableLiveRouting {
            val index = LinkedHashMap<TransferProfileRef, Long>()
            val portable = remap(plan) { profileId -> index.getOrPut(resolve(profileId)) { index.size + 1L } }
            val result = PortableLiveRouting(index.keys.toList(), portable)
            // Use the wire validators for local plan limits as well, without serializing/parsing JSON again.
            return decode(result.encode())
        }

        fun decode(json: JSONObject): PortableLiveRouting {
            json.transferKeys("profiles", "defaultGroupId", "groups", "rules")
            val profiles = json.transferValue("profiles").transferArray("live routing profiles", DeviceTransferJson.MAX_PROFILES)
                .mapTransfer { TransferProfileRef.decode(it.transferObject("live routing profile")) }
            require(profiles.toSet().size == profiles.size) { "Duplicate Live Routing profile reference." }
            val used = BooleanArray(profiles.size)
            val indexes = profiles.indices
            val groups = json.transferValue("groups").transferArray("live proxy groups", DeviceTransferJson.MAX_GROUPS)
                .mapTransfer { value ->
                    val group = value.transferObject("live proxy group")
                    group.transferKeys("id", "name", "strategy", "checkAvailability", "members", "selected")
                    val strategyName = group.transferText("strategy", 32)
                    val strategy = ProxyGroupStrategy.entries.firstOrNull { it.name == strategyName }
                        ?: throw IllegalArgumentException("Unknown Live Routing strategy.")
                    val members = group.transferValue("members").transferArray("profile members", DeviceTransferJson.MAX_PROFILES)
                        .mapTransfer { it.transferInt("profile member", indexes).let { index -> used[index] = true; index + 1L } }
                    val selected = group.transferValue("selected").let {
                        if (it === JSONObject.NULL) null else it.transferInt("selected profile", indexes) + 1L
                    }
                    ProxyGroup(id = group.transferText("id", 128), name = group.transferText("name", DeviceTransferJson.MAX_NAME),
                        profileIds = members, strategy = strategy, selectedProfileId = selected,
                        checkAvailability = group.transferBoolean("checkAvailability"))
                }
            require(used.all { it }) { "Unused Live Routing profile reference." }
            val rules = json.transferValue("rules").transferArray("live routing rules", DeviceTransferJson.MAX_ROUTES)
                .mapTransfer { value ->
                    val rule = value.transferObject("live routing rule")
                    rule.transferKeys("id", "name", "enabled", "packages", "domains", "target")
                    AppDomainRule(id = rule.transferText("id", 128), name = rule.transferText("name", DeviceTransferJson.MAX_NAME),
                        enabled = rule.transferBoolean("enabled"), packages = readStrings(rule, "packages", max = 255),
                        domains = readStrings(rule, "domains"), target = rule.transferText("target", 128))
                }
            val default = json.transferValue("defaultGroupId").let {
                if (it === JSONObject.NULL) null else it.transferString("default group", 128)
            }
            val plan = LiveRoutingPlan(groups, rules, default)
            LiveRoutingHelper.validate(plan)
            return PortableLiveRouting(profiles, plan)
        }

        private fun remap(plan: LiveRoutingPlan, map: (Long) -> Long): LiveRoutingPlan = plan.copy(
            groups = plan.groups.map { group ->
                group.copy(profileIds = group.profileIds.map(map), selectedProfileId = group.selectedProfileId?.let(map))
            },
        )
    }
}

private fun readStrings(json: JSONObject, key: String, max: Int = DeviceTransferJson.MAX_TEXT): List<String> =
    json.transferValue(key).transferArray(key, DeviceTransferJson.MAX_ROUTES).mapTransfer { it.transferString(key, max) }
