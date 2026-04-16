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
            return entries.firstOrNull { it.jsonValue == value } ?: Any
        }
    }
}

data class CoreRoutingRule(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val enabled: Boolean = true,
    val matchType: CoreRoutingMatchType = CoreRoutingMatchType.Domain,
    val transport: CoreRoutingTransport = CoreRoutingTransport.Any,
    val outboundTag: String = "proxy",
    val valuesText: String = "",
)

data class ParsedCoreRouting(
    val domainStrategy: String,
    val rules: List<CoreRoutingRule>,
    val preservedRules: List<JSONObject>,
    val preservedTopLevel: JSONObject,
    val unsupportedRuleCount: Int,
)

object CoreRoutingHelper {

    private const val DEFAULT_DOMAIN_STRATEGY = "IPIfNonMatch"

    fun defaultRule(
        index: Int,
        name: String = "Rule $index",
    ): CoreRoutingRule {
        return CoreRoutingRule(name = name)
    }

    fun parseUiRules(raw: String): List<CoreRoutingRule> {
        val array = runCatching { JSONArray(raw) }.getOrElse { return emptyList() }
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                add(
                    CoreRoutingRule(
                        id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                        name = item.optString("name"),
                        enabled = item.optBoolean("enabled", true),
                        matchType = item.optString("matchType")
                            .let { rawType -> CoreRoutingMatchType.entries.firstOrNull { it.name == rawType } }
                            ?: CoreRoutingMatchType.Domain,
                        transport = item.optString("transport")
                            .let { rawTransport -> CoreRoutingTransport.entries.firstOrNull { it.name == rawTransport } }
                            ?: CoreRoutingTransport.Any,
                        outboundTag = item.optString("outboundTag", "proxy").ifBlank { "proxy" },
                        valuesText = item.optString("valuesText"),
                    )
                )
            }
        }
    }

    fun encodeUiRules(rules: List<CoreRoutingRule>): String {
        val array = JSONArray()
        rules.forEach { rule ->
            array.put(
                JSONObject()
                    .put("id", rule.id)
                    .put("name", rule.name)
                    .put("enabled", rule.enabled)
                    .put("matchType", rule.matchType.name)
                    .put("transport", rule.transport.name)
                    .put("outboundTag", rule.outboundTag)
                    .put("valuesText", rule.valuesText)
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

        val finalRules = applyStoredUiState(rules, storedUiRules)
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

    private fun parseRule(
        rule: JSONObject,
        index: Int,
        defaultRuleName: (Int) -> String,
    ): CoreRoutingRule? {
        val supportedKeys = CoreRoutingMatchType.entries.filter { entry ->
            when (val value = rule.opt(entry.jsonKey)) {
                null, JSONObject.NULL -> false
                is JSONArray -> value.length() > 0
                is String -> value.isNotBlank()
                else -> false
            }
        }
        if (supportedKeys.size != 1) return null

        val matchType = supportedKeys.single()
        val valuesText = extractValues(rule.opt(matchType.jsonKey))
        if (valuesText.isBlank()) return null

        return CoreRoutingRule(
            name = rule.optString("remarks").ifBlank { defaultRuleName(index + 1) },
            enabled = rule.optBoolean("enabled", true),
            matchType = matchType,
            transport = CoreRoutingTransport.fromJsonValue(rule.optString("network").ifBlank { null }),
            outboundTag = rule.optString("outboundTag", "proxy").ifBlank { "proxy" },
            valuesText = valuesText,
        )
    }

    private fun extractValues(value: Any?): String {
        return when (value) {
            is JSONArray -> buildList {
                for (index in 0 until value.length()) {
                    val item = value.optString(index).trim()
                    if (item.isNotEmpty()) add(item)
                }
            }.joinToString("\n")
            is String -> value.trim()
            else -> ""
        }
    }

    private fun buildRule(rule: CoreRoutingRule): JSONObject? {
        if (!rule.enabled) return null
        val values = rule.valuesText
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toList()
        if (values.isEmpty()) return null

        val array = JSONArray()
        values.forEach(array::put)

        return JSONObject()
            .put(rule.matchType.jsonKey, array)
            .put("outboundTag", rule.outboundTag.trim().ifBlank { "proxy" })
            .apply {
                rule.transport.jsonValue?.let { put("network", it) }
            }
    }

    private fun buildExchangeRule(
        rule: CoreRoutingRule,
        defaultRuleName: String,
    ): JSONObject? {
        val values = rule.valuesText
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toList()
        if (values.isEmpty()) return null

        val array = JSONArray()
        values.forEach(array::put)

        return JSONObject()
            .put("enabled", rule.enabled)
            .put("locked", false)
            .put("outboundTag", rule.outboundTag.trim().ifBlank { "proxy" })
            .put("remarks", rule.name.ifBlank { defaultRuleName })
            .apply {
                put(rule.matchType.jsonKey, array)
                rule.transport.jsonValue?.let { put("network", it) }
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
        return first.matchType == second.matchType &&
                first.transport == second.transport &&
                first.outboundTag.trim() == second.outboundTag.trim() &&
                normalizeValues(first.valuesText) == normalizeValues(second.valuesText)
    }

    private fun normalizeValues(values: String): List<String> {
        return values.lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toList()
    }
}
