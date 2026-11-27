package io.github.derundevu.yaxc.transfer

import io.github.derundevu.yaxc.Settings
import io.github.derundevu.yaxc.database.Link
import io.github.derundevu.yaxc.database.Profile
import io.github.derundevu.yaxc.helper.HttpHelper
import io.github.derundevu.yaxc.helper.LiveRoutingHelper
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URISyntaxException

internal data class PortableSource(
    val name: String,
    val address: String,
    val type: Link.Type,
    val active: Boolean,
    val userAgent: String?,
    val refreshMinutes: Int?,
    val headers: Map<String, String>,
) {
    val key = sourceKey(address, name)

    fun merge(local: Link?): Link {
        val localHeaders = local?.customHeaders
        val mergedHeaders = if (headers.isEmpty()) localHeaders else {
            val configured = HttpHelper.parseHeaders(localHeaders).mapKeys { it.key.lowercase() }
            if (headers.all { (name, value) -> configured[name] == value }) localHeaders
            else (configured + headers).entries.joinToString("\n") { (name, value) -> "$name: $value" }
        }
        val cleartext = address.startsWith("http://", ignoreCase = true)
        require(!cleartext || local?.isActive != true || mergedHeaders == localHeaders) {
            "Pause the existing HTTP subscription before importing new credentials for it."
        }
        return (local ?: Link()).copy(
            name = name,
            address = local?.address ?: address,
            type = type,
            // Existing activation (and any selection) is local; new HTTP sources require review.
            isActive = local?.isActive ?: (active && !cleartext),
            userAgent = userAgent ?: local?.userAgent,
            refreshIntervalMinutes = refreshMinutes,
            customHeaders = mergedHeaders,
        )
    }

    fun encode(): JSONObject = JSONObject()
        .put("name", name).put("address", address).put("type", type.name).put("active", active)
        .put("userAgent", userAgent ?: JSONObject.NULL).put("refreshMinutes", refreshMinutes ?: JSONObject.NULL)
        .put("headers", JSONObject(headers))

    companion object {
        private val blockedHeaders = setOf(
            "x-hwid", "hwid", "x-device-os", "x-ver-os", "x-device-model", "x-device-id", "device-id",
            "x-install-id", "x-installation-id", "installation-id",
        )
        private val headerName = Regex("[!#$%&'*+.^_`|~0-9a-z-]+")

        fun capture(link: Link): PortableSource = decode(JSONObject()
            .put("name", link.name).put("address", link.address.trim()).put("type", link.type.name)
            .put("active", link.isActive).put("userAgent", link.userAgent ?: JSONObject.NULL)
            .put("refreshMinutes", link.refreshIntervalMinutes ?: JSONObject.NULL)
            .put("headers", JSONObject(HttpHelper.parseHeaders(link.customHeaders)
                .mapKeys { it.key.lowercase() }.filterKeys { it !in blockedHeaders })))

        fun decode(json: JSONObject): PortableSource {
            json.transferKeys("name", "address", "type", "active", "userAgent", "refreshMinutes", "headers")
            val name = json.transferText("name", DeviceTransferJson.MAX_NAME)
            val address = json.transferText("address")
            require(address.isEmpty() || subscriptionUri(address) != null) { "Invalid subscription URL in transfer." }
            val type = Link.Type.entries.firstOrNull { it.name == json.transferText("type", 32) }
                ?: throw IllegalArgumentException("Unknown profile group type.")
            val userAgent = json.transferValue("userAgent").let { value ->
                if (value === JSONObject.NULL) null else value.transferString("userAgent", DeviceTransferJson.MAX_TEXT)
                    .also { require(it.isNotBlank() && it.none(Char::isISOControl)) { "Invalid source user agent." } }
            }
            val interval = json.transferValue("refreshMinutes").let { value ->
                if (value === JSONObject.NULL) null else value.transferInt("refreshMinutes",
                    Settings.MIN_REFRESH_LINKS_INTERVAL_MINUTES..Int.MAX_VALUE)
            }
            val rawHeaders = json.transferValue("headers").transferObject("subscription headers")
            require(rawHeaders.length() <= 64) { "Too many subscription headers." }
            val headers = rawHeaders.keys().asSequence().associateWith { key ->
                require(key.length <= 128 && headerName.matches(key) && key !in blockedHeaders) {
                    "Invalid or device-specific subscription header."
                }
                rawHeaders.transferText(key).also { value ->
                    require(value.isNotBlank() && value.none { it != '\t' && it.isISOControl() }) {
                        "Invalid subscription header value."
                    }
                }
            }
            require(address.isNotEmpty() || (headers.isEmpty() && userAgent == null && interval == null)) {
                "A manual group cannot include subscription options."
            }
            return PortableSource(name, address, type, json.transferBoolean("active"), userAgent, interval, headers)
        }
    }
}

