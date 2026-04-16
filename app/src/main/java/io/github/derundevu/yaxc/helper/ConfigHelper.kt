package io.github.derundevu.yaxc.helper

import io.github.derundevu.yaxc.Settings
import io.github.derundevu.yaxc.database.Config
import org.json.JSONArray
import org.json.JSONObject

class ConfigHelper(
    private val settings: Settings,
    config: Config,
    base: String,
) {
    private val base: JSONObject = JsonHelper.makeObject(base)
    private val userRoutingConfig = config.routing
    private val userRoutingMode = config.routingMode

    init {
        applyManagedRuntimeConfig()
        process("log", config.log, config.logMode)
        process("dns", config.dns, config.dnsMode)
        process("inbounds", config.inbounds, config.inboundsMode)
        process("outbounds", config.outbounds, config.outboundsMode)
        process("routing", config.routing, config.routingMode)
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

    private fun applyManagedRuntimeConfig() {
        base.put("log", runtimeLog())
        base.put("dns", runtimeDns())
        base.put("inbounds", runtimeInbounds())
        base.put("routing", runtimeRouting())
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

    private fun runtimeRouting(): JSONObject {
        val rules = JSONArray()

        if (settings.transparentProxy) {
            val proxyDns = JSONObject()
                .put("network", "udp")
                .put("port", 53)
                .put("inboundTag", JSONArray().put("all-in"))
                .put("outboundTag", "dns-out")
            rules.put(proxyDns)
        } else {
            val proxyDns = JSONObject()
                .put("ip", managedDnsServers())
                .put("port", 53)
                .put("outboundTag", "proxy")
            rules.put(proxyDns)
        }

        if (!hasCustomPrivateRule()) {
            val directPrivate = JSONObject()
                .put("ip", privateAddressRanges())
                .put("outboundTag", "direct")
            rules.put(directPrivate)
        }

        return JSONObject()
            .put("domainStrategy", "IPIfNonMatch")
            .put("rules", rules)
    }

    private fun hasCustomPrivateRule(): Boolean {
        if (userRoutingMode == Config.Mode.Disable) return false

        val rules = runCatching {
            JsonHelper.makeObject(userRoutingConfig).optJSONArray("rules")
        }.getOrNull() ?: return false

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
