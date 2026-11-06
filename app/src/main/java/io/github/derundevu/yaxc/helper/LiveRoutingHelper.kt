package io.github.derundevu.yaxc.helper

import android.util.JsonReader
import android.util.JsonToken
import io.github.derundevu.yaxc.Settings
import io.github.derundevu.yaxc.database.Profile
import org.json.JSONArray
import org.json.JSONObject
import java.io.StringReader
import java.math.BigDecimal
import java.net.IDN
import java.net.URI
import java.security.MessageDigest
import java.util.UUID

enum class ProxyGroupStrategy { Select, Random, RoundRobin, LeastPing }

data class ProxyGroup(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val profileIds: List<Long> = emptyList(),
    val strategy: ProxyGroupStrategy = ProxyGroupStrategy.Select,
    val selectedProfileId: Long? = null,
    val checkAvailability: Boolean = false,
)

data class AppDomainRule(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val enabled: Boolean = true,
    val packages: List<String> = emptyList(),
    val domains: List<String> = emptyList(),
    val target: String = "proxy",
)

data class LiveRoutingPlan(
    val groups: List<ProxyGroup> = emptyList(),
    val rules: List<AppDomainRule> = emptyList(),
    val defaultGroupId: String? = null,
) {
    val defaultGroup: ProxyGroup?
        get() = if (defaultGroupId == null) groups.firstOrNull() else groups.firstOrNull { it.id == defaultGroupId }
}

data class LiveRoutingRuntimeStatus(
    val running: Boolean = false,
    val groups: List<LiveRoutingGroupStatus> = emptyList(),
)

data class LiveRoutingGroupStatus(
    val id: String,
    val strategy: ProxyGroupStrategy,
    val selectedProfileId: Long?,
    val state: String,
    val activeProfileId: Long?,
    val eligibleProfileIds: List<Long>,
    val members: List<LiveRoutingMemberStatus>,
)

data class LiveRoutingMemberStatus(
    val profileId: Long,
    val fingerprint: String,
    val state: String,
    val lastCheckedAt: Long,
    val delayMs: Long,
)

/** Pure compilation: the caller supplies one coherent database/package snapshot. */
object LiveRoutingHelper {
    const val CONNECTION_PROFILE_ID = -1L

    private val builtInTargets = setOf("proxy", "direct", "block")
    private val packagePattern = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*")
    private val outboundProtocols = setOf(
        "block", "blackhole", "direct", "freedom", "http", "shadowsocks", "socks",
        "vless", "vmess", "trojan", "hysteria", "masque", "dns", "wireguard",
    )
    private val directProtocols = setOf("freedom", "direct")
    private val blockProtocols = setOf("blackhole", "block")
    private val proxyProtocols = outboundProtocols - directProtocols - blockProtocols - "dns"
    private val domainPrefixes = setOf("full", "domain", "regexp", "geosite", "ext")
    private val xhttpSettingNames = arrayOf("xhttpSettings", "splithttpSettings")
    private val planFields = setOf("groups", "rules", "defaultGroupId")
    private val groupFields = setOf("id", "name", "profileIds", "strategy", "selectedProfileId", "checkAvailability")
    private val ruleFields = setOf("id", "name", "enabled", "packages", "domains", "target")

