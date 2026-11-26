package io.github.derundevu.yaxc.presentation.profile

import io.github.derundevu.yaxc.presentation.editor.JsonEditorTools
import io.github.derundevu.yaxc.presentation.editor.JsonRoot
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class ProfileEditorMode {
    Visual,
    Json,
}

data class ProfileVisualConfig(
    val baseRootJson: String = "{}",
    val outbounds: List<ProfileOutboundDraft> = listOf(ProfileOutboundDraft()),
    val selectedOutboundIndex: Int = 0,
    val hadExplicitOutbounds: Boolean = false,
) {
    val activeOutbound: ProfileOutboundDraft
        get() = outbounds.getOrElse(selectedOutboundIndex) { outbounds.first() }

    fun updateActiveOutbound(transform: (ProfileOutboundDraft) -> ProfileOutboundDraft): ProfileVisualConfig {
        val index = selectedOutboundIndex.coerceIn(outbounds.indices)
        val updated = transform(outbounds[index]).copy(dirty = true)
        return copy(
            outbounds = outbounds.toMutableList().apply { set(index, updated) },
        )
    }

    fun selectOutbound(index: Int): ProfileVisualConfig = copy(
        selectedOutboundIndex = index.coerceIn(outbounds.indices),
    )

    fun addOutbound(): ProfileVisualConfig {
        val next = ProfileOutboundDraft(
            tag = "outbound-${outbounds.size + 1}",
            dirty = true,
        )
        return copy(
            outbounds = outbounds + next,
            selectedOutboundIndex = outbounds.size,
        )
    }

    fun deleteSelectedOutbound(): ProfileVisualConfig {
        if (outbounds.size <= 1) return this
        val index = selectedOutboundIndex.coerceIn(outbounds.indices)
        val remaining = outbounds.toMutableList().apply { removeAt(index) }
        return copy(
            outbounds = remaining,
            selectedOutboundIndex = index.coerceAtMost(remaining.lastIndex),
        )
    }
}

fun ProfileOutboundDraft.withProtocol(newProtocol: String): ProfileOutboundDraft {
    val normalized = newProtocol.trim().lowercase()
    return when (normalized) {
        "naive" -> copy(
            protocol = newProtocol,
            network = if (network in setOf("tcp", "hysteria")) "raw" else network,
            security = if (security == "none") "tls" else security,
        )
        "hysteria" -> copy(
            protocol = newProtocol,
            network = "hysteria",
        )
        else -> copy(
            protocol = newProtocol,
            network = if (normalized in ProfileVisualOptions.networkProxyProtocols && network == "hysteria") {
                "tcp"
            } else {
                network
            },
        )
    }
}

data class ProfileOutboundDraft(
    val id: String = UUID.randomUUID().toString(),
    val originalJson: String? = null,
    val dirty: Boolean = false,
    val tag: String = "proxy",
    val protocol: String = "vless",
    val address: String = "",
    val port: String = "",
    val userId: String = "",
    val username: String = "",
    val password: String = "",
    val vlessFlow: String = "",
    val vlessEncryption: String = "none",
    val vmessSecurity: String = "auto",
    val vmessAlterId: String = "",
    val ssMethod: String = "aes-256-gcm",
    val ssUot: Boolean = false,
    val naiveQuic: Boolean = false,
    val naiveUdpOverTcp: Boolean = true,
    val naiveCongestionControl: String = "",
    val naiveHeadersText: String = "",
    val hysteriaVersion: String = "2",
    val hysteriaAuth: String = "",
    val freedomDomainStrategy: String = "",
    val freedomRedirect: String = "",
    val blackholeResponseType: String = "none",
    val network: String = "tcp",
    val tcpHeaderType: String = "none",
    val tcpHttpHost: String = "",
    val tcpHttpPath: String = "",
    val transportHost: String = "",
    val transportPath: String = "",
    val grpcMultiMode: Boolean = false,
    val xhttpMode: String = "",
    val security: String = "none",
    val sni: String = "",
    val fingerprint: String = "",
    val alpn: String = "",
    val allowInsecure: Boolean = false,
    val realityPublicKey: String = "",
    val realityShortId: String = "",
    val realitySpiderX: String = "",
    val realityMldsa65Verify: String = "",
    val muxEnabled: Boolean = false,
    val muxConcurrency: String = "",
    val muxXudpConcurrency: String = "",
    val tcpFastOpen: Boolean? = null,
    val dialerProxy: String = "",
)

