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

        val directPrivate = JSONObject()
            .put("ip", JSONArray().put("geoip:private"))
            .put("outboundTag", "direct")
        rules.put(directPrivate)

        return JSONObject()
            .put("domainStrategy", "IPIfNonMatch")
            .put("rules", rules)
    }
}