    fun decode(raw: String): LiveRoutingPlan {
        val root = parseObject(raw, "Live routing plan")
        requireKeys(root, planFields, "Live routing plan")
        val groups = objectList(root, "groups").mapIndexed { index, value ->
            val label = "Group ${index + 1}"
            requireKeys(value, groupFields, label)
            val strategy = string(value, "strategy", "Select")
            ProxyGroup(
                id = string(value, "id"),
                name = string(value, "name", ""),
                profileIds = array(value, "profileIds").let { ids ->
                    (0 until ids.length()).map { long(ids.get(it), "$label profileIds[${it + 1}]") }
                },
                strategy = ProxyGroupStrategy.entries.firstOrNull { it.name == strategy }
                    ?: throw IllegalArgumentException("$label has unknown strategy '$strategy'."),
                selectedProfileId = if (!value.has("selectedProfileId") || value.isNull("selectedProfileId")) null
                    else long(value.get("selectedProfileId"), "$label selectedProfileId"),
                checkAvailability = boolean(value, "checkAvailability", false),
            )
        }
        val rules = objectList(root, "rules").mapIndexed { index, value ->
            val label = "Rule ${index + 1}"
            requireKeys(value, ruleFields, label)
            val enabled = if (!value.has("enabled")) true else value.get("enabled").let {
                require(it is Boolean) { "$label enabled must be a boolean." }
                it
            }
            AppDomainRule(
                id = string(value, "id"), name = string(value, "name", ""), enabled = enabled,
                packages = stringList(value, "packages"), domains = stringList(value, "domains"),
                target = string(value, "target", "proxy"),
            )
        }
        val defaultGroupId = if (!root.has("defaultGroupId") || root.isNull("defaultGroupId")) null
            else string(root, "defaultGroupId")
        return LiveRoutingPlan(groups, rules, defaultGroupId).also(::validate)
    }

    fun encode(plan: LiveRoutingPlan): String {
        validate(plan)
        return JSONObject()
            .put("defaultGroupId", plan.defaultGroupId ?: JSONObject.NULL)
            .put("groups", JSONArray().apply {
                plan.groups.forEach { group ->
                    put(JSONObject().put("id", group.id).put("name", group.name)
                        .put("profileIds", jsonArray(group.profileIds)).put("strategy", group.strategy.name)
                        .put("selectedProfileId", group.selectedProfileId ?: JSONObject.NULL)
                        .put("checkAvailability", group.checkAvailability))
                }
            })
            .put("rules", JSONArray().apply {
                plan.rules.forEach { rule ->
                    put(JSONObject().put("id", rule.id).put("name", rule.name).put("enabled", rule.enabled)
                        .put("packages", jsonArray(rule.packages)).put("domains", jsonArray(rule.domains))
                        .put("target", rule.target))
                }
            }).toString()
    }

    fun profileFingerprint(config: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(config.toByteArray(Charsets.UTF_8))
        val hex = "0123456789abcdef"
        return buildString(digest.size * 2) {
            for (byte in digest) {
                val value = byte.toInt() and 0xff
                append(hex[value ushr 4])
                append(hex[value and 0x0f])
            }
        }
    }

    fun decodeRuntimeStatus(raw: String): LiveRoutingRuntimeStatus {
        val root = parseObject(raw, "Live routing status")
        val running = boolean(root, "running")
        val groups = objectList(root, "groups").map { group ->
            val strategy = string(group, "strategy")
            val state = string(group, "state")
            require(state in setOf("checking", "available", "unavailable", "error")) { "Unknown group health state." }
            LiveRoutingGroupStatus(
                id = string(group, "id"),
                strategy = ProxyGroupStrategy.entries.firstOrNull { it.name == strategy }
                    ?: throw IllegalArgumentException("Unknown runtime group strategy '$strategy'."),
                selectedProfileId = nullableLong(group, "selectedProfileId"),
                state = state,
                activeProfileId = nullableLong(group, "activeProfileId"),
                eligibleProfileIds = array(group, "eligibleProfileIds").let { ids ->
                    (0 until ids.length()).map { long(ids.get(it), "eligibleProfileIds") }
                },
                members = objectList(group, "members").map { member ->
                    val memberState = string(member, "state")
                    require(memberState in setOf("checking", "available", "unavailable")) { "Unknown member health state." }
                    LiveRoutingMemberStatus(
                        profileId = long(member.get("profileId"), "profileId"),
                        fingerprint = string(member, "fingerprint"),
                        state = memberState,
                        lastCheckedAt = long(member.get("lastCheckedAt"), "lastCheckedAt"),
                        delayMs = long(member.get("delayMs"), "delayMs"),
                    )
                },
            )
        }
        require(running || groups.isEmpty()) { "Stopped live routing status must not contain groups." }
        require(groups.map { it.id }.distinct().size == groups.size) { "Duplicate runtime group ID." }
        return LiveRoutingRuntimeStatus(running, groups)
    }