object ProfileVisualOptions {
    val protocols = listOf(
        "vless",
        "vmess",
        "trojan",
        "shadowsocks",
        "naive",
        "socks",
        "http",
        "hysteria",
        "freedom",
        "blackhole",
    )

    val outboundTags = listOf("proxy", "direct", "block", "dns-out")
    val vlessFlows = listOf("", "xtls-rprx-vision", "xtls-rprx-vision-udp443")
    val vlessEncryptions = listOf("none")
    val vmessSecurities = listOf("auto", "aes-128-gcm", "chacha20-poly1305", "none", "zero")
    val shadowsocksMethods = listOf(
        "2022-blake3-aes-128-gcm",
        "2022-blake3-aes-256-gcm",
        "2022-blake3-chacha20-poly1305",
        "aes-256-gcm",
        "aes-128-gcm",
        "chacha20-ietf-poly1305",
        "xchacha20-ietf-poly1305",
        "none",
    )
    val naiveCongestionControls = listOf("", "bbr", "bbr2", "cubic", "reno")
    val freedomDomainStrategies = listOf("", "AsIs", "UseIP", "UseIPv4", "UseIPv6", "ForceIP", "ForceIPv4", "ForceIPv6")
    val blackholeResponseTypes = listOf("none", "http")
    val networks = listOf("raw", "tcp", "ws", "xhttp", "httpupgrade", "grpc", "hysteria", "kcp")
    val tcpHeaderTypes = listOf("none", "http")
    val xhttpModes = listOf("", "auto", "packet-up", "stream-up", "stream-one")
    val securities = listOf("none", "tls", "reality")
    val fingerprints = listOf("", "chrome", "firefox", "safari", "ios", "android", "edge", "360", "qq", "random", "randomized")
    val alpnOptions = listOf("", "h2,http/1.1", "h2", "http/1.1", "h3", "h3,h2,http/1.1")
    val networkProxyProtocols = setOf(
        "vless",
        "vmess",
        "trojan",
        "shadowsocks",
        "naive",
        "socks",
        "http",
        "hysteria",
        "wireguard",
        "masque",
    )
    val knownEditableSettingsProtocols = setOf(
        "vless",
        "vmess",
        "trojan",
        "shadowsocks",
        "naive",
        "socks",
        "http",
        "hysteria",
        "freedom",
        "blackhole",
    )
}

object ProfileVisualConfigHelper {
    fun parse(rawJson: String): ProfileVisualConfig? {
        val trimmed = rawJson.trim()
        if (trimmed.isEmpty()) {
            return ProfileVisualConfig(
                baseRootJson = "{}",
                outbounds = listOf(ProfileOutboundDraft()),
                selectedOutboundIndex = 0,
                hadExplicitOutbounds = false,
            )
        }
        if (JsonEditorTools.validate(rawJson, JsonRoot.Object) != null) return null
        val root = runCatching { JSONObject(trimmed) }.getOrNull() ?: return null
        val outboundsArray = root.optJSONArray("outbounds")
        val parsedOutbounds = mutableListOf<ProfileOutboundDraft>()
        if (outboundsArray != null) {
            for (index in 0 until outboundsArray.length()) {
                val obj = outboundsArray.optJSONObject(index) ?: continue
                parsedOutbounds.add(parseOutbound(obj))
            }
        }
        val hasExplicit = parsedOutbounds.isNotEmpty()
        val finalOutbounds = if (hasExplicit) parsedOutbounds else listOf(ProfileOutboundDraft())
        val selectedIndex = pickDefaultOutboundIndex(finalOutbounds)
        return ProfileVisualConfig(
            baseRootJson = root.toString(),
            outbounds = finalOutbounds,
            selectedOutboundIndex = selectedIndex,
            hadExplicitOutbounds = hasExplicit,
        )
    }

    fun buildJson(config: ProfileVisualConfig): String {
        val root = runCatching { JSONObject(config.baseRootJson) }.getOrElse { JSONObject() }
        val outboundsArray = JSONArray()
        config.outbounds.forEach { draft ->
            outboundsArray.put(serializeOutbound(draft))
        }
        root.put("outbounds", outboundsArray)
        return root.toString(2) + "\n"
    }

    fun supportsTransportAndSecurity(protocol: String): Boolean {
        val normalized = protocol.trim().lowercase()
        return normalized !in setOf("freedom", "blackhole", "dns")
    }

