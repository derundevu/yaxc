package io.github.derundevu.yaxc.helper

import org.json.JSONArray
import org.json.JSONObject

/** Immutable JSON snapshots keep previews independent from later subscription updates. */
data class CoreRoutingImportProfile(val id: Long, val name: String, val config: String)
data class CoreRoutingImportReference(val tag: String, val suggestedTarget: String?)
data class CoreRoutingImportRule(
    val index: Int,
    val json: String,
    val outbound: CoreRoutingImportReference?,
    val inbounds: List<String>,
    val problem: String?,
)
data class CoreRoutingImportPreview(
    val profile: CoreRoutingImportProfile,
    val domainStrategy: String?,
    val rules: List<CoreRoutingImportRule>,
)
data class CoreRoutingImportResult(val routing: String, val added: Int, val skipped: Int)

object CoreRoutingImportHelper {
    private val proxyProtocols = setOf("vless", "vmess", "trojan", "shadowsocks", "socks", "http", "wireguard", "hysteria", "masque")

    fun preview(profile: CoreRoutingImportProfile): CoreRoutingImportPreview {
        val config = JSONObject(profile.config)
        val routing = config.optJSONObject("routing") ?: JSONObject()
        val outbounds = config.optJSONArray("outbounds") ?: JSONArray()
        val byTag = (0 until outbounds.length()).mapNotNull { outbounds.optJSONObject(it) }
            .filter { it.optString("tag").isNotBlank() }.groupBy { it.getString("tag") }
        val inbounds = config.optJSONArray("inbounds") ?: JSONArray()
        val inboundCounts = (0 until inbounds.length()).mapNotNull { inbounds.optJSONObject(it)?.optString("tag") }
            .filter { it.isNotBlank() }.groupingBy { it }.eachCount()
        val proxyCount = byTag.values.flatten().count { it.optString("protocol").lowercase() in proxyProtocols }
        val rules = routing.optJSONArray("rules") ?: JSONArray()
        return CoreRoutingImportPreview(profile, routing.opt("domainStrategy")?.let {
            require(it is String && it in setOf("AsIs", "IPIfNonMatch", "IPOnDemand")) { "Unsupported domainStrategy" }
            it
        }, (0 until rules.length()).map { index ->
            val rule = rules.optJSONObject(index)
            var problem: String? = null
            var reference: CoreRoutingImportReference? = null
            val inboundTags = mutableListOf<String>()
            fun reject(reason: String) { if (problem == null) problem = reason }
            if (rule == null) {
                reject("Rule is not an object")
            } else {
                val outboundTag = rule.optString("outboundTag")
                if (rule.has("balancerTag")) {
                    reject("Balancer selection, selectors and fallback dependencies cannot be transferred to Core Routing")
                } else if (outboundTag.isBlank()) {
                    reject("Rule has no outbound destination")
                } else {
                    val matches = byTag[outboundTag].orEmpty()
                    if (matches.size != 1) {
                        reject("Outbound '$outboundTag' is missing or has duplicate definitions")
                    } else {
                        val outbound = matches.single()
                        val protocol = outbound.optString("protocol").lowercase()
                        val dependency = outbound.has("proxySettings") ||
                            outbound.optJSONObject("streamSettings")?.optJSONObject("sockopt")?.has("dialerProxy") == true ||
                            outbound.has("sendThrough")
                        if (dependency) reject("Outbound '$outboundTag' has chaining or source-binding dependencies that cannot be transferred")
                        val target = when (protocol) {
                            "freedom" -> if (outbound.optJSONObject("settings")?.has("redirect") == true) null else "direct"
                            "blackhole" -> "block"
                            in proxyProtocols -> if (proxyCount == 1) "proxy" else null
                            else -> { reject("Outbound '$outboundTag' uses unsupported protocol '$protocol'"); null }
                        }
                        reference = CoreRoutingImportReference(outboundTag, target)
                    }
                }
                if (rule.has("inboundTag")) {
                    val tags = rule.optJSONArray("inboundTag")
                    if (tags == null || tags.length() == 0) reject("inboundTag must be a nonempty array")
                    else for (i in 0 until tags.length()) {
                        val tag = tags.opt(i) as? String
                        if (tag == null || inboundCounts[tag] != 1) reject("Inbound '${tag.orEmpty()}' is missing or ambiguous in the source profile")
                        else inboundTags += tag
                    }
                }
                // These fields name other config objects, not portable match conditions.
                if (rule.has("outboundTag") && rule.has("balancerTag")) reject("Rule has conflicting destinations")
                if (rule.has("vlessRoute")) reject("vlessRoute dependencies cannot be transferred")
            }
            CoreRoutingImportRule(index, rule?.toString(2) ?: rules.opt(index).toString(), reference, inboundTags, problem)
        })
    }

    fun importIntoDraft(
        preview: CoreRoutingImportPreview,
        currentRouting: String,
        selected: Set<Int>,
        outboundMappings: Map<String, String>,
        inboundMappings: Map<String, String>,
        runtimeInboundTag: String,
        replace: Boolean,
        importDomainStrategy: Boolean,
    ): CoreRoutingImportResult {
        require(selected.all { index -> preview.rules.any { it.index == index } }) { "Unknown selected rule" }
        val current = JSONObject(currentRouting)
        val imported = JSONArray()
        preview.rules.filter { it.index in selected }.forEach { item ->
            require(item.problem == null) { item.problem.orEmpty() }
            val rule = JSONObject(item.json)
            val outbound = requireNotNull(item.outbound) { "Missing outbound destination" }
            val target = outboundMappings[outbound.tag] ?: outbound.suggestedTarget
            require(target in setOf("proxy", "direct", "block")) { "Map outbound '${outbound.tag}' before importing" }
            rule.put("outboundTag", target)
            if (item.inbounds.isNotEmpty()) {
                val mapped = item.inbounds.map { tag ->
                    require(inboundMappings[tag] == runtimeInboundTag) { "Map inbound '$tag' to the current runtime listener before importing" }
                    runtimeInboundTag
                }.distinct()
                rule.put("inboundTag", JSONArray(mapped))
            }
            imported.put(rule)
        }
        val existing = if (replace) JSONArray() else current.optJSONArray("rules") ?: JSONArray()
        val before = JsonHelper.distinctRules(existing).length()
        val merged = JsonHelper.distinctRules(existing, imported)
        current.put("rules", merged)
        if (importDomainStrategy) {
            current.put("domainStrategy", requireNotNull(preview.domainStrategy) { "Source has no domainStrategy" })
        }
        val added = merged.length() - before
        return CoreRoutingImportResult(current.toString(4), added, imported.length() - added)
    }
}