    fun validate(plan: LiveRoutingPlan) {
        val groupIds = HashSet<String>()
        plan.groups.forEachIndexed { index, group ->
            val label = groupLabel(group, index)
            validId(group.id, label)
            require(group.id !in builtInTargets) { "$label ID '${group.id}' is reserved for a built-in target." }
            require(groupIds.add(group.id)) { "Duplicate group ID '${group.id}'." }
            require(group.profileIds.isNotEmpty()) { "$label must contain at least one profile." }
            require(group.profileIds.all { it > 0 }) { "$label profile IDs must be positive integers." }
            require(group.profileIds.distinct().size == group.profileIds.size) { "$label contains duplicate profiles." }
            require(group.selectedProfileId == null || group.selectedProfileId in group.profileIds) {
                "$label selected profile ${group.selectedProfileId} is not a member; choose a member again."
            }
            require(group.strategy != ProxyGroupStrategy.LeastPing || group.checkAvailability) {
                "$label LeastPing strategy requires availability checks."
            }
        }
        require(plan.defaultGroupId == null || plan.defaultGroupId in groupIds) {
            "Default group '${plan.defaultGroupId}' is missing; choose an existing default group."
        }
        val ruleIds = HashSet<String>()
        plan.rules.forEachIndexed { index, rule ->
            val label = ruleLabel(rule, index)
            validId(rule.id, label)
            require(ruleIds.add(rule.id)) { "Duplicate rule ID '${rule.id}'." }
            require(rule.target in builtInTargets || rule.target in groupIds) {
                "$label targets missing group '${rule.target}'; choose an existing target."
            }
            // Incomplete disabled drafts are persistent data, never catch-all native rules.
            if (!rule.enabled) return@forEachIndexed
            require(rule.packages.isNotEmpty() || rule.domains.isNotEmpty()) {
                "$label must match at least one application or domain."
            }
            rule.packages.forEach { name ->
                require(packagePattern.matches(name)) { "$label has invalid application package '$name'." }
            }
            rule.domains.forEach { domain -> normalizeDomain(domain, label) }
        }
    }