    private fun pickDefaultOutboundIndex(outbounds: List<ProfileOutboundDraft>): Int {
        val proxyTagged = outbounds.indexOfFirst { it.tag.equals("proxy", ignoreCase = true) }
        if (proxyTagged >= 0) return proxyTagged
        val firstProxy = outbounds.indexOfFirst {
            it.protocol.trim().lowercase() in ProfileVisualOptions.networkProxyProtocols
        }
        return if (firstProxy >= 0) firstProxy else 0
    }

    private fun parseOutbound(obj: JSONObject): ProfileOutboundDraft {
        val protocol = obj.optString("protocol").trim().ifBlank { "vless" }
        val normalizedProtocol = protocol.lowercase()
        val tag = obj.optString("tag")
        val settings = obj.optJSONObject("settings") ?: JSONObject()
        val stream = obj.optJSONObject("streamSettings") ?: JSONObject()

        var address = ""
        var port = ""
        var userId = ""
        var username = ""
        var password = ""
        var vlessFlow = ""
        var vlessEncryption = "none"
        var vmessSecurity = "auto"
        var vmessAlterId = ""
        var ssMethod = "aes-256-gcm"
        var ssUot = false
        var naiveQuic = false
        var naiveUdpOverTcp = true
        var naiveCongestionControl = ""
        var naiveHeadersText = ""
        var hysteriaVersion = "2"
        var hysteriaAuth = ""
        var freedomDomainStrategy = ""
        var freedomRedirect = ""
        var blackholeResponseType = "none"

        when (normalizedProtocol) {
            "vless", "vmess" -> {
                val server = settings.optJSONArray("vnext")?.optJSONObject(0)
                if (server != null) {
                    address = server.optString("address")
                    port = optPort(server, "port")
                    val user = server.optJSONArray("users")?.optJSONObject(0)
                    if (user != null) {
                        userId = user.optString("id")
                        if (normalizedProtocol == "vless") {
                            vlessFlow = user.optString("flow")
                            vlessEncryption = user.optString("encryption").ifBlank { "none" }
                        } else {
                            vmessSecurity = user.optString("security").ifBlank { "auto" }
                            vmessAlterId = optIntString(user, "alterId")
                        }
                    }
                }
            }
            "trojan" -> {
                val server = settings.optJSONArray("servers")?.optJSONObject(0)
                if (server != null) {
                    address = server.optString("address")
                    port = optPort(server, "port")
                    password = server.optString("password")
                }
            }
            "shadowsocks" -> {
                val server = settings.optJSONArray("servers")?.optJSONObject(0)
                if (server != null) {
                    address = server.optString("address")
                    port = optPort(server, "port")
                    ssMethod = server.optString("method").ifBlank { "aes-256-gcm" }
                    password = server.optString("password")
                    ssUot = server.optBoolean("uot", false)
                }
            }
            "naive" -> {
                address = settings.optString("address")
                port = optPort(settings, "port")
                username = settings.optString("user")
                password = settings.optString("pass")
                naiveQuic = settings.optBoolean("quic", false)
                naiveUdpOverTcp = settings.optBoolean("udpOverTcp", true)
                naiveCongestionControl = settings.optString("quicCongestionControl")
                settings.optJSONObject("headers")?.let { headers ->
                    naiveHeadersText = headers.keys().asSequence()
                        .map { key -> "$key: ${headers.optString(key)}" }
                        .joinToString("\n")
                }
            }
            "socks", "http" -> {
                val server = settings.optJSONArray("servers")?.optJSONObject(0)
                if (server != null) {
                    address = server.optString("address")
                    port = optPort(server, "port")
                    val user = server.optJSONArray("users")?.optJSONObject(0)
                    if (user != null) {
                        username = user.optString("user")
                        password = user.optString("pass")
                    }
                }
            }
            "hysteria" -> {
                val server = settings.optJSONArray("servers")?.optJSONObject(0)
                address = server?.optString("address")?.takeIf { it.isNotBlank() } ?: settings.optString("address")
                port = server?.let { optPort(it, "port") }?.takeIf { it.isNotBlank() } ?: optPort(settings, "port")
                hysteriaVersion = if (settings.has("version")) settings.optInt("version", 2).toString() else "2"
                hysteriaAuth = settings.optString("auth").ifBlank {
                    stream.optJSONObject("hysteriaSettings")?.optString("auth").orEmpty()
                }
            }
            "freedom" -> {
                freedomDomainStrategy = settings.optString("domainStrategy")
                freedomRedirect = settings.optString("redirect")
            }
            "blackhole" -> {
                blackholeResponseType = settings.optJSONObject("response")?.optString("type")?.ifBlank { "none" } ?: "none"
            }
        }

        val network = stream.optString("network").ifBlank { "tcp" }
        val normalizedNet = network.lowercase()
        val tcpLike = stream.optJSONObject("tcpSettings") ?: stream.optJSONObject("rawSettings")
        val tcpHeader = tcpLike?.optJSONObject("header")
        val tcpHeaderType = tcpHeader?.optString("type")?.ifBlank { "none" } ?: "none"
        val tcpRequest = tcpHeader?.optJSONObject("request")
        val tcpHttpHost = joinJsonStringOrArray(tcpRequest?.optJSONObject("headers")?.opt("Host"))
        val tcpHttpPath = joinJsonStringOrArray(tcpRequest?.opt("path"))

        var transportHost = ""
        var transportPath = ""
        var grpcMultiMode = false
        var xhttpMode = ""
        when (normalizedNet) {
            "ws", "websocket" -> {
                val ws = stream.optJSONObject("wsSettings")
                transportPath = ws?.optString("path").orEmpty()
                transportHost = ws?.optString("host")?.takeIf { it.isNotBlank() }
                    ?: ws?.optJSONObject("headers")?.optString("Host").orEmpty()
            }
            "httpupgrade" -> {
                val hu = stream.optJSONObject("httpupgradeSettings")
                transportPath = hu?.optString("path").orEmpty()
                transportHost = hu?.optString("host").orEmpty()
            }
            "xhttp", "splithttp" -> {
                val xh = stream.optJSONObject("xhttpSettings") ?: stream.optJSONObject("splithttpSettings")
                transportPath = xh?.optString("path").orEmpty()
                transportHost = xh?.optString("host").orEmpty()
                xhttpMode = xh?.optString("mode").orEmpty()
            }
            "grpc", "gun" -> {
                val grpc = stream.optJSONObject("grpcSettings")
                transportPath = grpc?.optString("serviceName").orEmpty()
                transportHost = grpc?.optString("authority").orEmpty()
                grpcMultiMode = grpc?.optBoolean("multiMode", false) ?: false
            }
        }

        val security = stream.optString("security").ifBlank { "none" }
        val tls = stream.optJSONObject("tlsSettings")
        val reality = stream.optJSONObject("realitySettings")
        val sni = when (security.lowercase()) {
            "reality" -> reality?.optString("serverName").orEmpty()
            "tls" -> tls?.optString("serverName").orEmpty()
            else -> ""
        }
        val fingerprint = when (security.lowercase()) {
            "reality" -> reality?.optString("fingerprint").orEmpty()
            "tls" -> tls?.optString("fingerprint").orEmpty()
            else -> ""
        }
        val alpn = joinJsonStringOrArray(tls?.opt("alpn"))
        val allowInsecure = tls?.optBoolean("allowInsecure", false) ?: false
        val realityPublicKey = reality?.optString("publicKey").orEmpty()
        val realityShortId = reality?.optString("shortId").orEmpty()
        val realitySpiderX = reality?.optString("spiderX").orEmpty()
        val realityMldsa65Verify = reality?.optString("mldsa65Verify").orEmpty()

        val mux = obj.optJSONObject("mux")
        val muxEnabled = mux?.optBoolean("enabled", false) ?: false
        val muxConcurrency = optIntString(mux, "concurrency")
        val muxXudpConcurrency = optIntString(mux, "xudpConcurrency")

        val sockopt = stream.optJSONObject("sockopt")
        val tcpFastOpen = if (sockopt?.has("tcpFastOpen") == true && !sockopt.isNull("tcpFastOpen")) {
            sockopt.optBoolean("tcpFastOpen")
        } else {
            null
        }
        val dialerProxy = sockopt?.optString("dialerProxy").orEmpty()

        return ProfileOutboundDraft(
            originalJson = obj.toString(),
            dirty = false,
            tag = tag,
            protocol = protocol,
            address = address,
            port = port,
            userId = userId,
            username = username,
            password = password,
            vlessFlow = vlessFlow,
            vlessEncryption = vlessEncryption,
            vmessSecurity = vmessSecurity,
            vmessAlterId = vmessAlterId,
            ssMethod = ssMethod,
            ssUot = ssUot,
            naiveQuic = naiveQuic,
            naiveUdpOverTcp = naiveUdpOverTcp,
            naiveCongestionControl = naiveCongestionControl,
            naiveHeadersText = naiveHeadersText,
            hysteriaVersion = hysteriaVersion,
            hysteriaAuth = hysteriaAuth,
            freedomDomainStrategy = freedomDomainStrategy,
            freedomRedirect = freedomRedirect,
            blackholeResponseType = blackholeResponseType,
            network = network,
            tcpHeaderType = tcpHeaderType,
            tcpHttpHost = tcpHttpHost,
            tcpHttpPath = tcpHttpPath,
            transportHost = transportHost,
            transportPath = transportPath,
            grpcMultiMode = grpcMultiMode,
            xhttpMode = xhttpMode,
            security = security,
            sni = sni,
            fingerprint = fingerprint,
            alpn = alpn,
            allowInsecure = allowInsecure,
            realityPublicKey = realityPublicKey,
            realityShortId = realityShortId,
            realitySpiderX = realitySpiderX,
            realityMldsa65Verify = realityMldsa65Verify,
            muxEnabled = muxEnabled,
            muxConcurrency = muxConcurrency,
            muxXudpConcurrency = muxXudpConcurrency,
            tcpFastOpen = tcpFastOpen,
            dialerProxy = dialerProxy,
        )
    }

