package io.github.derundevu.yaxc.helper

import io.github.derundevu.yaxc.database.Profile
import org.json.JSONArray
import org.json.JSONObject

/** Runtime-only attribution. Never reads a saved routing plan or changes routing decisions. */
object ConnectionConfigHelper {
    const val PROFILE_METADATA_KEY = "_yaxcConnectionProfiles"

    fun ordinary(base: String, profile: Profile, packageUids: Map<String, Int>, username: String, password: String): String {
        val root = JSONObject(base)
        addUidAccounts(root, packageUids, username, password, needsApps = false)
        // Formatting/source-only edits must not turn an unchanged effective runtime into a reload.
        root.put(PROFILE_METADATA_KEY, profileMetadata(root.optJSONArray("outbounds") ?: JSONArray(), profile,
            LiveRoutingHelper.profileFingerprint(base)))
        return root.toString()
    }

    fun profileMetadata(outbounds: JSONArray, profile: Profile? = null, fingerprint: String? = null): JSONObject {
        val result = JSONObject()
        val identityFingerprint = fingerprint ?: profile?.let { LiveRoutingHelper.profileFingerprint(it.config) }
        for (index in 0 until outbounds.length()) {
            val outbound = outbounds.getJSONObject(index)
            val tag = outbound.optString("tag")
            // An untagged handler cannot be distinguished from another untagged handler by the dispatcher.
            if (tag.isEmpty()) continue
            val route = routeKind(outbound)
            result.put(tag, if (profile != null) profileIdentity(profile.id, checkNotNull(identityFingerprint), profile.name, route)
                else JSONObject().put("profileId", 0).put("route", route))
        }
        return result
    }

    fun profileIdentity(profileId: Long, fingerprint: String, name: String, route: String = "proxy"): JSONObject = JSONObject()
        .put("profileId", profileId)
        .put("profileName", name)
        .put("profileFingerprint", fingerprint)
        .put("route", route)

    fun routeKind(outbound: JSONObject): String = when (outbound.optString("protocol").lowercase()) {
        "blackhole", "block" -> "block"
        "freedom", "direct" -> "direct"
        "http", "socks", "shadowsocks", "vless", "vmess", "trojan", "hysteria", "masque", "wireguard" -> "proxy"
        else -> "unknown"
    }

    /** Display labels are captured for history but never require a core reload by themselves. */
    fun runtimeComparison(text: String): String {
        val root = JSONObject(text)
        val profiles = root.optJSONObject(PROFILE_METADATA_KEY) ?: return text
        profiles.keys().forEach { tag -> profiles.getJSONObject(tag).remove("profileName") }
        return root.toString()
    }

    /** Shared by ordinary VPN attribution and Live Routing's application matchers. */
    fun addUidAccounts(
        root: JSONObject, packageUids: Map<String, Int>, username: String, password: String, needsApps: Boolean,
    ) {
        require(!needsApps || (username.isNotBlank() && password.isNotBlank())) {
            "Application rules require SOCKS username/password authentication; configure SOCKS credentials first."
        }
        if (username.isBlank() || password.isBlank()) return
        val uids = packageUids.values.toSortedSet()
        require(uids.none { it < 0 }) { "Installed application UID snapshot contains an unavailable UID; refresh the application list." }
        var authenticatedSocks = false
        val inbounds = array(root, "inbounds")
        for (index in 0 until inbounds.length()) {
            val inbound = inbounds.get(index) as? JSONObject
                ?: throw IllegalArgumentException("Base inbound ${index + 1} must be an object.")
            if (optionalString(inbound, "protocol") != "socks") continue
            val settings = optionalObject(inbound, "settings") ?: continue
            if (optionalString(settings, "auth") != "password") continue
            val accounts = if (settings.has("accounts")) array(settings, "accounts") else JSONArray().apply {
                val users = array(settings, "users")
                for (userIndex in 0 until users.length()) put(users.get(userIndex))
            }
            val passwords = HashMap<String, String>()
            for (accountIndex in 0 until accounts.length()) {
                val account = accounts.get(accountIndex) as? JSONObject
                    ?: throw IllegalArgumentException("SOCKS account ${accountIndex + 1} must be an object.")
                val accountUser = string(account, "user")
                require(passwords.put(accountUser, string(account, "pass")) == null) {
                    "SOCKS inbound has duplicate account names; make each account unique before using live routing."
                }
            }
            require(passwords[username] == password) {
                "Base SOCKS account does not match the configured default credentials; regenerate the base config."
            }
            authenticatedSocks = true
            uids.forEach { uid ->
                val user = "yaxc.uid.$uid"
                val existing = passwords[user]
                require(existing == null || existing == password) { "SOCKS account '$user' conflicts with the managed application identity." }
                if (existing == null) accounts.put(JSONObject().put("user", user).put("pass", password))
            }
            if (uids.isNotEmpty()) settings.put("accounts", accounts)
        }
        require(!needsApps || authenticatedSocks) {
            "Application rules require an authenticated SOCKS inbound; no-auth and transparent inbounds cannot identify applications."
        }
        if (authenticatedSocks && uids.isNotEmpty()) {
            require(!root.has("_yaxcDefaultSocksUser") || root.optString("_yaxcDefaultSocksUser") == username) {
                "Base config uses reserved application-identity metadata."
            }
            root.put("_yaxcDefaultSocksUser", username)
        }
    }

    private fun optionalObject(value: JSONObject, key: String): JSONObject? {
        if (!value.has(key) || value.isNull(key)) return null
        return value.get(key) as? JSONObject ?: throw IllegalArgumentException("'$key' must be an object.")
    }

    private fun array(value: JSONObject, key: String): JSONArray {
        if (!value.has(key)) return JSONArray()
        return value.get(key) as? JSONArray ?: throw IllegalArgumentException("'$key' must be an array.")
    }

    private fun optionalString(value: JSONObject, key: String): String? {
        if (!value.has(key)) return null
        return value.get(key) as? String ?: throw IllegalArgumentException("'$key' must be a string.")
    }

    private fun string(value: JSONObject, key: String): String =
        optionalString(value, key) ?: throw IllegalArgumentException("Missing required '$key'.")
}