    fun compile(
        base: String,
        plan: LiveRoutingPlan,
        profiles: Map<Long, Profile>,
        packageUids: Map<String, Int>,
        socksUsername: String,
        socksPassword: String,
        pingAddress: String = "",
        healthIntervalSeconds: Int = Settings.DEFAULT_LIVE_ROUTING_HEALTH_INTERVAL_SECONDS,
    ): String {
        validate(plan)
        require(healthIntervalSeconds in
            Settings.MIN_LIVE_ROUTING_HEALTH_INTERVAL_SECONDS..Settings.MAX_LIVE_ROUTING_HEALTH_INTERVAL_SECONDS
        ) { "Availability check interval is outside the supported range." }
        require(plan.defaultGroup != null) {
            "Live routing needs a default proxy group; add a group before connecting."
        }
        val root = parseObject(base, "Base config")
        require(!root.has("_yaxcLiveHealth")) { "Base config uses reserved live-health metadata." }
        val healthGroups = JSONArray()
        if (plan.groups.any(ProxyGroup::checkAvailability)) {
            val url = try { URI(pingAddress) } catch (_: Exception) { null }
            require(url != null && url.scheme in setOf("http", "https") && !url.host.isNullOrEmpty() &&
                url.rawUserInfo == null && url.rawFragment == null) {
                "Availability checks need an HTTP(S) ping URL without credentials or a fragment; configure Ping Address in Settings."
            }
        }
        val outbounds = array(root, "outbounds")
        val baseOutbounds = (0 until outbounds.length()).map { index ->
            outbounds.get(index) as? JSONObject
                ?: throw IllegalArgumentException("Base outbound ${index + 1} must be an object.")
        }
        val routing = optionalObject(root, "routing") ?: JSONObject()
        val baseRules = array(routing, "rules")
        val baseBalancers = array(routing, "balancers")
        val existingTags = HashSet<String>()
        baseOutbounds.forEach { outbound ->
            val tag = optionalString(outbound, "tag") ?: return@forEach
            require(tag.isEmpty() || existingTags.add(tag)) { "Base config has duplicate outbound tag '$tag'." }
        }
        val balancerTags = HashSet<String>()
        for (index in 0 until baseBalancers.length()) {
            val balancer = baseBalancers.get(index) as? JSONObject
                ?: throw IllegalArgumentException("Base balancer ${index + 1} must be an object.")
            val tag = string(balancer, "tag")
            require(balancerTags.add(tag)) { "Base config has duplicate balancer tag '$tag'." }
            existingTags.add(tag)
        }
        for (index in 0 until baseRules.length()) {
            val rule = baseRules.get(index) as? JSONObject
                ?: throw IllegalArgumentException("Base routing rule ${index + 1} must be an object.")
            optionalString(rule, "ruleTag")?.let(existingTags::add)
        }
        plan.groups.forEachIndexed { index, group ->
            group.profileIds.forEach { id ->
                require(profiles.containsKey(id)) {
                    "${groupLabel(group, index)} references missing profile $id; restore the subscription or remove that member."
                }
            }
        }
        val activeRules = plan.rules.filter(AppDomainRule::enabled)
        require(routing.optString("script").isEmpty()) {
            "Live routing cannot be combined with a base routing script; use field-based base rules."
        }
        val appRules = activeRules.filter { it.packages.isNotEmpty() }
        ConnectionConfigHelper.addUidAccounts(root, packageUids, socksUsername, socksPassword, appRules.isNotEmpty())

        // Xray balancer selectors match prefixes, not exact tags. Keep our entire namespace
        // outside existing selectors, and terminate every member tag with a separator.
        val selectors = buildList {
            for (index in 0 until baseBalancers.length()) {
                addAll(nativeStringList(baseBalancers.getJSONObject(index), "selector"))
            }
        }
        require(plan.groups.isEmpty() || selectors.none(String::isEmpty)) {
            "Base balancer has an empty selector that would also capture live profiles; use explicit outbound prefixes."
        }
        var namespace = "yaxc.live."
        while (existingTags.any { it.startsWith(namespace) } ||
            selectors.any { it.isNotEmpty() && (namespace.startsWith(it) || it.startsWith(namespace)) }) {
            namespace = "_$namespace"
        }
        val targets = HashMap<String, Pair<String, String>>()
        val profileTargets = HashMap<Long, String>()
        val profileFingerprints = HashMap<Long, String>()
        val managedBalancers = JSONArray()
        val connectionProfiles = ConnectionConfigHelper.profileMetadata(outbounds)
        plan.groups.forEachIndexed { index, group ->
            // Keep every member loaded: selecting a member/strategy changes routing only,
            // not handler ownership or the retained-version budget of the running core.
            val members = group.profileIds.map { id ->
                profileTargets.getOrPut(id) {
                    val start = outbounds.length()
                    val target = cloneProfile(profiles.getValue(id), id, namespace, outbounds)
                    val fingerprint = profileFingerprints.getOrPut(id) { profileFingerprint(profiles.getValue(id).config) }
                    for (outboundIndex in start until outbounds.length()) {
                        val outbound = outbounds.getJSONObject(outboundIndex)
                        connectionProfiles.put(outbound.getString("tag"),
                            ConnectionConfigHelper.profileIdentity(id, fingerprint, profiles.getValue(id).name,
                                ConnectionConfigHelper.routeKind(outbound)))
                    }
                    target
                }
            }
            if (group.strategy == ProxyGroupStrategy.Select && !group.checkAvailability) {
                targets[group.id] = "outboundTag" to profileTargets.getValue(group.selectedProfileId ?: group.profileIds.first())
            } else {
                val tag = "${namespace}group.$index."
                val blockTag = "${namespace}health.$index.block."
                if (group.checkAvailability) {
                    outbounds.put(JSONObject().put("tag", blockTag).put("protocol", "blackhole"))
                    connectionProfiles.put(blockTag, JSONObject().put("profileId", 0).put("route", "block"))
                    val orderedIds = if (group.strategy == ProxyGroupStrategy.Select) {
                        val preferred = group.selectedProfileId ?: group.profileIds.first()
                        listOf(preferred) + group.profileIds.filterNot { it == preferred }
                    } else group.profileIds
                    healthGroups.put(JSONObject()
                        .put("id", group.id).put("strategy", group.strategy.name)
                        .put("balancerTag", tag).put("blockTag", blockTag)
                        .put("selectedProfileId", group.selectedProfileId ?: JSONObject.NULL)
                        .put("members", JSONArray().apply {
                            orderedIds.forEach { id ->
                                put(JSONObject().put("profileId", id).put("tag", profileTargets.getValue(id))
                                    .put("fingerprint", profileFingerprints.getOrPut(id) {
                                        profileFingerprint(profiles.getValue(id).config)
                                    }))
                            }
                        }))
                }
                managedBalancers.put(JSONObject().put("tag", tag)
                    .put("selector", jsonArray(if (group.checkAvailability) listOf(blockTag) else members))
                    .put("strategy", JSONObject().put("type", when (group.strategy) {
                        ProxyGroupStrategy.Select, ProxyGroupStrategy.Random, ProxyGroupStrategy.LeastPing -> "random"
                        ProxyGroupStrategy.RoundRobin -> "roundrobin"
                    })))
                targets[group.id] = "balancerTag" to tag
            }
        }
        val defaultTarget = targets.getValue(checkNotNull(plan.defaultGroup).id)
        val managedRules = JSONArray()
        activeRules.forEach { rule ->
            val target = targets[rule.target] ?: if (rule.target == "proxy") defaultTarget
                else "outboundTag" to resolveBuiltIn(rule.target, baseOutbounds)
            val native = JSONObject().put("type", "field").put("ruleTag", "${namespace}rule.${managedRules.length()}.")
                .put(target.first, target.second)
            if (rule.packages.isNotEmpty()) {
                val users = rule.packages.map { name ->
                    val uid = packageUids[name]
                    require(uid != null && uid >= 0) {
                        "Rule '${rule.name.ifBlank { rule.id }}' application '$name' is not installed or its UID is unavailable; remove it or reinstall it."
                    }
                    "yaxc.uid.$uid"
                }.distinct()
                native.put("user", jsonArray(users))
            }
            if (rule.domains.isNotEmpty()) {
                native.put("domain", jsonArray(rule.domains.map { normalizeDomain(it, "Rule '${rule.name.ifBlank { rule.id }}'") }.distinct()))
            }
            // Fields in one Xray rule are ANDed; values within either array are ORed.
            managedRules.put(native)
        }
        // Live rules precede Core Routing; its direct/block rules still precede the
        // default-group fallback. Replace proxy destinations, not match conditions.
        val baseProxyTag = baseOutbounds.firstOrNull {
            optionalString(it, "tag")?.equals("proxy", ignoreCase = true) == true
        }?.getString("tag") ?: baseOutbounds.firstOrNull {
            optionalString(it, "protocol")?.lowercase() in proxyProtocols && !optionalString(it, "tag").isNullOrEmpty()
        }?.getString("tag")
        for (index in 0 until baseRules.length()) {
            val rule = baseRules.getJSONObject(index)
            val tag = optionalString(rule, "outboundTag")
            // Xray gives outboundTag precedence when both destinations are present.
            // Clear both before installing a group balancer, or it would be ignored.
            if (tag?.equals("proxy", ignoreCase = true) == true || (baseProxyTag != null && tag == baseProxyTag)) {
                rule.remove("outboundTag")
                rule.remove("balancerTag")
                rule.put(defaultTarget.first, defaultTarget.second)
            }
            managedRules.put(rule)
        }
        managedRules.put(JSONObject().put("type", "field").put("ruleTag", "${namespace}default.")
            .put("network", "tcp,udp").put(defaultTarget.first, defaultTarget.second))
        routing.put("rules", managedRules)
        if (managedBalancers.length() > 0) {
            for (index in 0 until baseBalancers.length()) managedBalancers.put(baseBalancers.get(index))
            routing.put("balancers", managedBalancers)
        }
        if (outbounds.length() > baseOutbounds.size) root.put("outbounds", outbounds)
        if (managedRules.length() > 0 || managedBalancers.length() > 0) root.put("routing", routing)
        if (healthGroups.length() > 0) {
            root.put("_yaxcLiveHealth", JSONObject().put("url", pingAddress)
                .put("intervalSeconds", healthIntervalSeconds).put("groups", healthGroups))
        }
        root.put(ConnectionConfigHelper.PROFILE_METADATA_KEY, connectionProfiles)
        return root.toString()
    }