    private fun serializeOutbound(draft: ProfileOutboundDraft): JSONObject {
        val original = draft.originalJson?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (!draft.dirty && original != null) return original

        val result = original ?: JSONObject()
        val originalProtocol = original?.optString("protocol")?.trim()?.lowercase().orEmpty()
        val protocol = draft.protocol.trim().ifBlank { "vless" }
        val normalizedProtocol = protocol.lowercase()
        val protocolChanged = original != null && originalProtocol != normalizedProtocol

        val tag = draft.tag.trim()
        if (tag.isNotEmpty()) result.put("tag", tag) else result.remove("tag")
        result.put("protocol", protocol)

        if (protocolChanged) {
            result.remove("settings")
        }
        serializeSettings(result, draft, normalizedProtocol)

        if (supportsTransportAndSecurity(normalizedProtocol)) {
            serializeStreamSettings(result, draft, normalizedProtocol)
        } else {
            val stream = result.optJSONObject("streamSettings")
            if (stream != null) {
                serializeSockopt(stream, draft)
                if (stream.length() == 0) result.remove("streamSettings")
            } else if (draft.tcpFastOpen != null || draft.dialerProxy.isNotBlank()) {
                val created = JSONObject()
                serializeSockopt(created, draft)
                if (created.length() > 0) result.put("streamSettings", created)
            }
        }

        serializeMux(result, draft)
        return result
    }