private fun subscriptionUri(address: String): URI? = try {
    URI(address).takeIf {
        (it.scheme.equals("http", true) || it.scheme.equals("https", true)) && !it.host.isNullOrBlank() &&
            (it.port == -1 || it.port in 1..65_535)
    }
} catch (_: URISyntaxException) {
    null
}

internal fun Link.transferKey(): String = sourceKey(address, name)

private fun sourceKey(address: String, name: String): String {
    val trimmedAddress = address.trim()
    if (trimmedAddress.isEmpty()) return "manual:$name"
    val uri = subscriptionUri(trimmedAddress)
    val normalized = if (uri == null) trimmedAddress else buildString {
        val scheme = uri.scheme.lowercase()
        append(scheme).append("://")
        uri.rawUserInfo?.let { append(it).append('@') }
        append(uri.host.lowercase())
        val standardPort = if (scheme == "https") 443 else 80
        if (uri.port != -1 && uri.port != standardPort) append(':').append(uri.port)
        append(uri.rawPath.ifEmpty { "/" })
        uri.rawQuery?.let { append('?').append(it) }
    }
    // Routing-only transfer does not expose a subscription URL or its embedded credential.
    return "remote:${LiveRoutingHelper.profileFingerprint(normalized)}"
}

private val remoteKey = Regex("remote:[0-9a-f]{64}")
private val profileDigest = Regex("[0-9a-f]{64}")

/** Local Room IDs never cross the wire. Occurrence distinguishes identical profiles in their group. */
internal data class TransferProfileRef(val source: String?, val name: String, val digest: String, val occurrence: Int) {
    fun encode(): JSONObject = JSONObject().put("source", source ?: JSONObject.NULL).put("name", name)
        .put("digest", digest).put("occurrence", occurrence)

    fun missing(): TransferRoutingDependencyException = TransferRoutingDependencyException(
        if (name.isBlank()) "A profile required by Live Routing was not found. Include its profile group and resend."
        else "Live Routing needs the profile ‘$name’. Include its profile group and resend.",
    )

    companion object {
        fun decode(json: JSONObject): TransferProfileRef {
            json.transferKeys("source", "name", "digest", "occurrence")
            val source = json.transferValue("source").let { value ->
                if (value === JSONObject.NULL) null else value.transferString("source", DeviceTransferJson.MAX_NAME + 7)
                    .also { require(it.startsWith("manual:") || remoteKey.matches(it)) { "Invalid profile group reference." } }
            }
            val name = json.transferText("name", DeviceTransferJson.MAX_NAME)
            val digest = json.transferText("digest", 64)
            require(profileDigest.matches(digest)) { "Invalid profile fingerprint." }
            val occurrence = json.transferValue("occurrence").transferInt("occurrence", 0 until DeviceTransferJson.MAX_PROFILES)
            return TransferProfileRef(source, name, digest, occurrence)
        }
    }
}

internal data class PortableProfile(val name: String, val config: String, val digest: String) {
    fun encode(): JSONObject = JSONObject().put("name", name).put("config", config)