    private fun cloneProfile(profile: Profile, id: Long, namespace: String, destination: JSONArray): String {
        val label = "Profile $id${profile.name.takeIf(String::isNotBlank)?.let { " ('$it')" } ?: ""}"
        val config = parseObject(profile.config, label)
        require(optionalObject(config, "reverse")?.length().let { it == null || it == 0 }) {
            "$label uses reverse routing, which cannot be isolated as an outbound group member."
        }
        require(optionalObject(config, "transport")?.length().let { it == null || it == 0 }) {
            "$label uses global transport settings; move them into each outbound's streamSettings."
        }
        val source = array(config, "outbounds")
        require(source.length() > 0) { "$label has no outbounds; choose a complete Xray profile." }
        val tags = HashMap<String, String>()
        val objects = (0 until source.length()).map { index ->
            val outbound = source.get(index) as? JSONObject
                ?: throw IllegalArgumentException("$label outbound ${index + 1} must be an object.")
            val protocol = string(outbound, "protocol").lowercase()
            require(protocol in outboundProtocols) {
                "$label outbound ${index + 1} uses unsupported protocol '$protocol'; inbound-dependent/unknown outbound graphs cannot be grouped."
            }
            val oldTag = optionalString(outbound, "tag")
            val newTag = "${namespace}profile.$id.outbound.$index."
            if (!oldTag.isNullOrEmpty()) {
                require(tags.put(oldTag, newTag) == null) { "$label has duplicate outbound tag '$oldTag'." }
            }
            outbound.put("tag", newTag)
            outbound
        }
        val edges = Array(objects.size) { mutableSetOf<Int>() }
        val indices = objects.mapIndexed { index, outbound -> outbound.getString("tag") to index }.toMap()
        objects.forEachIndexed { index, outbound ->
            rewriteReferences(outbound, "$label outbound ${index + 1}") { oldTag, path ->
                val newTag = tags[oldTag] ?: throw IllegalArgumentException(
                    "$path references missing outbound '$oldTag'; include the complete outbound chain in $label.",
                )
                edges[index].add(indices.getValue(newTag))
                newTag
            }
        }
        val states = IntArray(objects.size)
        fun visit(index: Int) {
            require(states[index] != 1) { "$label has a cyclic outbound chain at outbound ${index + 1}; remove the proxy loop." }
            if (states[index] == 2) return
            states[index] = 1
            edges[index].forEach(::visit)
            states[index] = 2
        }
        objects.indices.forEach(::visit)
        objects.forEach(destination::put)
        return objects.first().getString("tag")
    }