    private fun serializeSettings(
        result: JSONObject,
        draft: ProfileOutboundDraft,
        normalizedProtocol: String,
    ) {
        if (normalizedProtocol !in ProfileVisualOptions.knownEditableSettingsProtocols) {
            return
        }
        val settings = result.optJSONObject("settings") ?: JSONObject()
        val portNum = draft.port.trim().toIntOrNull()

        when (normalizedProtocol) {
            "vless", "vmess" -> {
                val vnext = settings.optJSONArray("vnext") ?: JSONArray()
                val server = vnext.optJSONObject(0) ?: JSONObject()
                putOrRemove(server, "address", draft.address.trim())
                if (portNum != null) server.put("port", portNum) else server.remove("port")
                val users = server.optJSONArray("users") ?: JSONArray()
                val user = users.optJSONObject(0) ?: JSONObject()
                putOrRemove(user, "id", draft.userId.trim())
                if (normalizedProtocol == "vless") {
                    user.put("encryption", draft.vlessEncryption.trim().ifBlank { "none" })
                    putOrRemove(user, "flow", draft.vlessFlow.trim())
                } else {
                    user.put("security", draft.vmessSecurity.trim().ifBlank { "auto" })
                    draft.vmessAlterId.trim().toIntOrNull()?.let { user.put("alterId", it) } ?: user.remove("alterId")
                }
                users.put(0, user)
                server.put("users", users)
                vnext.put(0, server)
                settings.put("vnext", vnext)
                result.put("settings", settings)
            }
            "trojan" -> {
                val servers = settings.optJSONArray("servers") ?: JSONArray()
                val server = servers.optJSONObject(0) ?: JSONObject()
                putOrRemove(server, "address", draft.address.trim())
                if (portNum != null) server.put("port", portNum) else server.remove("port")
                putOrRemove(server, "password", draft.password)
                servers.put(0, server)
                settings.put("servers", servers)
                result.put("settings", settings)
            }
            "shadowsocks" -> {
                val servers = settings.optJSONArray("servers") ?: JSONArray()
                val server = servers.optJSONObject(0) ?: JSONObject()
                putOrRemove(server, "address", draft.address.trim())
                if (portNum != null) server.put("port", portNum) else server.remove("port")
                putOrRemove(server, "method", draft.ssMethod.trim())
                putOrRemove(server, "password", draft.password)
                if (draft.ssUot || server.has("uot")) {
                    if (draft.ssUot) server.put("uot", true) else server.remove("uot")
                }
                servers.put(0, server)
                settings.put("servers", servers)
                result.put("settings", settings)
            }
            "naive" -> {
                putOrRemove(settings, "address", draft.address.trim())
                if (portNum != null) settings.put("port", portNum) else settings.remove("port")
                settings.put("user", draft.username)
                settings.put("pass", draft.password)
                settings.put("quic", draft.naiveQuic)
                settings.put("udpOverTcp", draft.naiveUdpOverTcp)
                putOrRemove(settings, "quicCongestionControl", draft.naiveCongestionControl.trim())
                val headers = parseHeaders(draft.naiveHeadersText)
                if (headers.length() > 0 || settings.has("headers")) {
                    settings.put("headers", headers)
                }
                result.put("settings", settings)
            }
            "socks", "http" -> {
                val servers = settings.optJSONArray("servers") ?: JSONArray()
                val server = servers.optJSONObject(0) ?: JSONObject()
                putOrRemove(server, "address", draft.address.trim())
                if (portNum != null) server.put("port", portNum) else server.remove("port")
                if (draft.username.isNotBlank() || draft.password.isNotBlank()) {
                    val users = server.optJSONArray("users") ?: JSONArray()
                    val user = users.optJSONObject(0) ?: JSONObject()
                    user.put("user", draft.username)
                    user.put("pass", draft.password)
                    users.put(0, user)
                    server.put("users", users)
                } else {
                    server.remove("users")
                }
                servers.put(0, server)
                settings.put("servers", servers)
                result.put("settings", settings)
            }
            "hysteria" -> {
                draft.hysteriaVersion.trim().toIntOrNull()?.let { settings.put("version", it) } ?: settings.remove("version")
                val servers = settings.optJSONArray("servers")
                if (servers != null || !settings.has("address")) {
                    val targetServers = servers ?: JSONArray()
                    val server = targetServers.optJSONObject(0) ?: JSONObject()
                    putOrRemove(server, "address", draft.address.trim())
                    if (portNum != null) server.put("port", portNum) else server.remove("port")
                    targetServers.put(0, server)
                    settings.put("servers", targetServers)
                } else {
                    putOrRemove(settings, "address", draft.address.trim())
                    if (portNum != null) settings.put("port", portNum) else settings.remove("port")
                }
                if (draft.hysteriaAuth.isNotBlank() && !result.optJSONObject("streamSettings")?.has("hysteriaSettings").orFalse()) {
                    settings.put("auth", draft.hysteriaAuth)
                } else if (settings.has("auth") && draft.hysteriaAuth.isBlank()) {
                    settings.remove("auth")
                }
                result.put("settings", settings)
            }
            "freedom" -> {
                putOrRemove(settings, "domainStrategy", draft.freedomDomainStrategy.trim())
                putOrRemove(settings, "redirect", draft.freedomRedirect.trim())
                if (settings.length() > 0) result.put("settings", settings) else result.remove("settings")
            }
            "blackhole" -> {
                val response = settings.optJSONObject("response") ?: JSONObject()
                response.put("type", draft.blackholeResponseType.trim().ifBlank { "none" })
                settings.put("response", response)
                result.put("settings", settings)
            }
        }
    }

