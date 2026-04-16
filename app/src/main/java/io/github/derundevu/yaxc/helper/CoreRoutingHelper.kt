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
)

data class ParsedCoreRouting(
    val domainStrategy: String,
    val rules: List<CoreRoutingRule>,
    val preservedRules: List<JSONObject>,
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
        CoreRoutingRule(
            name = "RU IP direct",
            ipsText = DEFAULT_RU_IP_VALUES,
            outboundTag = "direct",
        ),
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
        if (containsDefaultRuIpRule(rules)) return false
        if (rules.size != 4) return false
        return containsDefaultRuDomainRule(rules) &&
            containsDefaultPrivateRule(rules) &&
            containsDefaultBitTorrentRule(rules) &&
            containsDefaultUdp443BlockRule(rules)
    }

    fun encodeUiRules(rules: List<CoreRoutingRule>): String {
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

        val rules = mutableListOf<CoreRoutingRule>()
        val preservedRules = mutableListOf<JSONObject>()
        var unsupportedRuleCount = 0
        for (index in 0 until rulesArray.length()) {
            val ruleObject = rulesArray.optJSONObject(index) ?: continue
            val parsedRule = parseRule(ruleObject, index, defaultRuleName)
            if (parsedRule != null) {
                rules.add(parsedRule)
            } else {
                unsupportedRuleCount += 1
                preservedRules.add(JSONObject(ruleObject.toString()))
            }
        }

        val finalRules = applyStoredUiState(
            parsedRules = migrateLegacyRules(rules),
            storedUiRules = migrateLegacyRules(storedUiRules),
        )
        return ParsedCoreRouting(
            domainStrategy = json.optString("domainStrategy").ifBlank { DEFAULT_DOMAIN_STRATEGY },
            rules = finalRules,
            preservedRules = preservedRules,
            preservedTopLevel = preservedTopLevel,
            unsupportedRuleCount = unsupportedRuleCount,
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
        preservedRules: List<JSONObject> = emptyList(),
        preservedTopLevel: JSONObject = JSONObject(),
    ): String {
        val routing = JSONObject(preservedTopLevel.toString())
        routing.put("domainStrategy", domainStrategy.ifBlank { DEFAULT_DOMAIN_STRATEGY })

        val rulesArray = JSONArray()
        rules.forEach { rule ->
            buildRule(rule)?.let(rulesArray::put)
        }
        preservedRules.forEach { rulesArray.put(JSONObject(it.toString())) }

        if (rulesArray.length() > 0 || routing.length() > 0) {
            routing.put("rules", rulesArray)
        }

        return routing.toString(4)
    }

    fun buildRulesExchangeJson(
        rules: List<CoreRoutingRule>,
        defaultRuleName: String = "Rule",
    ): String {
        val array = JSONArray()
        rules.forEach { rule ->
            buildExchangeRule(rule, defaultRuleName)?.let(array::put)
        }
        return array.toString(4)
    }

    fun isMeaningfulRoutingJson(json: String): Boolean {
        val routing = runCatching { JSONObject(json) }.getOrNull() ?: return false
        if (routing.length() == 0) return false
        if (routing.optJSONArray("rules")?.length() ?: 0 > 0) return true
        val domainStrategy = routing.optString("domainStrategy").ifBlank { DEFAULT_DOMAIN_STRATEGY }
        return routing.length() > 1 || domainStrategy != DEFAULT_DOMAIN_STRATEGY
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
        )
    }

    private fun parseRule(
        rule: JSONObject,
        index: Int,
        defaultRuleName: (Int) -> String,
    ): CoreRoutingRule? {
        if (hasUnsupportedKeys(rule)) return null

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
        val preservedRules = mutableListOf<JSONObject>()
        var unsupportedRuleCount = 0

        for (index in 0 until rulesArray.length()) {
            val ruleObject = rulesArray.optJSONObject(index) ?: continue
            val parsedRule = parseRule(ruleObject, index, defaultRuleName)
            if (parsedRule != null) {
                rules.add(parsedRule)
            } else {
                unsupportedRuleCount += 1
                preservedRules.add(JSONObject(ruleObject.toString()))
            }
        }

        return ParsedCoreRouting(
            domainStrategy = domainStrategy.ifBlank { DEFAULT_DOMAIN_STRATEGY },
            rules = rules,
            preservedRules = preservedRules,
            preservedTopLevel = JSONObject(),
            unsupportedRuleCount = unsupportedRuleCount,
        )
    }

    private fun applyStoredUiState(
        parsedRules: List<CoreRoutingRule>,
        storedUiRules: List<CoreRoutingRule>,
    ): List<CoreRoutingRule> {
        if (storedUiRules.isEmpty()) return parsedRules

        val unmatchedStoredRules = storedUiRules.toMutableList()
        val mappedRules = parsedRules.map { rule ->
            val matchIndex = unmatchedStoredRules.indexOfFirst { stored -> sameRuleShape(rule, stored) }
            if (matchIndex == -1) {
                rule
            } else {
                val stored = unmatchedStoredRules.removeAt(matchIndex)
                rule.copy(
                    id = stored.id,
                    name = stored.name.ifBlank { rule.name },
                    enabled = stored.enabled,
                )
            }
        }
        val disabledOnlyRules = unmatchedStoredRules.filterNot { it.enabled }
        return mappedRules + disabledOnlyRules
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

    private fun containsDefaultRuIpRule(rules: List<CoreRoutingRule>): Boolean {
        return rules.any { rule ->
            normalizeList(rule.ipsText) == listOf(DEFAULT_RU_IP_VALUES) &&
                rule.outboundTag.trim() == "direct"
        }
    }

    private fun containsDefaultRuDomainRule(rules: List<CoreRoutingRule>): Boolean {
        return rules.any { rule ->
            normalizeList(rule.domainsText) == listOf(DEFAULT_RU_DOMAIN_VALUES) &&
                rule.outboundTag.trim() == "direct"
        }
    }

    private fun containsDefaultPrivateRule(rules: List<CoreRoutingRule>): Boolean {
        return rules.any { rule ->
            normalizeList(rule.ipsText) == normalizeList(DEFAULT_PRIVATE_IP_VALUES) &&
                rule.outboundTag.trim() == "block"
        }
    }

    private fun containsDefaultBitTorrentRule(rules: List<CoreRoutingRule>): Boolean {
        return rules.any { rule ->
            normalizeList(rule.protocolsText) == listOf("bittorrent") &&
                rule.outboundTag.trim() == "direct"
        }
    }

    private fun containsDefaultUdp443BlockRule(rules: List<CoreRoutingRule>): Boolean {
        return rules.any { rule ->
            normalizePortList(rule.portsText) == listOf("443") &&
                rule.network == CoreRoutingTransport.Udp &&
                rule.outboundTag.trim() == "block"
        }
    }
}

private fun CoreRoutingRule.hasAnyMatchers(): Boolean {
    return domainsText.isNotBlank() ||
        ipsText.isNotBlank() ||
        portsText.isNotBlank() ||
        sourcePortsText.isNotBlank() ||
        protocolsText.isNotBlank()
}