    private fun rewriteReferences(outbound: JSONObject, path: String, resolve: (String, String) -> String) {
        require(!outbound.has("proxySettings") || outbound.isNull("proxySettings")) {
            "$path.proxySettings is no longer supported by Xray; replace its tag with streamSettings.sockopt.dialerProxy in this profile."
        }
        if (outbound.getString("protocol").equals("vless", ignoreCase = true)) {
            optionalObject(outbound, "settings")?.let { settings ->
                rejectReverse(settings, "$path.settings")
                objectList(settings, "vnext").forEachIndexed { serverIndex, server ->
                    objectList(server, "users").forEachIndexed { userIndex, user ->
                        rejectReverse(user, "$path.settings.vnext[$serverIndex].users[$userIndex]")
                    }
                }
            }
        }
        optionalObject(outbound, "streamSettings")?.let {
            rewriteStreamReferences(it, "$path.streamSettings", resolve)
        }
    }

    private fun rejectReverse(settings: JSONObject, path: String) {
        require(!settings.has("reverse") || settings.isNull("reverse")) {
            "$path.reverse needs routing/inbound state outside the outbound graph and is not supported in a proxy group."
        }
    }

    private fun rewriteStreamReferences(stream: JSONObject, path: String, resolve: (String, String) -> String) {
        optionalObject(stream, "sockopt")?.let { sockopt ->
            if (sockopt.has("dialerProxy") && !sockopt.isNull("dialerProxy")) {
                val tag = string(sockopt, "dialerProxy")
                if (tag.isNotEmpty()) sockopt.put("dialerProxy", resolve(tag, "$path.sockopt.dialerProxy"))
            }
        }
        // Only these fields are nested StreamConfig objects. Arbitrary HTTP headers or
        // provider metadata with tag-like names must remain byte-for-byte values.
        for (key in xhttpSettingNames) {
            optionalObject(stream, key)?.let { xhttp ->
                optionalObject(xhttp, "downloadSettings")?.let {
                    rewriteStreamReferences(it, "$path.$key.downloadSettings", resolve)
                }
                optionalObject(xhttp, "extra")?.let { extra ->
                    optionalObject(extra, "downloadSettings")?.let {
                        rewriteStreamReferences(it, "$path.$key.extra.downloadSettings", resolve)
                    }
                }
            }
        }
    }