    private fun serializeStreamSettings(
        result: JSONObject,
        draft: ProfileOutboundDraft,
        normalizedProtocol: String,
    ) {
        val stream = result.optJSONObject("streamSettings") ?: JSONObject()
        val previousNet = stream.optString("network").lowercase()
        val network = draft.network.trim().ifBlank { "tcp" }
        val normalizedNet = network.lowercase()
        val networkChanged = stream.has("network") && previousNet != normalizedNet

        if (networkChanged) {
            listOf(
                "tcpSettings",
                "rawSettings",
                "wsSettings",
                "grpcSettings",
                "httpupgradeSettings",
                "xhttpSettings",
                "splithttpSettings",
                "hysteriaSettings",
                "kcpSettings",
            ).forEach(stream::remove)
        }

        stream.put("network", network)

        when (normalizedNet) {
            "tcp", "raw" -> {
                val key = if (normalizedNet == "raw" && stream.has("rawSettings")) "rawSettings" else "tcpSettings"
                val tcp = stream.optJSONObject(key) ?: JSONObject()
                val headerType = draft.tcpHeaderType.trim().ifBlank { "none" }
                if (headerType == "none" && draft.tcpHttpHost.isBlank() && draft.tcpHttpPath.isBlank()) {
                    if (tcp.has("header")) {
                        tcp.remove("header")
                    }
                } else {
                    val header = tcp.optJSONObject("header") ?: JSONObject()
                    header.put("type", headerType)
                    if (headerType == "http") {
                        val request = header.optJSONObject("request") ?: JSONObject()
                        val paths = splitCommaValues(draft.tcpHttpPath)
                        if (paths.isNotEmpty()) {
                            request.put("path", JSONArray(paths))
                        } else {
                            request.remove("path")
                        }
                        val hosts = splitCommaValues(draft.tcpHttpHost)
                        val headers = request.optJSONObject("headers") ?: JSONObject()
                        if (hosts.isNotEmpty()) {
                            headers.put("Host", JSONArray(hosts))
                            request.put("headers", headers)
                        } else {
                            headers.remove("Host")
                            if (headers.length() == 0) request.remove("headers") else request.put("headers", headers)
                        }
                        if (request.length() > 0) header.put("request", request) else header.remove("request")
                    }
                    tcp.put("header", header)
                }
                if (tcp.length() > 0) stream.put(key, tcp) else stream.remove(key)
            }
            "ws", "websocket" -> {
                val ws = stream.optJSONObject("wsSettings") ?: JSONObject()
                putOrRemove(ws, "path", draft.transportPath.trim())
                if (ws.optJSONObject("headers")?.has("Host") == true && !ws.has("host")) {
                    val headers = ws.getJSONObject("headers")
                    putOrRemove(headers, "Host", draft.transportHost.trim())
                    if (headers.length() == 0) ws.remove("headers")
                } else {
                    putOrRemove(ws, "host", draft.transportHost.trim())
                }
                if (ws.length() > 0) stream.put("wsSettings", ws) else stream.remove("wsSettings")
            }
            "httpupgrade" -> {
                val hu = stream.optJSONObject("httpupgradeSettings") ?: JSONObject()
                putOrRemove(hu, "path", draft.transportPath.trim())
                putOrRemove(hu, "host", draft.transportHost.trim())
                if (hu.length() > 0) stream.put("httpupgradeSettings", hu) else stream.remove("httpupgradeSettings")
            }
            "xhttp", "splithttp" -> {
                val key = if (stream.has("splithttpSettings") && !stream.has("xhttpSettings")) "splithttpSettings" else "xhttpSettings"
                val xh = stream.optJSONObject(key) ?: JSONObject()
                putOrRemove(xh, "path", draft.transportPath.trim())
                putOrRemove(xh, "host", draft.transportHost.trim())
                putOrRemove(xh, "mode", draft.xhttpMode.trim())
                if (xh.length() > 0) stream.put(key, xh) else stream.remove(key)
            }
            "grpc", "gun" -> {
                val grpc = stream.optJSONObject("grpcSettings") ?: JSONObject()
                putOrRemove(grpc, "serviceName", draft.transportPath.trim())
                putOrRemove(grpc, "authority", draft.transportHost.trim())
                if (draft.grpcMultiMode) grpc.put("multiMode", true) else grpc.remove("multiMode")
                if (grpc.length() > 0) stream.put("grpcSettings", grpc) else stream.remove("grpcSettings")
            }
            "hysteria" -> {
                val hyst = stream.optJSONObject("hysteriaSettings") ?: JSONObject()
                draft.hysteriaVersion.trim().toIntOrNull()?.let { hyst.put("version", it) }
                putOrRemove(hyst, "auth", draft.hysteriaAuth.trim())
                if (hyst.length() > 0) stream.put("hysteriaSettings", hyst) else stream.remove("hysteriaSettings")
            }
        }

        val security = draft.security.trim().ifBlank { "none" }
        stream.put("security", security)
        when (security.lowercase()) {
            "none" -> {
                stream.remove("tlsSettings")
                stream.remove("realitySettings")
            }
            "tls" -> {
                stream.remove("realitySettings")
                val tls = stream.optJSONObject("tlsSettings") ?: JSONObject()
                putOrRemove(tls, "serverName", draft.sni.trim())
                putOrRemove(tls, "fingerprint", draft.fingerprint.trim())
                val alpnList = splitCommaValues(draft.alpn)
                if (alpnList.isNotEmpty()) tls.put("alpn", JSONArray(alpnList)) else tls.remove("alpn")
                if (draft.allowInsecure) tls.put("allowInsecure", true) else tls.remove("allowInsecure")
                if (tls.length() > 0 || normalizedProtocol == "naive") {
                    stream.put("tlsSettings", tls)
                } else {
                    stream.remove("tlsSettings")
                }
            }
            "reality" -> {
                stream.remove("tlsSettings")
                val reality = stream.optJSONObject("realitySettings") ?: JSONObject()
                putOrRemove(reality, "serverName", draft.sni.trim())
                putOrRemove(reality, "fingerprint", draft.fingerprint.trim())
                putOrRemove(reality, "publicKey", draft.realityPublicKey.trim())
                putOrRemove(reality, "shortId", draft.realityShortId.trim())
                putOrRemove(reality, "spiderX", draft.realitySpiderX.trim())
                putOrRemove(reality, "mldsa65Verify", draft.realityMldsa65Verify.trim())
                stream.put("realitySettings", reality)
            }
        }

        serializeSockopt(stream, draft)
        result.put("streamSettings", stream)
    }

