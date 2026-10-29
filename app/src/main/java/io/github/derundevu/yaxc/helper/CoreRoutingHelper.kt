package io.github.derundevu.yaxc.helper

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.UUID

enum class RoutingEditorTab {
    Apps,
    Core,
}

enum class CoreRoutingEditorMode {
    Visual,
    Json,
}

enum class CoreRoutingMatchType(val jsonKey: String) {
    Domain("domain"),
    Ip("ip"),
    Port("port"),
    SourcePort("sourcePort"),
    Protocol("protocol"),
}

enum class CoreRoutingTransport(val jsonValue: String?) {
    Any(null),
    Tcp("tcp"),
    Udp("udp");

    companion object {
        fun fromJsonValue(value: String?): CoreRoutingTransport {
            val normalized = value?.trim()?.lowercase().orEmpty()
            return when {
                normalized.isBlank() -> Any
                normalized == "tcp" -> Tcp
                normalized == "udp" -> Udp
                normalized.contains("tcp") && normalized.contains("udp") -> Any
                else -> Any
            }
        }

        fun fromJsonValue(value: Any?): CoreRoutingTransport {
            return when (value) {
                is JSONArray -> {
                    val entries = buildSet {
                        for (index in 0 until value.length()) {
                            val item = value.optString(index).trim().lowercase()
                            if (item.isNotEmpty()) add(item)
                        }
                    }
                    when {
                        entries.isEmpty() -> Any
                        entries == setOf("tcp") -> Tcp
                        entries == setOf("udp") -> Udp
                        else -> Any
                    }
                }

                is String -> fromJsonValue(value)
                else -> Any
            }
        }
    }
}

data class CoreRoutingRule(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val enabled: Boolean = true,
    val domainsText: String = "",
    val ipsText: String = "",
    val portsText: String = "",
    val sourcePortsText: String = "",
    val protocolsText: String = "",
    val network: CoreRoutingTransport = CoreRoutingTransport.Any,
    val outboundTag: String = "proxy",
    /** Disabled drafts are absent from native JSON, so the UI sidecar retains their opaque-rule boundary. */
    val opaqueRulesBefore: Int? = null,
)

sealed interface CoreRoutingRuleSlot {
    data class Editable(val id: String) : CoreRoutingRuleSlot
    data class Preserved(val json: JSONObject) : CoreRoutingRuleSlot
}

data class ParsedCoreRouting(
    val domainStrategy: String,
    val rules: List<CoreRoutingRule>,
    val ruleOrder: List<CoreRoutingRuleSlot>,
    val preservedTopLevel: JSONObject,
    val unsupportedRuleCount: Int,
)

object CoreRoutingHelper {

    const val CURRENT_DEFAULT_RULES_VERSION = 2

    private const val DEFAULT_DOMAIN_STRATEGY = "IPIfNonMatch"
    private const val LEGACY_RU_DOMAIN_RULE = "geosite:ru"
    private const val LEGACY_RU_IP_RULE = "geoip:ru"
    private const val LEGACY_PRIVATE_IP_RULE = "geoip:private"
    private val LEGACY_RU_DOMAIN_FALLBACK_VALUES = listOf("domain:ru", "domain:xn--p1ai")
    private const val DEFAULT_RU_DOMAIN_VALUES = "geosite:category-ru"
    private const val DEFAULT_RU_IP_VALUES = "geoip:ru"
    private const val DEFAULT_PRIVATE_IP_VALUES = """
10.0.0.0/8
100.64.0.0/10
127.0.0.0/8
169.254.0.0/16
172.16.0.0/12
192.168.0.0/16
::1/128
fc00::/7
fe80::/10
"""

    private val supportedRuleKeys = setOf(
        "domain",
        "ip",
        "port",
        "sourcePort",
        "protocol",
        "network",
        "outboundTag",
        "remarks",
        "enabled",
        "locked",
    )