    private fun resolveBuiltIn(target: String, outbounds: List<JSONObject>): String {
        require(target in builtInTargets) { "Missing routing target '$target'." }
        outbounds.firstOrNull { optionalString(it, "tag") == target }?.let { return it.getString("tag") }
        outbounds.firstOrNull { optionalString(it, "tag")?.equals(target, ignoreCase = true) == true }
            ?.let { return it.getString("tag") }
        val protocols = when (target) {
            "direct" -> directProtocols
            "block" -> blockProtocols
            else -> proxyProtocols
        }
        outbounds.firstOrNull { optionalString(it, "protocol")?.lowercase() in protocols && !optionalString(it, "tag").isNullOrEmpty() }
            ?.let { return it.getString("tag") }
        throw IllegalArgumentException("Base config has no tagged '$target' outbound; add that outbound before using this routing target.")
    }

    private fun normalizeDomain(raw: String, label: String): String {
        val value = raw.trim()
        require(value.isNotEmpty() && value.none { it.isISOControl() }) { "$label has an empty or multiline domain matcher." }
        val prefix = value.substringBefore(':', "")
        if (prefix.isEmpty()) {
            return "domain:${validDomain(value, label)}"
        }
        val content = value.substringAfter(':')
        require(prefix in domainPrefixes && content.isNotBlank()) {
            "$label has unsupported domain matcher '$value'; use a domain or full:/domain:/regexp:/geosite:/ext:."
        }
        when (prefix) {
            "full", "domain" -> return "$prefix:${validDomain(content, label)}"
            "geosite" -> require(content.none(Char::isWhitespace)) { "$label geosite matcher must not contain whitespace." }
            "ext" -> require(content.substringBefore(':').isNotBlank() && content.contains(':') &&
                content.substringAfter(':').isNotBlank() && content.none(Char::isWhitespace)) {
                "$label external matcher must be ext:<file>:<tag>."
            }
        }
        return value
    }

    private fun validDomain(value: String, label: String): String {
        val ascii = try { IDN.toASCII(value, IDN.USE_STD3_ASCII_RULES) } catch (_: IllegalArgumentException) { "" }
        require(ascii.isNotEmpty() && ascii.length <= 253 && ascii.removeSuffix(".").split('.').all { it.isNotEmpty() && it.length <= 63 }) {
            "$label has invalid domain '$value'; enter a hostname, not a URL or wildcard."
        }
        return ascii.lowercase()
    }

    private fun validId(id: String, label: String) {
        require(id.isNotBlank() && id == id.trim() && id.none(Char::isISOControl)) { "$label must have a nonblank, stable ID." }
    }

    private fun groupLabel(group: ProxyGroup, index: Int) = "Group '${group.name.ifBlank { "#${index + 1}" }}'"
    private fun ruleLabel(rule: AppDomainRule, index: Int) = "Rule '${rule.name.ifBlank { "#${index + 1}" }}'"
    private fun jsonArray(values: Iterable<*>): JSONArray = JSONArray().apply { values.forEach(::put) }

