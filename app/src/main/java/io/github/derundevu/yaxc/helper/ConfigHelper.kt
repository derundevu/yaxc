package io.github.derundevu.yaxc.helper

import io.github.derundevu.yaxc.Settings
import io.github.derundevu.yaxc.database.Config
import org.json.JSONArray
import org.json.JSONObject

class ConfigHelper(
    private val settings: Settings,
    config: Config,
    base: String,
    private val target: Target = Target.Profile,
) {
    /** LiveRoutingBase is compiler input: its logical Proxy destination is resolved by LiveRoutingHelper. */
    enum class Target { Profile, LiveRoutingBase }

    private val base: JSONObject = JsonHelper.makeObject(base)

    init {
        process("log", config.log, config.logMode)
        process("dns", config.dns, config.dnsMode)
        process("inbounds", config.inbounds, config.inboundsMode)
        process("outbounds", config.outbounds, config.outboundsMode)
        applyManagedRuntimeConfig(config)
        if (settings.tproxyHotspot || settings.tproxyTethering) sharedInbounds()
    }

    override fun toString(): String {
        return base.toString(4)
    }

    private fun process(key: String, config: String, mode: Config.Mode) {
        if (mode == Config.Mode.Disable) return
        if (arrayOf("inbounds", "outbounds").contains(key)) {
            processArray(key, config, mode)
            return
        }
        processObject(key, config, mode)
    }

    private fun processObject(key: String, config: String, mode: Config.Mode) {
        val oldValue = JsonHelper.getObject(base, key)
        val newValue = JsonHelper.makeObject(config)
        val final = if (mode == Config.Mode.Replace) newValue
        else JsonHelper.mergeObjects(oldValue, newValue)
        base.put(key, final)
    }

    private fun processArray(key: String, config: String, mode: Config.Mode) {
        val oldValue = JsonHelper.getArray(base, key)
        val newValue = JsonHelper.makeArray(config)
        val final = if (mode == Config.Mode.Replace) newValue
        else JsonHelper.mergeArrays(oldValue, newValue, "protocol")
        base.put(key, final)
    }

    private fun sharedInbounds() {
        val key = "inbounds"
        val inbounds = JsonHelper.getArray(base, key)
        for (i in 0 until inbounds.length()) {
            val inbound = inbounds[i]
            if (inbound is JSONObject && inbound.has("listen")) {
                inbound.remove("listen")
                inbounds.put(i, inbound)
            }
        }
        base.put(key, inbounds)
    }

    private fun applyManagedRuntimeConfig(config: Config) {
        base.put("log", JsonHelper.mergeObjects(JsonHelper.getObject(base, "log"), runtimeLog()))
        val outbounds = ensureManagedOutbounds(JsonHelper.getArray(base, "outbounds"))
        base.put("outbounds", outbounds)
        base.put("dns", mergeRuntimeDns(JsonHelper.getObject(base, "dns")))
        base.put("inbounds", runtimeInbounds())
        base.put("routing", mergeRuntimeRouting(combineRouting(config, outbounds), outbounds))
    }

    private fun combineRouting(config: Config, outbounds: JSONArray): JSONObject {
        val profile = JsonHelper.getObject(base, "routing")
        normalizeRuleOutboundTags(JsonHelper.getArray(profile, "rules"), outbounds, semantic = false)
        if (config.routingMode == Config.Mode.Disable) return profile
        val core = JsonHelper.makeObject(config.routing)
        normalizeRuleOutboundTags(JsonHelper.getArray(core, "rules"), outbounds, semantic = true)
        return if (config.routingMode == Config.Mode.Replace) core else JsonHelper.mergeRouting(profile, core)
    }

    private fun runtimeLog(): JSONObject {
        return JSONObject().put("loglevel", "warning")
    }

    private fun managedDnsServers(): JSONArray {
        return JSONArray()
            .put(settings.primaryDns)
            .put(settings.secondaryDns)
            .apply {
                if (settings.enableIpV6) {
                    put(settings.primaryDnsV6)
                    put(settings.secondaryDnsV6)
                }
            }
    }

    private fun runtimeDns(): JSONObject {
        return JSONObject().put("servers", managedDnsServers())
    }

    private fun mergeRuntimeDns(currentDns: JSONObject): JSONObject {
        return if (currentDns.length() == 0) {
            runtimeDns()
        } else {
            JsonHelper.mergeObjects(currentDns, runtimeDns())
        }
    }

    private fun runtimeInbounds(): JSONArray {
        val inbounds = JSONArray()

        val sniffing = JSONObject().apply {
            put("enabled", true)
            put("destOverride", JSONArray().put("http").put("tls").put("quic"))
        }

        if (settings.transparentProxy) {
            val tproxySockopt = JSONObject().put("tproxy", "tproxy")
            val tproxyStreamSettings = JSONObject().put("sockopt", tproxySockopt)
            val tproxySettings = JSONObject()
                .put("network", "tcp,udp")
                .put("followRedirect", true)

            inbounds.put(
                JSONObject()
                    .put("listen", settings.tproxyAddress)
                    .put("port", settings.tproxyPort)
                    .put("protocol", "dokodemo-door")
                    .put("settings", tproxySettings)
                    .put("sniffing", sniffing)
                    .put("streamSettings", tproxyStreamSettings)
                    .put("tag", "all-in")
            )
            return inbounds
        }

        val socksSettings = JSONObject().put("udp", true)
        val socksUsername = settings.socksUsername.trim()
        val socksPassword = settings.socksPassword.trim()

        if (socksUsername.isNotEmpty() && socksPassword.isNotEmpty()) {
            val account = JSONObject()
                .put("user", socksUsername)
                .put("pass", socksPassword)
            socksSettings.put("auth", "password")
            socksSettings.put("accounts", JSONArray().put(account))
        } else {
            socksSettings.put("auth", "noauth")
        }

        inbounds.put(
            JSONObject()
                .put("listen", settings.socksAddress)
                .put("port", settings.socksPort.toInt())
                .put("protocol", "socks")
                .put("settings", socksSettings)
                .put("sniffing", sniffing)
                .put("tag", "socks")
        )

        return inbounds
    }

    private fun mergeRuntimeRouting(
        currentRouting: JSONObject,
        outbounds: JSONArray,
    ): JSONObject {
        val routing = JSONObject(currentRouting.toString())
        val rules = removeManagedAntifilterRules(JsonHelper.getArray(routing, "rules"))
        val needsPrivateRule = !hasPrivateRule(rules)

        antifilterProxyRule(outbounds)?.let(rules::put)
        rules.put(runtimeProxyDnsRule(outbounds))
        if (needsPrivateRule) {
            rules.put(runtimeDirectPrivateRule(outbounds))
        }

        routing.put("rules", rules)
        if (routing.optString("domainStrategy").isBlank()) {
            routing.put("domainStrategy", "IPIfNonMatch")
        }
        return routing
    }

    private fun removeManagedAntifilterRules(source: JSONArray): JSONArray {
        return JSONArray().apply {
            for (index in 0 until source.length()) {
                val rule = source.optJSONObject(index)
                val ruleTag = rule?.optString("ruleTag")
                if (ruleTag == AntifilterHelper.ROUTING_RULE_TAG ||
                    ruleTag == AntifilterHelper.LEGACY_DIRECT_ROUTING_RULE_TAG
                ) {
                    continue
                }
                put(source.get(index))
            }
        }
    }

    private fun antifilterProxyRule(outbounds: JSONArray): JSONObject? {
        if (!settings.antifilterEnabled) return null
        val cidrs = runCatching { AntifilterHelper.readRules(settings.antifilterFile()) }
            .getOrDefault(emptyList())
        if (cidrs.isEmpty()) return null

        return JSONObject()
            .put("ruleTag", AntifilterHelper.ROUTING_RULE_TAG)
            .put("ip", JSONArray().apply { cidrs.forEach(::put) })
            .put("outboundTag", resolveRoutingOutboundTag("proxy", outbounds))
    }

    private fun runtimeProxyDnsRule(outbounds: JSONArray): JSONObject {
        return if (settings.transparentProxy) {
            JSONObject()
                .put("network", "udp")
                .put("port", 53)
                .put("inboundTag", JSONArray().put("all-in"))
                .put("outboundTag", resolveRoutingOutboundTag("dns-out", outbounds))
        } else {
            JSONObject()
                .put("ip", managedDnsServers())
                .put("port", 53)
                .put("outboundTag", resolveRoutingOutboundTag("proxy", outbounds))
        }
    }

    private fun runtimeDirectPrivateRule(outbounds: JSONArray): JSONObject {
        return JSONObject()
            .put("ip", privateAddressRanges())
            .put("outboundTag", resolveRoutingOutboundTag("direct", outbounds))
    }

    private fun ensureManagedOutbounds(currentOutbounds: JSONArray): JSONArray {
        val outbounds = JSONArray(currentOutbounds.toString())
        if (target == Target.Profile && findRoleTag(outbounds, "proxy") == null) {
            for (index in 0 until outbounds.length()) {
                val outbound = outbounds.optJSONObject(index) ?: continue
                val role = outboundRole(outbound)
                if (outbound.optString("tag").isNotBlank() || (role != "proxy" && role != "direct")) continue
                outbound.put("tag", unusedTag(outbounds, "proxy"))
                break
            }
        }
        fun ensure(role: String, protocol: String) {
            if (findRoleTag(outbounds, role) == null) {
                outbounds.put(JSONObject().put("protocol", protocol).put("tag", unusedTag(outbounds, role)))
            }
        }
        // Empty profiles use the managed direct outbound as their default forwarding destination.
        ensure("direct", "freedom")
        ensure("block", "blackhole")
        if (settings.transparentProxy) ensure("dns-out", "dns")
        return outbounds
    }

    private fun unusedTag(outbounds: JSONArray, preferred: String): String {
        if (findExactOutboundTag(outbounds, preferred) == null) return preferred
        val prefix = "yaxc-$preferred"
        var candidate = prefix
        var suffix = 2
        while (findExactOutboundTag(outbounds, candidate) != null) candidate = "$prefix-${suffix++}"
        return candidate
    }

    /** Routing roles are not tag names: a VLESS outbound named "block" is still a proxy. */
    private fun outboundRole(outbound: JSONObject): String? {
        return when (val protocol = outbound.optString("protocol").trim().lowercase()) {
            "freedom" -> when {
                outbound.optJSONObject("settings")?.optString("redirect")?.isNotBlank() == true ||
                    outbound.has("proxySettings") ||
                    outbound.optJSONObject("streamSettings")?.optJSONObject("sockopt")?.has("dialerProxy") == true -> "proxy"
                else -> "direct"
            }
            "blackhole" -> "block"
            "dns" -> "dns-out"
            "" -> null
            else -> if (protocol == "loopback") null else "proxy"
        }
    }

    private fun findRoleTag(outbounds: JSONArray, role: String): String? {
        var first: String? = null
        var directFallback: String? = null
        for (index in 0 until outbounds.length()) {
            val outbound = outbounds.optJSONObject(index) ?: continue
            val actualRole = outboundRole(outbound)
            if (actualRole != role && !(role == "proxy" && actualRole == "direct")) continue
            val tag = outbound.optString("tag").trim()
            if (tag.isEmpty()) continue
            // The Live compiler reserves this tag for its selected default group, regardless of protocol.
            if (target == Target.LiveRoutingBase && role != "proxy" && tag.equals("proxy", ignoreCase = true)) continue
            var occurrences = 0
            for (other in 0 until outbounds.length()) {
                if (outbounds.optJSONObject(other)?.optString("tag")?.trim() == tag) occurrences += 1
            }
            if (occurrences != 1) continue
            if (role == "proxy" && actualRole == "direct") {
                if (directFallback == null || tag == "proxy") directFallback = tag
                continue
            }
            if (tag == role) return tag
            if (first == null) first = tag
        }
        return first ?: directFallback
    }

    private fun normalizeRuleOutboundTags(rules: JSONArray, outbounds: JSONArray, semantic: Boolean) {
        for (index in 0 until rules.length()) {
            val rule = rules.optJSONObject(index) ?: continue
            val tag = rule.optString("outboundTag").trim()
            if (tag.isNotEmpty()) rule.put("outboundTag", resolveRoutingOutboundTag(tag, outbounds, semantic))
        }
    }

    private fun resolveRoutingOutboundTag(
        outboundTag: String,
        outbounds: JSONArray,
        semantic: Boolean = true,
    ): String {
        val normalized = outboundTag.trim()
        // Profile tags are literal. Core Routing and managed aliases denote protocol-checked roles.
        if (!semantic) findExactOutboundTag(outbounds, normalized)?.let { return it }
        val role = normalized.lowercase()
        if (target == Target.LiveRoutingBase && role == "proxy") return "proxy"
        if (role != "proxy" && role != "direct" && role != "block" && role != "dns-out") return normalized
        return requireNotNull(findRoleTag(outbounds, role)) {
            "No unambiguous outbound is available for Core Routing role '$role'"
        }
    }

    private fun findExactOutboundTag(
        outbounds: JSONArray,
        tag: String,
    ): String? {
        for (index in 0 until outbounds.length()) {
            val outbound = outbounds.optJSONObject(index) ?: continue
            val outboundTag = outbound.optString("tag").trim()
            if (outboundTag == tag) return outboundTag
        }
        return null
    }

    private fun hasPrivateRule(rules: JSONArray): Boolean {
        val privateRanges = privateAddressRangesList().map(String::lowercase).toSet()
        for (index in 0 until rules.length()) {
            val rule = rules.optJSONObject(index) ?: continue
            val values = extractIpRuleValues(rule.opt("ip"))
            if (values.isEmpty()) continue

            if ("geoip:private" in values) return true
            if (privateRanges.all(values::contains)) return true
        }
        return false
    }

    private fun extractIpRuleValues(value: Any?): Set<String> {
        return when (value) {
            is JSONArray -> buildSet {
                for (index in 0 until value.length()) {
                    val item = value.optString(index).trim().lowercase()
                    if (item.isNotEmpty()) add(item)
                }
            }

            is String -> value.split(',')
                .map(String::trim)
                .filter(String::isNotEmpty)
                .map(String::lowercase)
                .toSet()

            else -> emptySet()
        }
    }

    private fun privateAddressRanges(): JSONArray {
        return JSONArray().apply {
            privateAddressRangesList().forEach(::put)
        }
    }

    private fun privateAddressRangesList(): List<String> {
        return listOf(
            "10.0.0.0/8",
            "100.64.0.0/10",
            "127.0.0.0/8",
            "169.254.0.0/16",
            "172.16.0.0/12",
            "192.168.0.0/16",
            "::1/128",
            "fc00::/7",
            "fe80::/10",
        )
    }
}