    fun replacing(local: Profile): String {
        val existing = DeviceTransferJson.parse(local.config, DeviceTransferJson.MAX_CONFIG).transferObject("local profile")
        val inbounds = existing.optJSONArray("inbounds") ?: return config
        // The receiver's inbound settings are local. Keep them on an update, never send their credentials.
        return DeviceTransferJson.parse(config, DeviceTransferJson.MAX_CONFIG).transferObject("profile")
            .put("inbounds", inbounds).toString()
    }

    companion object {
        private val inboundSecrets = setOf(
            "accounts", "clients", "users", "password", "passwd", "pass", "username", "user", "token",
            "secretkey", "privatekey", "presharedkey", "accesskey", "keyfile", "key", "mldsa65seed", "shortids",
            "decryption", "psk",
        )

        fun capture(profile: Profile): PortableProfile = read(profile.name, profile.config, sending = true)

        fun decode(json: JSONObject): PortableProfile {
            json.transferKeys("name", "config")
            return read(json.transferText("name", DeviceTransferJson.MAX_NAME),
                json.transferText("config", DeviceTransferJson.MAX_CONFIG), sending = false)
        }

        private fun read(name: String, config: String, sending: Boolean): PortableProfile {
            DeviceTransferJson.checkSize(name, DeviceTransferJson.MAX_NAME)
            val root = DeviceTransferJson.parse(config, DeviceTransferJson.MAX_CONFIG).transferObject("profile config")
            var changed = root.remove("_yaxcLiveHealth") != null
            if (root.has("inbounds")) {
                val inbounds = root.transferValue("inbounds").transferArray("inbounds", DeviceTransferJson.MAX_ROUTES)
                for (i in 0 until inbounds.length()) {
                    if (redact(inbounds.get(i).transferObject("inbound"))) changed = true
                }
            }
            if (root.has("outbounds")) {
                val outbounds = root.transferValue("outbounds").transferArray("outbounds", DeviceTransferJson.MAX_ROUTES)
                for (i in 0 until outbounds.length()) outbounds.get(i).transferObject("outbound")
            }
            require(sending || !changed) { "A profile in the transfer contains local credentials or runtime state." }
            val portableConfig = if (changed) root.toString() else config
            // Runtime always supplies this installation's inbounds; they cannot identify a remote endpoint.
            root.remove("inbounds")
            val digest = LiveRoutingHelper.profileFingerprint(DeviceTransferJson.canonical(root))
            return PortableProfile(name, portableConfig, digest)
        }

        private fun redact(value: Any): Boolean {
            var changed = false
            when (value) {
                is JSONObject -> {
                    val keys = value.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val normalized = key.lowercase()
                        val authSecret = normalized == "auth" && value.opt(key) !in setOf("none", "noauth", "password")
                        if (normalized in inboundSecrets || authSecret) {
                            keys.remove()
                            changed = true
                        } else if (redact(value.get(key))) changed = true
                    }
                }
                is JSONArray -> for (i in 0 until value.length()) if (redact(value.get(i))) changed = true
            }
            return changed
        }
    }
}

internal data class PortableGroup(val source: PortableSource?, val profiles: List<PortableProfile>) {
    val key: String? get() = source?.key

    fun encode(): JSONObject = JSONObject().put("source", source?.encode() ?: JSONObject.NULL)
        .put("profiles", JSONArray().apply { profiles.forEach { put(it.encode()) } })

    companion object {
        fun decode(json: JSONObject): PortableGroup {
            json.transferKeys("source", "profiles")
            val source = json.transferValue("source").let {
                if (it === JSONObject.NULL) null else PortableSource.decode(it.transferObject("profile group"))
            }
            val profiles = json.transferValue("profiles").transferArray("profiles", DeviceTransferJson.MAX_PROFILES)
                .mapTransfer { PortableProfile.decode(it.transferObject("profile")) }
            return PortableGroup(source, profiles)
        }
    }
}