    private fun serializeSockopt(stream: JSONObject, draft: ProfileOutboundDraft) {
        val sockopt = stream.optJSONObject("sockopt") ?: JSONObject()
        when (draft.tcpFastOpen) {
            null -> sockopt.remove("tcpFastOpen")
            true -> sockopt.put("tcpFastOpen", true)
            false -> sockopt.put("tcpFastOpen", false)
        }
        putOrRemove(sockopt, "dialerProxy", draft.dialerProxy.trim())
        if (sockopt.length() > 0) stream.put("sockopt", sockopt) else stream.remove("sockopt")
    }

    private fun serializeMux(result: JSONObject, draft: ProfileOutboundDraft) {
        val existing = result.optJSONObject("mux")
        val concurrency = draft.muxConcurrency.trim().toIntOrNull()
        val xudpConcurrency = draft.muxXudpConcurrency.trim().toIntOrNull()
        if (!draft.muxEnabled && existing == null && concurrency == null && xudpConcurrency == null) {
            return
        }
        val mux = existing ?: JSONObject()
        mux.put("enabled", draft.muxEnabled)
        if (concurrency != null) mux.put("concurrency", concurrency) else mux.remove("concurrency")
        if (xudpConcurrency != null) mux.put("xudpConcurrency", xudpConcurrency) else mux.remove("xudpConcurrency")
        if (!draft.muxEnabled && mux.length() == 1 && existing == null) {
            result.remove("mux")
        } else {
            result.put("mux", mux)
        }
    }