    private fun requireKeys(value: JSONObject, keys: Set<String>, label: String) {
        value.keys().forEach { require(it in keys) { "$label contains unsupported field '$it'; the saved plan was not changed." } }
    }

    private fun optionalObject(value: JSONObject, key: String): JSONObject? {
        if (!value.has(key) || value.isNull(key)) return null
        return value.get(key) as? JSONObject ?: throw IllegalArgumentException("'$key' must be an object.")
    }

    private fun array(value: JSONObject, key: String): JSONArray {
        if (!value.has(key)) return JSONArray()
        return value.get(key) as? JSONArray ?: throw IllegalArgumentException("'$key' must be an array.")
    }

    private fun objectList(value: JSONObject, key: String): List<JSONObject> {
        val items = array(value, key)
        return (0 until items.length()).map {
            items.get(it) as? JSONObject ?: throw IllegalArgumentException("$key[${it + 1}] must be an object.")
        }
    }

    /** Existing Xray StringList fields also accept a comma-separated scalar. */
    private fun nativeStringList(value: JSONObject, key: String): List<String> =
        (value.opt(key) as? String)?.split(',') ?: stringList(value, key)

    private fun stringList(value: JSONObject, key: String): List<String> {
        val items = array(value, key)
        return (0 until items.length()).map {
            items.get(it) as? String ?: throw IllegalArgumentException("$key[${it + 1}] must be a string.")
        }
    }

    private fun optionalString(value: JSONObject, key: String): String? {
        if (!value.has(key)) return null
        return value.get(key) as? String ?: throw IllegalArgumentException("'$key' must be a string.")
    }

    private fun string(value: JSONObject, key: String, default: String? = null): String =
        optionalString(value, key) ?: default ?: throw IllegalArgumentException("Missing required '$key'.")

    private fun boolean(value: JSONObject, key: String, default: Boolean? = null): Boolean {
        if (!value.has(key) && default != null) return default
        return value.get(key).let {
            require(it is Boolean) { "'$key' must be a boolean." }
            it
        }
    }

    private fun nullableLong(value: JSONObject, key: String): Long? =
        if (value.isNull(key)) null else long(value.get(key), key)

    private fun long(value: Any, label: String): Long {
        require(value is Long) { "$label must be an exact 64-bit integer, not a string or decimal." }
        return value
    }

    /** Android's org.json accepts comments, duplicate keys and coercions; persistence must not. */
    private fun parseObject(raw: String, label: String): JSONObject {
        try {
            JsonReader(StringReader(raw)).use { reader ->
                reader.isLenient = false
                val value = readJson(reader, 0)
                require(reader.peek() == JsonToken.END_DOCUMENT) { "Unexpected content after JSON." }
                return value as? JSONObject ?: throw IllegalArgumentException("Expected a JSON object.")
            }
        } catch (error: Exception) {
            throw IllegalArgumentException("$label is invalid JSON: ${error.message}", error)
        }
    }

    private fun readJson(reader: JsonReader, depth: Int): Any {
        require(depth <= 128) { "JSON nesting is too deep." }
        return when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> JSONObject().apply {
                reader.beginObject()
                while (reader.hasNext()) {
                    val key = reader.nextName()
                    require(!has(key)) { "Duplicate JSON field '$key'." }
                    put(key, readJson(reader, depth + 1))
                }
                reader.endObject()
            }
            JsonToken.BEGIN_ARRAY -> JSONArray().apply {
                reader.beginArray()
                while (reader.hasNext()) put(readJson(reader, depth + 1))
                reader.endArray()
            }
            JsonToken.STRING -> reader.nextString()
            JsonToken.BOOLEAN -> reader.nextBoolean()
            JsonToken.NULL -> { reader.nextNull(); JSONObject.NULL }
            JsonToken.NUMBER -> reader.nextString().let { it.toLongOrNull() ?: BigDecimal(it) }
            else -> throw IllegalArgumentException("Expected a JSON value.")
        }
    }
}
