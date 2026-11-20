package io.github.derundevu.yaxc.helper

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** An understood record whose semantics cannot be represented by the selected core. */
class UnsupportedProfileException(message: String) : IllegalArgumentException(message)

internal object ProxyImportHelper {
    fun unsupported(reason: String): Nothing = throw UnsupportedProfileException(reason)

    fun decode(value: String): String =
        URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8.name())

    fun naiveUri(uri: URI): JSONObject {
        val quic = uri.scheme.equals("naive+quic", ignoreCase = true)
        val address = requireNotNull(uri.host) { "Missing Naive server" }.removeSurrounding("[", "]")
        val port = uri.port.takeIf { it != -1 } ?: 443
        require(port in 1..65535) { "Invalid Naive port" }
        require(uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") { "Invalid Naive path" }
        val credentials = uri.rawUserInfo
        val separator = credentials?.indexOf(':') ?: -1
        require(credentials == null || separator >= 0) { "Missing Naive password separator" }
        val user = credentials?.let { decode(it.substring(0, separator)) }.orEmpty()
        val pass = credentials?.let { decode(it.substring(separator + 1)) }.orEmpty()
        require(':' !in user && (user.isNotEmpty() || pass.isEmpty())) { "Invalid Naive credentials" }
        val query = linkedMapOf<String, String>()
        uri.rawQuery?.split('&')?.filter(String::isNotEmpty)?.forEach { part ->
            val pieces = part.split('=', limit = 2)
            val key = decode(pieces[0])
            require(key !in query) { "Duplicate Naive option" }
            query[key] = decode(pieces.getOrElse(1) { "" })
        }
        if (query.keys.any { it !in setOf("sni", "padding", "tfo") }) {
            unsupported("Unsupported Naive URI option")
        }
        query["padding"]?.let { if (!booleanOption(it)) unsupported("Disabling Naive padding is unsupported") }
        val tfo = query["tfo"]?.let(::booleanOption)
        if (quic && tfo == true) unsupported("TCP Fast Open is unavailable for Naive QUIC")
        return naive(address, port, user, pass, quic, true, query["sni"]).apply {
            if (tfo != null && !quic) getJSONObject("streamSettings")
                .put("sockopt", JSONObject().put("tcpFastOpen", tfo))
        }
    }

    private fun booleanOption(value: String): Boolean = when (value.lowercase()) {
        "true", "1" -> true
        "false", "0" -> false
        else -> throw IllegalArgumentException("Invalid boolean option")
    }

    private fun naive(address: String, port: Int, user: String, pass: String, quic: Boolean,
                      udpOverTcp: Boolean, sni: String?): JSONObject = JSONObject()
        .put("protocol", "naive")
        .put("settings", JSONObject().put("address", address).put("port", port)
            .put("user", user).put("pass", pass).put("quic", quic)
            .put("udpOverTcp", udpOverTcp).put("headers", JSONObject()))
        .put("streamSettings", JSONObject().put("network", "raw").put("security", "tls")
            .put("tlsSettings", JSONObject().apply { if (!sni.isNullOrBlank()) put("serverName", sni) }))

    fun isSingBox(config: JSONObject): Boolean =
        config.optJSONArray("outbounds")?.let { outbounds ->
            (0 until outbounds.length()).any {
                outbounds.optJSONObject(it)?.let { outbound -> outbound.has("type") && !outbound.has("protocol") } == true
            }
        } == true

    fun singBoxOutbound(source: JSONObject): JSONObject {
        val protocol = requiredString(source, "type")
        if (protocol !in setOf("naive", "vless")) unsupported("Unsupported sing-box protocol")
        // Validate required data before classifying unsupported optional semantics.
        val server = requiredString(source, "server")
        val port = requiredPort(source, "server_port")
        val user = requiredString(source, if (protocol == "naive") "username" else "uuid", allowEmpty = protocol == "naive")
        val password = if (protocol == "naive") requiredString(source, "password", allowEmpty = true) else ""
        if (protocol == "naive") require(':' !in user && (user.isNotEmpty() || password.isEmpty())) { "Invalid Naive credentials" }
        val common = setOf("type", "tag", "server", "server_port", "tls", "tcp_fast_open")
        val allowed = if (protocol == "naive") common + setOf(
            "username", "password", "quic", "quic_congestion_control", "udp_over_tcp", "extra_headers", "headers",
        ) else common + setOf("uuid", "flow", "network", "packet_encoding", "transport")
        requireSupportedKeys(source, allowed)
        if (source.has("tcp_fast_open")) requiredBoolean(source, "tcp_fast_open")
        val tls = source.optJSONObject("tls")
        if (source.has("tls")) require(tls != null) { "Invalid TLS options" }
        if (protocol == "naive") {
            require(tls != null && requiredBoolean(tls, "enabled")) { "Naive requires TLS" }
            requireSupportedKeys(tls, setOf("enabled", "server_name"))
            val quic = optionalBoolean(source, "quic")
            val tfo = if (source.has("tcp_fast_open")) requiredBoolean(source, "tcp_fast_open") else null
            if (quic && tfo == true) unsupported("TCP Fast Open is unavailable for Naive QUIC")
            val congestion = optionalString(source, "quic_congestion_control")
            if (!congestion.isNullOrEmpty() && (congestion !in setOf("bbr", "bbr2", "cubic", "reno") || !quic)) {
                unsupported("Unsupported Naive QUIC congestion control")
            }
            return naive(server, port, user, password, quic, udpOverTcp(source), optionalString(tls, "server_name"))
                .apply {
                    if (!congestion.isNullOrEmpty()) getJSONObject("settings").put("quicCongestionControl", congestion)
                    naiveHeaders(source)?.let { getJSONObject("settings").put("headers", it) }
                    if (tfo != null && !quic) getJSONObject("streamSettings")
                        .put("sockopt", JSONObject().put("tcpFastOpen", tfo))
                }
        }
        optionalString(source, "network")?.let { if (it != "tcp") unsupported("Unsupported VLESS network restriction") }
        optionalString(source, "packet_encoding")?.let { if (it.isNotEmpty()) unsupported("Unsupported VLESS packet encoding") }
        val account = JSONObject().put("id", user).put("encryption", "none")
        optionalString(source, "flow")?.let { account.put("flow", it) }
        val stream = JSONObject().put("network", "raw").put("security", "none")
        tls?.let { vlessTls(it, stream) }
        if (source.has("transport")) vlessTransport(source.getJSONObject("transport"), stream)
        if (source.has("tcp_fast_open")) stream.put("sockopt", JSONObject().put("tcpFastOpen", requiredBoolean(source, "tcp_fast_open")))
        return JSONObject().put("protocol", "vless").put("settings", JSONObject().put("vnext", JSONArray()
            .put(JSONObject().put("address", server).put("port", port).put("users", JSONArray().put(account)))))
            .put("streamSettings", stream)
    }

    private fun udpOverTcp(source: JSONObject): Boolean {
        if (!source.has("udp_over_tcp")) return true
        val option = source.get("udp_over_tcp")
        if (option is Boolean) return option
        require(option is JSONObject) { "Invalid UDP-over-TCP option" }
        requireSupportedKeys(option, setOf("enabled", "version"))
        if (option.has("version") && option.getInt("version") != 2) unsupported("Unsupported UDP-over-TCP version")
        return optionalBoolean(option, "enabled")
    }

    private fun vlessTls(tls: JSONObject, stream: JSONObject) {
        requireSupportedKeys(tls, setOf("enabled", "server_name", "insecure", "alpn", "utls", "reality"))
        val enabled = optionalBoolean(tls, "enabled")
        if (!enabled) {
            if (tls.length() > 1) unsupported("TLS options on disabled TLS are unsupported")
            return
        }
        val options = JSONObject()
        optionalString(tls, "server_name")?.let { options.put("serverName", it) }
        if (tls.has("insecure") && requiredBoolean(tls, "insecure")) unsupported("Insecure TLS is unsupported")
        if (tls.has("alpn")) {
            val alpn = tls.getJSONArray("alpn")
            for (index in 0 until alpn.length()) require(alpn.get(index) is String) { "Invalid ALPN" }
            options.put("alpn", alpn)
        }
        (if (tls.has("utls")) tls.getJSONObject("utls") else null)?.let {
            requireSupportedKeys(it, setOf("enabled", "fingerprint"))
            if (optionalBoolean(it, "enabled")) {
                val fingerprint = optionalString(it, "fingerprint")?.takeIf(String::isNotBlank)
                    ?: unsupported("Implicit uTLS fingerprint is unsupported")
                options.put("fingerprint", fingerprint)
            }
            else if (it.length() > 1) unsupported("Options on disabled uTLS are unsupported")
        }
        val reality = if (tls.has("reality")) tls.getJSONObject("reality") else null
        if (reality != null) {
            requireSupportedKeys(reality, setOf("enabled", "public_key", "short_id"))
            if (!optionalBoolean(reality, "enabled") && reality.length() > 1) unsupported("Options on disabled Reality are unsupported")
        }
        if (reality != null && optionalBoolean(reality, "enabled")) {
            requireSupportedKeys(reality, setOf("enabled", "public_key", "short_id"))
            if (options.has("alpn")) unsupported("Reality ALPN override is unsupported")
            options.put("publicKey", requiredString(reality, "public_key"))
            optionalString(reality, "short_id")?.let { options.put("shortId", it) }
            stream.put("security", "reality").put("realitySettings", options)
        } else stream.put("security", "tls").put("tlsSettings", options)
    }

    private fun vlessTransport(transport: JSONObject, stream: JSONObject) {
        val type = requiredString(transport, "type")
        when (type) {
            "ws", "httpupgrade" -> {
                requireSupportedKeys(transport, setOf("type", "path", "host", "headers"))
                val options = JSONObject()
                optionalString(transport, "path")?.let { options.put("path", it) }
                optionalString(transport, "host")?.let { options.put("host", it) }
                (if (transport.has("headers")) transport.getJSONObject("headers") else null)?.let { headers ->
                    if (type == "httpupgrade") unsupported("HTTPUpgrade custom headers are unsupported")
                    headers.keys().forEach { require(headers.get(it) is String) { "Invalid WebSocket header" } }
                    options.put("headers", headers)
                }
                stream.put("network", type).put(if (type == "ws") "wsSettings" else "httpupgradeSettings", options)
            }
            "grpc" -> {
                requireSupportedKeys(transport, setOf("type", "service_name"))
                val options = JSONObject()
                optionalString(transport, "service_name")?.let { options.put("serviceName", it) }
                stream.put("network", "grpc").put("grpcSettings", options)
            }
            else -> unsupported("Unsupported VLESS transport")
        }
    }

    private fun naiveHeaders(source: JSONObject): JSONObject? {
        val headers = if (source.isNull("extra_headers")) {
            if (!source.has("headers")) return null
            source.getJSONObject("headers")
        } else {
            if (source.has("headers")) unsupported("Ambiguous Naive headers")
            val extra = source.getJSONObject("extra_headers")
            JSONObject().apply {
                extra.keys().forEach { key ->
                    when (val value = extra.get(key)) {
                        JSONObject.NULL -> Unit
                        is String -> put(key, value)
                        is JSONArray -> {
                            for (index in 0 until value.length()) {
                                require(value.get(index) is String) { "Invalid Naive header value" }
                            }
                            // sing-box passes only the first value of each header to Cronet.
                            if (value.length() > 0) put(key, value.getString(0))
                        }
                        else -> throw IllegalArgumentException("Invalid Naive header value")
                    }
                }
            }
        }
        validateNaiveHeaders(headers)
        return headers
    }

    private fun validateNaiveHeaders(headers: JSONObject) {
        val reserved = setOf("host", "connection", "proxy-connection", "keep-alive", "transfer-encoding",
            "upgrade", "te", "trailer", "content-length", "proxy-authorization", "padding")
        val seen = mutableSetOf<String>()
        headers.keys().forEach { key ->
            val value = headers.get(key)
            require(value is String && value.all { it == '\t' || (it.code >= 32 && it.code != 127) }) { "Invalid Naive header" }
            require(key.matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+"))) { "Invalid Naive header name" }
            if (key.startsWith('-') || key.lowercase() in reserved || !seen.add(key.lowercase())) {
                unsupported("Unsupported Naive control header")
            }
        }
    }

    private fun requireSupportedKeys(source: JSONObject, allowed: Set<String>) {
        if (source.keys().asSequence().any { it !in allowed }) unsupported("Unsupported sing-box option")
    }

    private fun requiredString(source: JSONObject, key: String, allowEmpty: Boolean = false): String {
        val value = source.get(key)
        require(value is String && (allowEmpty || value.isNotBlank())) { "Invalid $key" }
        return value
    }

    private fun optionalString(source: JSONObject, key: String): String? =
        if (source.has(key)) requiredString(source, key, allowEmpty = true) else null

    private fun requiredPort(source: JSONObject, key: String): Int {
        val value = source.get(key)
        require(value is Number && value.toDouble() == value.toInt().toDouble() && value.toInt() in 1..65535) { "Invalid server port" }
        return value.toInt()
    }

    private fun requiredBoolean(source: JSONObject, key: String): Boolean {
        val value = source.get(key)
        require(value is Boolean) { "Invalid $key" }
        return value
    }

    private fun optionalBoolean(source: JSONObject, key: String): Boolean =
        if (source.has(key)) requiredBoolean(source, key) else false
}