    private fun parseHeaders(text: String): JSONObject {
        val headers = JSONObject()
        text.lines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@forEach
            val sep = trimmed.indexOf(':')
            if (sep <= 0) return@forEach
            val key = trimmed.substring(0, sep).trim()
            val value = trimmed.substring(sep + 1).trim()
            if (key.isNotEmpty()) headers.put(key, value)
        }
        return headers
    }

    private fun splitCommaValues(text: String): List<String> =
        text.split(',', '\n').map(String::trim).filter(String::isNotEmpty)

    private fun joinJsonStringOrArray(value: Any?): String = when (value) {
        is JSONArray -> buildList {
            for (index in 0 until value.length()) {
                val item = value.optString(index).trim()
                if (item.isNotEmpty()) add(item)
            }
        }.joinToString(", ")
        is String -> value.trim()
        else -> ""
    }

    private fun optPort(obj: JSONObject, key: String): String {
        if (!obj.has(key) || obj.isNull(key)) return ""
        val value = obj.opt(key)
        return when (value) {
            is Number -> value.toInt().takeIf { it > 0 }?.toString().orEmpty()
            is String -> value.trim()
            else -> ""
        }
    }

    private fun optIntString(obj: JSONObject?, key: String): String {
        if (obj == null || !obj.has(key) || obj.isNull(key)) return ""
        return obj.opt(key)?.toString()?.trim().orEmpty()
    }

    private fun putOrRemove(target: JSONObject, key: String, value: String) {
        if (value.isNotEmpty()) target.put(key, value) else target.remove(key)
    }

    private fun Boolean?.orFalse(): Boolean = this == true
}