    fun defaultRule(
        index: Int,
        name: String = "Rule $index",
    ): CoreRoutingRule {
        return CoreRoutingRule(name = name)
    }

    fun defaultSeededRules(): List<CoreRoutingRule> = listOf(
        CoreRoutingRule(
            name = "BitTorrent direct",
            protocolsText = "bittorrent",
            outboundTag = "direct",
        ),
        CoreRoutingRule(
            name = "UDP 443 block",
            portsText = "443",
            network = CoreRoutingTransport.Udp,
            outboundTag = "block",
        ),
        defaultRuIpRule(),
        CoreRoutingRule(
            name = "RU domain direct",
            domainsText = DEFAULT_RU_DOMAIN_VALUES,
            outboundTag = "direct",
        ),
        CoreRoutingRule(
            name = "Block private",
            ipsText = DEFAULT_PRIVATE_IP_VALUES.trimIndent(),
            outboundTag = "block",
        ),
    )

    internal fun defaultRuIpRule(): CoreRoutingRule = CoreRoutingRule(
        name = "RU IP direct",
        ipsText = DEFAULT_RU_IP_VALUES,
        outboundTag = "direct",
    )

    fun parseUiRules(raw: String): List<CoreRoutingRule> {
        val array = runCatching { JSONArray(raw) }.getOrElse { return emptyList() }
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                add(parseUiRuleItem(item))
            }
        }
    }

    fun migrateLegacyRules(rules: List<CoreRoutingRule>): List<CoreRoutingRule> {
        return rules.map { rule ->
            rule.copy(
                domainsText = migrateDomains(rule.domainsText),
                ipsText = migrateIps(rule.ipsText),
            )
        }
    }

    fun shouldUpgradeSeededDefaults(rules: List<CoreRoutingRule>): Boolean {
        if (rules.size != 4) return false
        return matchesDefaultRule(rules[0], protocolsText = "bittorrent", outboundTag = "direct") &&
            matchesDefaultRule(rules[1], portsText = "443", network = CoreRoutingTransport.Udp, outboundTag = "block") &&
            matchesDefaultRule(rules[2], domainsText = DEFAULT_RU_DOMAIN_VALUES, outboundTag = "direct") &&
            matchesDefaultRule(rules[3], ipsText = DEFAULT_PRIVATE_IP_VALUES, outboundTag = "block")
    }

    fun encodeUiRules(
        rules: List<CoreRoutingRule>,
        ruleOrder: List<CoreRoutingRuleSlot> = emptyList(),
    ): String {
        val opaquePositions = if (rules.any { !it.enabled } && ruleOrder.any { it is CoreRoutingRuleSlot.Preserved }) {
            val positions = mutableMapOf<String, Int>()
            var opaqueCount = 0
            forEachOrderedRule(rules, ruleOrder,
                onRule = { if (!it.enabled) positions[it.id] = opaqueCount },
                onPreserved = { opaqueCount += 1 },
            )
            positions
        } else emptyMap()
        val array = JSONArray()
        rules.forEach { rule ->
            array.put(
                JSONObject()
                    .put("id", rule.id)
                    .put("name", rule.name)
                    .put("enabled", rule.enabled)
                    .put("domainsText", rule.domainsText)
                    .put("ipsText", rule.ipsText)
                    .put("portsText", rule.portsText)
                    .put("sourcePortsText", rule.sourcePortsText)
                    .put("protocolsText", rule.protocolsText)
                    .put("network", rule.network.name)
                    .put("outboundTag", rule.outboundTag)
                    .apply { opaquePositions[rule.id]?.let { put("opaqueRulesBefore", it) } }
            )
        }
        return array.toString()
    }

    fun parseRoutingJson(
        rawJson: String,
        storedUiRules: List<CoreRoutingRule> = emptyList(),
        defaultRuleName: (Int) -> String = { "Rule $it" },
    ): ParsedCoreRouting {
        val json = JSONObject(rawJson.ifBlank { "{}" })
        val rulesArray = json.optJSONArray("rules") ?: JSONArray()
        val preservedTopLevel = JSONObject()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key != "domainStrategy" && key != "rules") {
                preservedTopLevel.put(key, json.get(key))
            }
        }

        val parsed = parseImportedRulesArray(
            rulesArray,
            json.optString("domainStrategy").ifBlank { DEFAULT_DOMAIN_STRATEGY },
            defaultRuleName,
        )
        return applyStoredUiState(
            parsed = parsed.copy(preservedTopLevel = preservedTopLevel),
            storedUiRules = migrateLegacyRules(storedUiRules),
        )
    }

    fun parseImportedRules(
        rawJson: String,
        fallbackDomainStrategy: String = DEFAULT_DOMAIN_STRATEGY,
        defaultRuleName: (Int) -> String = { "Rule $it" },
    ): ParsedCoreRouting {
        val root = JSONTokener(rawJson.trim()).nextValue()
        return when (root) {
            is JSONArray -> parseImportedRulesArray(root, fallbackDomainStrategy, defaultRuleName)
            is JSONObject -> {
                if (root.has("rules") || root.has("domainStrategy")) {
                    if (root.optString("domainStrategy").isBlank()) {
                        root.put("domainStrategy", fallbackDomainStrategy)
                    }
                    parseRoutingJson(
                        rawJson = root.toString(),
                        defaultRuleName = defaultRuleName,
                    )
                } else {
                    parseImportedRulesArray(
                        rulesArray = JSONArray().put(root),
                        domainStrategy = fallbackDomainStrategy,
                        defaultRuleName = defaultRuleName,
                    )
                }
            }

            else -> throw IllegalArgumentException("Unsupported routing JSON")
        }
    }

    fun buildRoutingJson(
        domainStrategy: String,
        rules: List<CoreRoutingRule>,
        ruleOrder: List<CoreRoutingRuleSlot> = emptyList(),
        preservedTopLevel: JSONObject = JSONObject(),
    ): String {
        val routing = JSONObject(preservedTopLevel.toString())
        routing.put("domainStrategy", domainStrategy.ifBlank { DEFAULT_DOMAIN_STRATEGY })

        val rulesArray = buildOrderedRules(rules, ruleOrder, ::buildRule)

        if (rulesArray.length() > 0 || routing.length() > 0) {
            routing.put("rules", rulesArray)
        }

        return routing.toString(4)
    }

    fun buildRulesExchangeJson(
        rules: List<CoreRoutingRule>,
        defaultRuleName: String = "Rule",
        ruleOrder: List<CoreRoutingRuleSlot> = emptyList(),
    ): String {
        val array = buildOrderedRules(rules, ruleOrder) { buildExchangeRule(it, defaultRuleName) }
        return array.toString(4)
    }

    private inline fun buildOrderedRules(
        rules: List<CoreRoutingRule>,
        ruleOrder: List<CoreRoutingRuleSlot>,
        build: (CoreRoutingRule) -> JSONObject?,
    ): JSONArray {
        val array = JSONArray()
        forEachOrderedRule(rules, ruleOrder,
            onRule = { build(it)?.let(array::put) },
            onPreserved = { array.put(it) },
        )
        return array
    }

    private inline fun forEachOrderedRule(
        rules: List<CoreRoutingRule>,
        ruleOrder: List<CoreRoutingRuleSlot>,
        onRule: (CoreRoutingRule) -> Unit,
        onPreserved: (JSONObject) -> Unit,
    ) {
        if (ruleOrder.none { it is CoreRoutingRuleSlot.Preserved }) {
            rules.forEach(onRule)
            return
        }
        // Opaque slots keep their priority while surviving editable slots follow the visual order.
        val remainingSlots = rules.mapTo(mutableSetOf()) { it.id }
        val orderedIds = mutableSetOf<String>()
        ruleOrder.forEach { if (it is CoreRoutingRuleSlot.Editable) orderedIds.add(it.id) }
        var nextRuleIndex = 0
        ruleOrder.forEach { slot ->
            when (slot) {
                is CoreRoutingRuleSlot.Editable -> if (remainingSlots.remove(slot.id)) {
                    while (rules[nextRuleIndex].id !in orderedIds) nextRuleIndex += 1
                    onRule(rules[nextRuleIndex++])
                }
                is CoreRoutingRuleSlot.Preserved -> onPreserved(slot.json)
            }
        }
        rules.forEach { if (it.id !in orderedIds) onRule(it) }
    }

    fun isMeaningfulRoutingJson(json: String): Boolean {
        val routing = runCatching { JSONObject(json) }.getOrNull() ?: return false
        if (routing.length() == 0) return false
        if (routing.optJSONArray("rules")?.length() ?: 0 > 0) return true
        val domainStrategy = routing.optString("domainStrategy").ifBlank { DEFAULT_DOMAIN_STRATEGY }
        return domainStrategy != DEFAULT_DOMAIN_STRATEGY ||
            routing.keys().asSequence().any { it != "domainStrategy" && it != "rules" }
    }

    private fun parseUiRuleItem(item: JSONObject): CoreRoutingRule {
        val legacyMatchType = item.optString("matchType")
            .let { rawType -> CoreRoutingMatchType.entries.firstOrNull { it.name == rawType } }
        val legacyValues = item.optString("valuesText")

        var domainsText = item.optString("domainsText")
        var ipsText = item.optString("ipsText")
        var portsText = item.optString("portsText")
        var sourcePortsText = item.optString("sourcePortsText")
        var protocolsText = item.optString("protocolsText")

        if (legacyMatchType != null && legacyValues.isNotBlank()) {
            when (legacyMatchType) {
                CoreRoutingMatchType.Domain -> domainsText = legacyValues
                CoreRoutingMatchType.Ip -> ipsText = legacyValues
                CoreRoutingMatchType.Port -> portsText = legacyValues
                CoreRoutingMatchType.SourcePort -> sourcePortsText = legacyValues
                CoreRoutingMatchType.Protocol -> protocolsText = legacyValues
            }
        }

        return CoreRoutingRule(
            id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
            name = item.optString("name"),
            enabled = item.optBoolean("enabled", true),
            domainsText = migrateDomains(domainsText),
            ipsText = migrateIps(ipsText),
            portsText = portsText,
            sourcePortsText = sourcePortsText,
            protocolsText = protocolsText,
            network = item.optString("network")
                .takeIf { it.isNotBlank() }
                ?.let { rawTransport -> CoreRoutingTransport.entries.firstOrNull { it.name == rawTransport } }
                ?: item.optString("transport")
                    .takeIf { it.isNotBlank() }
                    ?.let { rawTransport -> CoreRoutingTransport.entries.firstOrNull { it.name == rawTransport } }
                ?: CoreRoutingTransport.Any,
            outboundTag = item.optString("outboundTag", "proxy").ifBlank { "proxy" },
            opaqueRulesBefore = item.optInt("opaqueRulesBefore", -1).takeIf { it >= 0 },
        )
    }

    private fun parseRule(
        rule: JSONObject,
        index: Int,
        defaultRuleName: (Int) -> String,
    ): CoreRoutingRule? {
        if (hasUnsupportedKeys(rule)) return null
        if (!isSupportedNetwork(rule.opt("network"))) return null

        val domainsText = migrateDomains(extractValues(rule.opt("domain")))
        val ipsText = migrateIps(extractValues(rule.opt("ip")))
        val portsText = extractValues(rule.opt("port"))
        val sourcePortsText = extractValues(rule.opt("sourcePort"))
        val protocolsText = extractValues(rule.opt("protocol"))
        val hasMatchers = listOf(
            domainsText,
            ipsText,
            portsText,
            sourcePortsText,
            protocolsText,
        ).any { it.isNotBlank() }
        if (!hasMatchers) return null

        return CoreRoutingRule(
            name = rule.optString("remarks").ifBlank { defaultRuleName(index + 1) },
            enabled = rule.optBoolean("enabled", true),
            domainsText = domainsText,
            ipsText = ipsText,
            portsText = portsText,
            sourcePortsText = sourcePortsText,
            protocolsText = protocolsText,
            network = CoreRoutingTransport.fromJsonValue(rule.opt("network")),
            outboundTag = rule.optString("outboundTag", "proxy").ifBlank { "proxy" },
        )
    }

    private fun hasUnsupportedKeys(rule: JSONObject): Boolean {
        val keys = rule.keys()
        while (keys.hasNext()) {
            if (keys.next() !in supportedRuleKeys) return true
        }
        return false
    }

    private fun isSupportedNetwork(value: Any?): Boolean {
        val entries = when (value) {
            null, JSONObject.NULL -> return true
            is String -> value.split(',').map(String::trim)
            is JSONArray -> (0 until value.length()).map { index ->
                value.opt(index) as? String ?: return false
            }
            else -> return false
        }
        return entries.all {
            val normalized = it.trim().lowercase()
            normalized.isEmpty() || normalized == "tcp" || normalized == "udp"
        }
    }

    private fun extractValues(value: Any?): String {
        return when (value) {
            is JSONArray -> buildList {
                for (index in 0 until value.length()) {
                    val item = value.optString(index).trim()
                    if (item.isNotEmpty()) add(item)
                }
            }.joinToString("\n")

            is String -> normalizeDelimitedValue(value)
            is Number -> value.toString()
            else -> ""
        }
    }

    private fun buildRule(rule: CoreRoutingRule): JSONObject? {
        if (!rule.enabled) return null
        if (!rule.hasAnyMatchers()) return null

        return JSONObject()
            .put("outboundTag", rule.outboundTag.trim().ifBlank { "proxy" })
            .apply {
                putIfPresent("domain", buildArrayValue(rule.domainsText))
                putIfPresent("ip", buildArrayValue(rule.ipsText))
                putIfPresent("port", buildPortValue(rule.portsText))
                putIfPresent("sourcePort", buildPortValue(rule.sourcePortsText))
                putIfPresent("protocol", buildArrayValue(rule.protocolsText))
                rule.network.jsonValue?.let { put("network", it) }
            }
    }

    private fun buildExchangeRule(
        rule: CoreRoutingRule,
        defaultRuleName: String,
    ): JSONObject? {
        if (!rule.hasAnyMatchers()) return null

        return JSONObject()
            .put("enabled", rule.enabled)
            .put("locked", false)
            .put("outboundTag", rule.outboundTag.trim().ifBlank { "proxy" })
            .put("remarks", rule.name.ifBlank { defaultRuleName })
            .apply {
                putIfPresent("domain", buildArrayValue(rule.domainsText))
                putIfPresent("ip", buildArrayValue(rule.ipsText))
                putIfPresent("port", buildPortValue(rule.portsText))
                putIfPresent("sourcePort", buildPortValue(rule.sourcePortsText))
                putIfPresent("protocol", buildArrayValue(rule.protocolsText))
                rule.network.jsonValue?.let { put("network", it) }
            }
    }

    private fun parseImportedRulesArray(
        rulesArray: JSONArray,
        domainStrategy: String,
        defaultRuleName: (Int) -> String,
    ): ParsedCoreRouting {
        val rules = mutableListOf<CoreRoutingRule>()
        val ruleOrder = mutableListOf<CoreRoutingRuleSlot>()
        var unsupportedRuleCount = 0

        for (index in 0 until rulesArray.length()) {
            val ruleObject = rulesArray.optJSONObject(index) ?: continue
            val parsedRule = parseRule(ruleObject, index, defaultRuleName)
            if (parsedRule != null) {
                rules.add(parsedRule)
                ruleOrder.add(CoreRoutingRuleSlot.Editable(parsedRule.id))
            } else {
                unsupportedRuleCount += 1
                ruleOrder.add(CoreRoutingRuleSlot.Preserved(JSONObject(ruleObject.toString())))
            }
        }

        return ParsedCoreRouting(
            domainStrategy = domainStrategy.ifBlank { DEFAULT_DOMAIN_STRATEGY },
            rules = rules,
            ruleOrder = ruleOrder,
            preservedTopLevel = JSONObject(),
            unsupportedRuleCount = unsupportedRuleCount,
        )
    }

    private fun applyStoredUiState(
        parsed: ParsedCoreRouting,
        storedUiRules: List<CoreRoutingRule>,
    ): ParsedCoreRouting {
        if (storedUiRules.isEmpty()) return parsed

        val unmatchedStoredRules = storedUiRules.toMutableList()
        val restoredIds = mutableMapOf<String, String>()
        val mappedRules = parsed.rules.map { rule ->
            val matchIndex = unmatchedStoredRules.indexOfFirst { stored ->
                stored.enabled == rule.enabled && sameRuleShape(rule, stored)
            }
            if (matchIndex == -1) {
                rule
            } else {
                val stored = unmatchedStoredRules.removeAt(matchIndex)
                restoredIds[rule.id] = stored.id
                rule.copy(
                    id = stored.id,
                    name = stored.name.ifBlank { rule.name },
                    enabled = stored.enabled,
                )
            }
        }
        val mappedIds = mappedRules.mapTo(mutableSetOf()) { it.id }
        val disabledBefore = mutableMapOf<String?, MutableList<CoreRoutingRule>>()
        var nextId: String? = null
        storedUiRules.asReversed().forEach { stored ->
            if (stored.id in mappedIds) {
                nextId = stored.id
            } else if (!stored.enabled) {
                disabledBefore.getOrPut(nextId) { mutableListOf() }.add(stored)
            }
        }
        val rules = buildList {
            mappedRules.forEach { rule ->
                disabledBefore[rule.id]?.asReversed()?.let(::addAll)
                add(rule)
            }
            disabledBefore[null]?.asReversed()?.let(::addAll)
        }
        val order = mutableListOf<CoreRoutingRuleSlot>()
        parsed.ruleOrder.forEach { slot ->
            if (slot is CoreRoutingRuleSlot.Editable) {
                val id = restoredIds[slot.id] ?: slot.id
                disabledBefore[id]?.asReversed()?.forEach { order.add(CoreRoutingRuleSlot.Editable(it.id)) }
                order.add(CoreRoutingRuleSlot.Editable(id))
            } else {
                order.add(slot)
            }
        }
        disabledBefore[null]?.asReversed()?.forEach { order.add(CoreRoutingRuleSlot.Editable(it.id)) }
        if (rules.any { !it.enabled && it.opaqueRulesBefore != null }) {
            val positions = mutableMapOf<String, Int>()
            rules.forEachIndexed { index, rule -> positions[rule.id] = index }
            rules.forEach { rule ->
                if (rule.enabled || rule.id in mappedIds) return@forEach
                val boundary = rule.opaqueRulesBefore?.coerceAtMost(parsed.unsupportedRuleCount) ?: return@forEach
                order.removeAll { it is CoreRoutingRuleSlot.Editable && it.id == rule.id }
                var opaqueCount = 0
                var insertion = order.size
                for ((index, slot) in order.withIndex()) {
                    if (slot is CoreRoutingRuleSlot.Preserved) {
                        if (opaqueCount == boundary) { insertion = index; break }
                        opaqueCount += 1
                    } else if (opaqueCount == boundary &&
                        positions.getValue((slot as CoreRoutingRuleSlot.Editable).id) > positions.getValue(rule.id)
                    ) {
                        insertion = index
                        break
                    }
                }
                order.add(insertion, CoreRoutingRuleSlot.Editable(rule.id))
            }
        }
        return parsed.copy(rules = rules, ruleOrder = order)
    }

    private fun sameRuleShape(first: CoreRoutingRule, second: CoreRoutingRule): Boolean {
        return first.network == second.network &&
            first.outboundTag.trim() == second.outboundTag.trim() &&
            normalizeList(first.domainsText) == normalizeList(second.domainsText) &&
            normalizeList(first.ipsText) == normalizeList(second.ipsText) &&
            normalizePortList(first.portsText) == normalizePortList(second.portsText) &&
            normalizePortList(first.sourcePortsText) == normalizePortList(second.sourcePortsText) &&
            normalizeList(first.protocolsText) == normalizeList(second.protocolsText)
    }

    private fun migrateDomains(values: String): String {
        val normalized = normalizeList(values)
        return when {
            normalized == listOf(LEGACY_RU_DOMAIN_RULE) -> DEFAULT_RU_DOMAIN_VALUES
            normalized == LEGACY_RU_DOMAIN_FALLBACK_VALUES -> DEFAULT_RU_DOMAIN_VALUES
            else -> normalized.joinToString("\n")
        }
    }

    private fun migrateIps(values: String): String {
        val normalized = normalizeList(values)
        return when {
            normalized == listOf(LEGACY_RU_IP_RULE) -> DEFAULT_RU_IP_VALUES
            normalized == listOf(LEGACY_PRIVATE_IP_RULE) -> DEFAULT_PRIVATE_IP_VALUES.trimIndent()
            else -> normalized.joinToString("\n")
        }
    }

    private fun buildArrayValue(values: String): JSONArray? {
        val items = normalizeList(values)
        if (items.isEmpty()) return null
        return JSONArray().apply { items.forEach(::put) }
    }

    private fun buildPortValue(values: String): String? {
        val items = normalizePortList(values)
        return items.takeIf { it.isNotEmpty() }?.joinToString(",")
    }

    private fun JSONObject.putIfPresent(key: String, value: Any?) {
        if (value == null) return
        put(key, value)
    }

    private fun normalizeDelimitedValue(value: String): String {
        return value.split('\n')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .joinToString("\n")
    }

    private fun normalizeList(values: String): List<String> {
        return values.split('\n')
            .map(String::trim)
            .filter(String::isNotEmpty)
    }

    private fun normalizePortList(values: String): List<String> {
        return values
            .split('\n', ',')
            .map(String::trim)
            .filter(String::isNotEmpty)
    }

    private fun matchesDefaultRule(
        rule: CoreRoutingRule,
        domainsText: String = "",
        ipsText: String = "",
        portsText: String = "",
        protocolsText: String = "",
        network: CoreRoutingTransport = CoreRoutingTransport.Any,
        outboundTag: String,
    ): Boolean = rule.enabled &&
        rule.network == network &&
        rule.outboundTag.trim() == outboundTag &&
        normalizeList(rule.domainsText) == normalizeList(domainsText) &&
        normalizeList(rule.ipsText) == normalizeList(ipsText) &&
        normalizePortList(rule.portsText) == normalizePortList(portsText) &&
        normalizePortList(rule.sourcePortsText).isEmpty() &&
        normalizeList(rule.protocolsText) == normalizeList(protocolsText)
}

private fun CoreRoutingRule.hasAnyMatchers(): Boolean {
    return domainsText.isNotBlank() ||
        ipsText.isNotBlank() ||
        portsText.any { !it.isWhitespace() && it != ',' } ||
        sourcePortsText.any { !it.isWhitespace() && it != ',' } ||
        protocolsText.isNotBlank()
}
