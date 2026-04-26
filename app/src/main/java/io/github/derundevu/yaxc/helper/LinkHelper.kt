package io.github.derundevu.yaxc.helper

import XrayCore.XrayCore
import android.util.Base64
import io.github.derundevu.yaxc.R
import io.github.derundevu.yaxc.Settings
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

class LinkHelper(
    private val settings: Settings,
    link: String
) {

    private data class ParsedConfigResult(
        val config: JSONObject,
        val remark: String? = null,
    )

    private data class ParsedOutboundResult(
        val success: Boolean,
        val outbound: JSONObject?,
    )

    private val success: Boolean
    private val importedConfig: JSONObject?
    private val outbound: JSONObject?
    private var remark: String = defaultRemark()

    init {
        val inlineConfig = parseInlineConfig(link)
        if (inlineConfig != null) {
            success = true
            importedConfig = inlineConfig.config
            outbound = inlineConfig.config.optJSONArray("outbounds")
                ?.optJSONObject(0)
                ?.let(::sanitizeObject)
            inlineConfig.remark?.takeIf { it.isNotBlank() }?.let { remark = it }
        } else {
            val uri = runCatching { URI(link) }.getOrNull()
            val parsedOutbound = uri?.let(::parseKnownOutbound)
            if (parsedOutbound != null) {
                success = true
                importedConfig = null
                outbound = parsedOutbound
                remark = remark(uri, defaultRemark())
            } else {
                val parsedFromXray = parseOutboundFromXray(link)
                val decodedInlineConfig = parseBase64InlineConfig(link)
                if (parsedFromXray.success && parsedFromXray.outbound != null) {
                    success = true
                    importedConfig = null
                    outbound = parsedFromXray.outbound
                } else if (decodedInlineConfig != null) {
                    success = true
                    importedConfig = decodedInlineConfig.config
                    outbound = decodedInlineConfig.config.optJSONArray("outbounds")
                        ?.optJSONObject(0)
                        ?.let(::sanitizeObject)
                    decodedInlineConfig.remark?.takeIf { it.isNotBlank() }?.let { remark = it }
                } else {
                    success = parsedFromXray.success
                    importedConfig = null
                    outbound = parsedFromXray.outbound
                }
            }
        }
    }

    companion object {
        fun remark(uri: URI, default: String = ""): String {
            val name = uri.fragment ?: ""
            return name.ifEmpty { default }
        }

        fun tryDecodeBase64(value: String): String {
            return runCatching {
                val byteArray = Base64.decode(value, Base64.DEFAULT)
                String(byteArray)
            }.getOrNull() ?: value
        }
    }

    fun isValid(): Boolean {
        return success && (importedConfig != null || outbound != null)
    }

    fun json(): String {
        return (importedConfig ?: config()).toString(2) + "\n"
    }

    fun remark(): String {
        return remark
    }

    private fun log(): JSONObject {
        val log = JSONObject()
        log.put("loglevel", "warning")
        return log
    }

    private fun dns(): JSONObject {
        val dns = JSONObject()
        val servers = JSONArray()
        servers.put(settings.primaryDns)
        servers.put(settings.secondaryDns)
        dns.put("servers", servers)
        return dns
    }

    private fun inbounds(): JSONArray {
        val inbounds = JSONArray()

        val sniffing = JSONObject()
        sniffing.put("enabled", true)
        val sniffingDestOverride = JSONArray()
        sniffingDestOverride.put("http")
        sniffingDestOverride.put("tls")
        sniffingDestOverride.put("quic")
        sniffing.put("destOverride", sniffingDestOverride)

        val tproxy = JSONObject()
        tproxy.put("listen", settings.tproxyAddress)
        tproxy.put("port", settings.tproxyPort)
        tproxy.put("protocol", "dokodemo-door")

        val tproxySettings = JSONObject()
        tproxySettings.put("network", "tcp,udp")
        tproxySettings.put("followRedirect", true)

        val tproxySockopt = JSONObject()
        tproxySockopt.put("tproxy", "tproxy")

        val tproxyStreamSettings = JSONObject()
        tproxyStreamSettings.put("sockopt", tproxySockopt)

        tproxy.put("settings", tproxySettings)
        tproxy.put("sniffing", sniffing)
        tproxy.put("streamSettings", tproxyStreamSettings)
        tproxy.put("tag", "all-in")

        val socks = JSONObject()
        socks.put("listen", settings.socksAddress)
        socks.put("port", settings.socksPort.toInt())
        socks.put("protocol", "socks")

        val socksSettings = JSONObject()
        socksSettings.put("udp", true)
        if (
            settings.socksUsername.trim().isNotEmpty() &&
            settings.socksPassword.trim().isNotEmpty()
        ) {
            val account = JSONObject()
            account.put("user", settings.socksUsername)
            account.put("pass", settings.socksPassword)
            val accounts = JSONArray()
            accounts.put(account)

            socksSettings.put("auth", "password")
            socksSettings.put("accounts", accounts)
        }

        socks.put("settings", socksSettings)
        socks.put("sniffing", sniffing)
        socks.put("tag", "socks")

        if (settings.transparentProxy) inbounds.put(tproxy)
        else inbounds.put(socks)

        return inbounds
    }

    private fun outbounds(): JSONArray {
        val outbounds = JSONArray()

        val proxy = sanitizeObject(JSONObject(outbound!!.toString()))
        if (proxy.has("sendThrough")) {
            remark = proxy.optString("sendThrough", defaultRemark())
            proxy.remove("sendThrough")
        }
        proxy.put("tag", "proxy")

        val direct = JSONObject()
        direct.put("protocol", "freedom")
        direct.put("tag", "direct")

        val block = JSONObject()
        block.put("protocol", "blackhole")
        block.put("tag", "block")

        val dns = JSONObject()
        dns.put("protocol", "dns")
        dns.put("tag", "dns-out")

        outbounds.put(proxy)
        outbounds.put(direct)
        outbounds.put(block)
        if (settings.transparentProxy) outbounds.put(dns)

        return outbounds
    }

    private fun routing(): JSONObject {
        val routing = JSONObject()
        routing.put("domainStrategy", "IPIfNonMatch")

        val rules = JSONArray()

        val proxyDns = JSONObject()

        if (settings.transparentProxy) {
            val inboundTag = JSONArray()
            inboundTag.put("all-in")
            proxyDns.put("network", "udp")
            proxyDns.put("port", 53)
            proxyDns.put("inboundTag", inboundTag)
            proxyDns.put("outboundTag", "dns-out")
        } else {
            val proxyDnsIp = JSONArray()
            proxyDnsIp.put(settings.primaryDns)
            proxyDnsIp.put(settings.secondaryDns)
            proxyDns.put("ip", proxyDnsIp)
            proxyDns.put("port", 53)
            proxyDns.put("outboundTag", "proxy")
        }

        val directPrivate = JSONObject()
        directPrivate.put("ip", privateAddressRanges())
        directPrivate.put("outboundTag", "direct")

        rules.put(proxyDns)
        rules.put(directPrivate)

        routing.put("rules", rules)

        return routing
    }

    private fun config(): JSONObject {
        val config = JSONObject()
        config.put("log", log())
        config.put("dns", dns())
        config.put("inbounds", inbounds())
        config.put("outbounds", outbounds())
        config.put("routing", routing())
        return config
    }

    private fun parseInlineConfig(raw: String): ParsedConfigResult? {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("{")) return null
        val json = runCatching { JSONObject(trimmed) }.getOrNull() ?: return null
        return sanitizeImportedConfig(json)
    }

    private fun parseBase64InlineConfig(raw: String): ParsedConfigResult? {
        if (runCatching { URI(raw) }.getOrNull()?.scheme != null) return null
        val decoded = tryDecodeBase64(raw)
        if (decoded == raw) return null
        return parseInlineConfig(decoded)
    }

    private fun parseOutboundFromXray(link: String): ParsedOutboundResult {
        val base64 = XrayCore.json(link)
        val decoded = tryDecodeBase64(base64)
        val response = try {
            JSONObject(decoded)
        } catch (_: JSONException) {
            JSONObject()
        }
        val data = response.optJSONObject("data") ?: JSONObject()
        val outbounds = data.optJSONArray("outbounds") ?: JSONArray()
        val outbound = if (outbounds.length() > 0) {
            runCatching { sanitizeObject(outbounds.getJSONObject(0)) }.getOrNull()
        } else {
            null
        }
        return ParsedOutboundResult(
            success = response.optBoolean("success", false),
            outbound = outbound,
        )
    }

    private fun sanitizeImportedConfig(source: JSONObject): ParsedConfigResult? {
        val config = sanitizeObject(source)
        val remark = config.optString("remarks")
            .ifBlank { config.optString("remark") }
            .trim()
            .ifBlank { null }
        config.remove("remarks")
        config.remove("remark")
        val outbounds = config.optJSONArray("outbounds") ?: return null
        if (outbounds.length() == 0) return null
        return ParsedConfigResult(config = config, remark = remark)
    }

    private fun defaultRemark(): String = settings.getString(R.string.newProfile)

    private fun privateAddressRanges(): JSONArray {
        return JSONArray().apply {
            put("10.0.0.0/8")
            put("100.64.0.0/10")
            put("127.0.0.0/8")
            put("169.254.0.0/16")
            put("172.16.0.0/12")
            put("192.168.0.0/16")
            put("::1/128")
            put("fc00::/7")
            put("fe80::/10")
        }
    }

    private fun parseKnownOutbound(uri: URI): JSONObject? {
        return when (uri.scheme?.lowercase()) {
            "vless" -> parseVlessOutbound(uri)
            else -> null
        }
    }

    private fun parseVlessOutbound(uri: URI): JSONObject? {
        val address = uri.host ?: return null
        val port = uri.port.takeIf { it != -1 } ?: return null
        val id = uri.userInfo?.takeIf { it.isNotBlank() } ?: return null
        val query = parseQuery(uri.rawQuery)

        val user = JSONObject()
        user.put("id", id)
        user.put("encryption", query["encryption"].orEmpty().ifBlank { "none" })
        putIfNotBlank(user, "flow", query["flow"])

        val vnext = JSONObject()
        vnext.put("address", address)
        vnext.put("port", port)
        vnext.put("users", JSONArray().put(user))

        val settings = JSONObject()
        settings.put("vnext", JSONArray().put(vnext))

        val streamSettings = JSONObject()
        streamSettings.put("network", normalizeNetwork(query["type"]))
        streamSettings.put("security", query["security"].orEmpty().ifBlank { "none" })

        when (streamSettings.getString("security")) {
            "tls" -> buildTlsSettings(query)?.let { streamSettings.put("tlsSettings", it) }
            "reality" -> buildRealitySettings(query)?.let {
                streamSettings.put("realitySettings", it)
            }
        }

        buildTransportSettings(streamSettings, query)

        val outbound = JSONObject()
        outbound.put("protocol", "vless")
        outbound.put("settings", settings)
        outbound.put("streamSettings", streamSettings)
        return outbound
    }

    private fun buildTlsSettings(query: Map<String, String>): JSONObject? {
        val tls = JSONObject()
        putIfNotBlank(tls, "serverName", query["sni"])
        putIfNotBlank(tls, "fingerprint", query["fp"])
        query["alpn"]?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)?.let {
            if (it.isNotEmpty()) {
                val values = JSONArray()
                it.forEach(values::put)
                tls.put("alpn", values)
            }
        }
        putIfNotBlank(tls, "echConfigList", query["ech"])
        putIfNotBlank(tls, "pinnedPeerCertSha256", query["pcs"])
        putIfNotBlank(tls, "verifyPeerCertByName", query["vcn"])
        return tls.takeIf { it.length() > 0 }
    }

    private fun buildRealitySettings(query: Map<String, String>): JSONObject? {
        val reality = JSONObject()
        putIfNotBlank(reality, "serverName", query["sni"])
        putIfNotBlank(reality, "fingerprint", query["fp"])
        putIfNotBlank(reality, "publicKey", query["pbk"])
        putIfNotBlank(reality, "shortId", query["sid"])
        putIfNotBlank(reality, "mldsa65Verify", query["pqv"])
        putIfNotBlank(reality, "spiderX", query["spx"])
        return reality.takeIf { it.length() > 0 }
    }

    private fun buildTransportSettings(streamSettings: JSONObject, query: Map<String, String>) {
        val network = streamSettings.optString("network", "tcp")
        when (network) {
            "tcp" -> buildTcpSettings(query)?.let { streamSettings.put("tcpSettings", it) }
            "ws", "websocket" -> {
                val wsSettings = JSONObject()
                putIfNotBlank(wsSettings, "host", query["host"])
                putIfNotBlank(wsSettings, "path", query["path"])
                if (wsSettings.length() > 0) streamSettings.put("wsSettings", wsSettings)
            }
            "grpc", "gun" -> {
                val grpcSettings = JSONObject()
                putIfNotBlank(grpcSettings, "authority", query["authority"])
                putIfNotBlank(grpcSettings, "serviceName", query["serviceName"])
                if (query["mode"] == "multi") grpcSettings.put("multiMode", true)
                if (grpcSettings.length() > 0) streamSettings.put("grpcSettings", grpcSettings)
            }
            "httpupgrade" -> {
                val httpUpgradeSettings = JSONObject()
                putIfNotBlank(httpUpgradeSettings, "host", query["host"])
                putIfNotBlank(httpUpgradeSettings, "path", query["path"])
                if (httpUpgradeSettings.length() > 0) {
                    streamSettings.put("httpupgradeSettings", httpUpgradeSettings)
                }
            }
            "xhttp", "splithttp" -> {
                val xhttpSettings = JSONObject()
                putIfNotBlank(xhttpSettings, "host", query["host"])
                putIfNotBlank(xhttpSettings, "path", query["path"])
                putIfNotBlank(xhttpSettings, "mode", query["mode"])
                query["extra"]?.takeIf { it.isNotBlank() }?.let {
                    runCatching { JSONObject(it) }.getOrNull()?.let { extra ->
                        xhttpSettings.put("extra", extra)
                    }
                }
                if (xhttpSettings.length() > 0) streamSettings.put("xhttpSettings", xhttpSettings)
            }
        }
    }

    private fun buildTcpSettings(query: Map<String, String>): JSONObject? {
        val hostValues = query["host"]?.split(',')?.map(String::trim)?.filter(String::isNotEmpty).orEmpty()
        val pathValues = query["path"]?.split(',')?.map(String::trim)?.filter(String::isNotEmpty).orEmpty()
        val headerType = query["headerType"].orEmpty().ifBlank { null }
        if (headerType == null && hostValues.isEmpty() && pathValues.isEmpty()) return null

        val header = JSONObject()
        header.put("type", headerType ?: "http")

        if (hostValues.isNotEmpty() || pathValues.isNotEmpty()) {
            val request = JSONObject()
            if (pathValues.isNotEmpty()) {
                val path = JSONArray()
                pathValues.forEach(path::put)
                request.put("path", path)
            }
            if (hostValues.isNotEmpty()) {
                val headers = JSONObject()
                val host = JSONArray()
                hostValues.forEach(host::put)
                headers.put("Host", host)
                request.put("headers", headers)
            }
            header.put("request", request)
        }

        return JSONObject().put("header", header)
    }

    private fun parseQuery(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        return rawQuery.split('&')
            .filter(String::isNotBlank)
            .associate { part ->
                val pieces = part.split('=', limit = 2)
                val key = decodeUriPart(pieces[0])
                val value = decodeUriPart(pieces.getOrElse(1) { "" })
                key to value
            }
    }

    private fun decodeUriPart(value: String): String {
        return URLDecoder.decode(value, StandardCharsets.UTF_8)
    }

    private fun normalizeNetwork(value: String?): String {
        return when (value?.trim()?.lowercase().orEmpty()) {
            "", "raw" -> "tcp"
            "gun" -> "grpc"
            else -> value!!.trim().lowercase()
        }
    }

    private fun putIfNotBlank(target: JSONObject, key: String, value: String?) {
        if (!value.isNullOrBlank()) target.put(key, value)
    }

    private fun sanitizeObject(source: JSONObject): JSONObject {
        val sanitized = JSONObject()
        val keys = source.keys().asSequence().toList()
        keys.forEach { key ->
            sanitizeValue(source.opt(key))?.let { sanitized.put(key, it) }
        }
        return sanitized
    }

    private fun sanitizeArray(source: JSONArray): JSONArray {
        val sanitized = JSONArray()
        for (index in 0 until source.length()) {
            sanitizeValue(source.opt(index))?.let(sanitized::put)
        }
        return sanitized
    }

    private fun sanitizeValue(value: Any?): Any? {
        return when (value) {
            null, JSONObject.NULL -> null
            is JSONObject -> sanitizeObject(value)
            is JSONArray -> sanitizeArray(value)
            else -> value
        }
    }

}
