package io.github.derundevu.yaxc.transfer

import android.content.Context
import androidx.room.withTransaction
import io.github.derundevu.yaxc.R
import io.github.derundevu.yaxc.Settings
import io.github.derundevu.yaxc.database.Link
import io.github.derundevu.yaxc.database.Profile
import io.github.derundevu.yaxc.database.YaxcDatabase
import io.github.derundevu.yaxc.helper.LiveRoutingHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal data class TransferGroup(val id: Long?, val name: String, val profileCount: Int)

internal data class TransferSelection(val groups: Set<Long?>, val settings: Boolean, val routing: Boolean)

internal data class TransferSummary(
    val groupsAdded: Int,
    val groupsUpdated: Int,
    val profilesAdded: Int,
    val profilesUpdated: Int,
    val settings: Boolean,
    val routing: Boolean,
) {
    fun encode(): String = JSONObject().put("groupsAdded", groupsAdded).put("groupsUpdated", groupsUpdated)
        .put("profilesAdded", profilesAdded).put("profilesUpdated", profilesUpdated)
        .put("settings", settings).put("routing", routing).toString()

    companion object {
        fun decode(encoded: String): TransferSummary {
            val json = DeviceTransferJson.parse(encoded, limit = 1_024).transferObject("transfer result")
            json.transferKeys("groupsAdded", "groupsUpdated", "profilesAdded", "profilesUpdated", "settings", "routing")
            return TransferSummary(
                json.transferValue("groupsAdded").transferInt("groupsAdded", 0..DeviceTransferJson.MAX_GROUPS),
                json.transferValue("groupsUpdated").transferInt("groupsUpdated", 0..DeviceTransferJson.MAX_GROUPS),
                json.transferValue("profilesAdded").transferInt("profilesAdded", 0..DeviceTransferJson.MAX_PROFILES),
                json.transferValue("profilesUpdated").transferInt("profilesUpdated", 0..DeviceTransferJson.MAX_PROFILES),
                json.transferBoolean("settings"), json.transferBoolean("routing"),
            )
        }
    }
}

internal class TransferRoutingDependencyException(message: String) : IllegalArgumentException(message)

internal class DeviceTransferStore(context: Context) {
    private val app = context.applicationContext

    suspend fun groups(): List<TransferGroup> = withContext(Dispatchers.IO) {
        val db = YaxcDatabase.ref(app)
        db.withTransaction {
            val counts = db.profileDao().transferCounts().associate { it.linkId to it.count }
            val manualName = app.getString(R.string.defaultManualGroupName)
            buildList {
                counts[null]?.takeIf { it > 0 }?.let { add(TransferGroup(null, manualName, it)) }
                db.linkDao().orderedLinks().forEach { link ->
                    add(TransferGroup(link.id, link.name.ifBlank { manualName }, counts[link.id] ?: 0))
                }
            }
        }
    }

    suspend fun export(selection: TransferSelection): String = withContext(Dispatchers.IO) {
        require(selection.groups.isNotEmpty() || selection.settings || selection.routing) { "Select something to send." }
        require(selection.groups.size <= DeviceTransferJson.MAX_GROUPS) { "Too many selected profile groups." }
        transferLock.withLock {
            val preferences = if (selection.settings || selection.routing) Settings.forDeviceTransfer(app) else null
            val settings = if (selection.settings) DeviceTransferPreferences.capture(checkNotNull(preferences)) else null
            val payload = if (selection.groups.isEmpty() && !selection.routing) {
                TransferPayload(emptyList(), settings, null)
            } else {
                val db = YaxcDatabase.ref(app)
                db.withTransaction {
                    val snapshot = Snapshot(db.linkDao().orderedLinks(), db.profileDao().transferProfiles())
                    require(selection.groups.filterNotNull().all(snapshot.linksById::containsKey)) {
                        "A selected profile group no longer exists. Choose your groups again."
                    }
                    val groups = buildList {
                        if (null in selection.groups) add(snapshot.exportGroup(null))
                        snapshot.links.forEach { if (it.id in selection.groups) add(snapshot.exportGroup(it)) }
                    }
                    val routing = if (selection.routing) {
                        val prefs = checkNotNull(preferences)
                        val plan = LiveRoutingHelper.decode(prefs.liveRoutingPlan)
                        val live = PortableLiveRouting.capture(plan, snapshot::exportReference)
                        DeviceTransferRouting.capture(prefs, db.configDao().get(), live)
                    } else null
                    TransferPayload(groups, settings, routing)
                }
            }
            payload.encode()
        }
    }

    suspend fun importPayload(payload: String): TransferSummary = withContext(Dispatchers.IO) {
        val transfer = TransferPayload.decode(payload)
        transferLock.withLock {
            // Once accepted, finish the Room commit and its corresponding preference edit together
            // even if the Activity that initiated the import is dismissed.
            withContext(NonCancellable) {
                val imported = if (transfer.groups.isNotEmpty() || transfer.routing != null) {
                    val db = YaxcDatabase.ref(app)
                    db.withTransaction { apply(db, transfer) }
                } else Applied(0, 0, 0, 0, null)
                if (transfer.settings != null || transfer.routing != null) {
                    Settings.forDeviceTransfer(app).editDeviceTransfer {
                        transfer.settings?.writeTo(this)
                        transfer.routing?.writeTo(this, checkNotNull(imported.livePlan))
                    }
                }
                TransferSummary(imported.groupsAdded, imported.groupsUpdated, imported.profilesAdded,
                    imported.profilesUpdated, transfer.settings != null, transfer.routing != null)
            }
        }
    }

    private suspend fun apply(db: YaxcDatabase, transfer: TransferPayload): Applied {
        val snapshot = Snapshot(db.linkDao().orderedLinks(), db.profileDao().transferProfiles())
        val merge = Merge(snapshot)
        transfer.groups.forEach(merge::stage)
        // Check every dependency against the planned state before inserting or updating anything.
        val resolved = transfer.routing?.live?.profiles?.let(merge::resolve)
        val existingConfig = if (transfer.routing != null) db.configDao().get() else null
        val newConfig = transfer.routing?.updateConfig(existingConfig)

        var groupsAdded = 0
        var groupsUpdated = 0
        var profilesAdded = 0
        var profilesUpdated = 0
        val linkDao = db.linkDao()
        val profileDao = db.profileDao()
        for (source in merge.links.values) {
            if (source.original == null) {
                source.id = linkDao.insertAtEnd(source.desired)
                groupsAdded++
            } else if (source.desired != source.original) {
                linkDao.update(source.desired)
                groupsUpdated++
            }
        }
        if (merge.insertions.isNotEmpty()) {
            var index = 0
            for (ordinal in 0..snapshot.profiles.size) {
                merge.insertions[ordinal]?.forEach { pending ->
                    val profile = checkNotNull(pending.desired)
                    pending.id = profileDao.insert(Profile(linkId = pending.source?.id, index = index++,
                        name = profile.name, config = profile.config))
                    profilesAdded++
                }
                if (ordinal < snapshot.profiles.size) {
                    val local = snapshot.profiles[ordinal]
                    if (local.index != index) profileDao.updateIndex(index, local.id)
                    index++
                }
            }
        }
        for (pending in merge.updates) {
            val current = checkNotNull(pending.original)
            val content = checkNotNull(pending.desired)
            profileDao.updateContent(current.id, current.linkId, content.name, content.config)
            profilesUpdated++
        }
        if (newConfig != null && newConfig != existingConfig) {
            if (existingConfig == null) db.configDao().insert(newConfig) else db.configDao().update(newConfig)
        }
        val livePlan = if (resolved != null) checkNotNull(transfer.routing).live.resolve(resolved.map { it.id }) else null
        return Applied(groupsAdded, groupsUpdated, profilesAdded, profilesUpdated, livePlan)
    }

    private data class Applied(val groupsAdded: Int, val groupsUpdated: Int, val profilesAdded: Int,
        val profilesUpdated: Int, val livePlan: String?)

    private companion object {
        val transferLock = Mutex()
    }
}

private data class TransferPayload(
    val groups: List<PortableGroup>, val settings: DeviceTransferPreferences?, val routing: DeviceTransferRouting?,
) {
    fun encode(): String {
        checkGroups(groups)
        val json = JSONObject().put("namespace", NAMESPACE).put("version", VERSION)
            .put("groups", JSONArray().apply { groups.forEach { put(it.encode()) } })
            .put("settings", settings?.encode() ?: JSONObject.NULL)
            .put("routing", routing?.encode() ?: JSONObject.NULL)
        return json.toString().also { DeviceTransferJson.checkSize(it) }
    }

    companion object {
        private const val NAMESPACE = "io.github.derundevu.yaxc.device-transfer"
        private const val VERSION = 1

        fun decode(raw: String): TransferPayload {
            val json = DeviceTransferJson.parse(raw).transferObject("transfer")
            json.transferKeys("namespace", "version", "groups", "settings", "routing")
            require(json.transferText("namespace", 128) == NAMESPACE) { "Not a yaxc device transfer." }
            json.transferValue("version").transferInt("transfer format version", VERSION..VERSION)
            val groups = json.transferValue("groups").transferArray("profile groups", DeviceTransferJson.MAX_GROUPS)
                .mapTransfer { PortableGroup.decode(it.transferObject("profile group")) }
            checkGroups(groups)
            val settings = json.transferValue("settings").let {
                if (it === JSONObject.NULL) null else DeviceTransferPreferences.decode(it.transferObject("settings"))
            }
            val routing = json.transferValue("routing").let {
                if (it === JSONObject.NULL) null else DeviceTransferRouting.decode(it.transferObject("routing"))
            }
            require(groups.isNotEmpty() || settings != null || routing != null) { "The transfer contains no data." }
            return TransferPayload(groups, settings, routing)
        }

        private fun checkGroups(groups: List<PortableGroup>) {
            require(groups.sumOf { it.profiles.size } <= DeviceTransferJson.MAX_PROFILES) { "Too many transfer profiles." }
            require(groups.map { it.key }.toSet().size == groups.size) { "Duplicate profile group identity." }
        }
    }
}

private class Snapshot(val links: List<Link>, val profiles: List<Profile>) {
    val linksById = links.associateBy(Link::id)
    val linksByKey = links.groupBy(Link::transferKey)
    val profilesByLink = profiles.groupBy(Profile::linkId)
    private val profilesById by lazy(LazyThreadSafetyMode.NONE) { profiles.associateBy(Profile::id) }
    private val portableProfiles = HashMap<Long, PortableProfile?>()
    private val exportReferences = HashMap<Long?, Map<Long, TransferProfileRef>>()

    fun profileContent(profile: Profile): PortableProfile? = portableProfiles.getOrPut(profile.id) {
        // Unrelated invalid local drafts may exist; a requested draft is reported rather than dropped.
        try { PortableProfile.capture(profile) } catch (_: Exception) { null }
    }

    fun exportGroup(link: Link?): PortableGroup {
        val source = link?.let(PortableSource::capture)
        require(source == null || linksByKey[source.key]?.size == 1) {
            "Two profile groups have the same subscription URL or manual group name. Make them distinct before sending."
        }
        val members = profilesByLink[link?.id].orEmpty()
        require(members.size <= DeviceTransferJson.MAX_PROFILES) { "Too many profiles in this group." }
        return PortableGroup(source, members.map { profile ->
            profileContent(profile) ?: throw IllegalArgumentException(
                "Profile ‘${profile.name}’ contains invalid or oversized configuration data. Check it before sending.",
            )
        })
    }

    fun exportReference(id: Long): TransferProfileRef {
        val profile = profilesById[id] ?: throw TransferRoutingDependencyException(
            "Live Routing refers to a removed profile. Edit Live Routing before sending.",
        )
        val group = profile.linkId?.let { linksById[it] }
        val groupKey = group?.transferKey()
        if (groupKey != null && linksByKey[groupKey]?.size != 1) {
            throw TransferRoutingDependencyException("A Live Routing profile is in an ambiguous profile group.")
        }
        val references = exportReferences.getOrPut(profile.linkId) {
            val occurrences = HashMap<Identity, Int>()
            buildMap {
                for (member in profilesByLink[profile.linkId].orEmpty()) {
                    val content = profileContent(member) ?: continue
                    put(member.id, occurrences.reference(groupKey, content))
                }
            }
        }
        return references[id] ?: throw TransferRoutingDependencyException(
            "Live Routing needs the profile ‘${profile.name}’, whose configuration cannot be sent.",
        )
    }
}

private data class Identity(val name: String, val digest: String)
private fun PortableProfile.identity() = Identity(name, digest)

private fun MutableMap<Identity, Int>.reference(source: String?, profile: PortableProfile): TransferProfileRef {
    val identity = profile.identity()
    val occurrence = getOrDefault(identity, 0)
    put(identity, occurrence + 1)
    return TransferProfileRef(source, profile.name, profile.digest, occurrence)
}

private class SourceChange(val original: Link?, val desired: Link) {
    var id: Long = original?.id ?: 0
}

private class ProfileSlot(
    val original: Profile?, val ordinal: Int, originalContent: () -> PortableProfile?, val source: SourceChange? = null,
) {
    private val oldContent by lazy(LazyThreadSafetyMode.NONE, originalContent)
    var desired: PortableProfile? = null
    var id: Long = original?.id ?: 0
    val content: PortableProfile? get() = desired ?: oldContent
}

private class Merge(private val snapshot: Snapshot) {
    val links = LinkedHashMap<String, SourceChange>()
    val insertions = HashMap<Int, MutableList<ProfileSlot>>()
    val updates = mutableListOf<ProfileSlot>()
    private val existing = snapshot.profiles.mapIndexed { index, profile ->
        ProfileSlot(profile, index, { snapshot.profileContent(profile) })
    }.groupBy { it.original?.linkId }
    private val transferred = HashMap<TransferProfileRef, ProfileSlot>()
    private val additionsByGroup = HashMap<String?, List<ProfileSlot>>()

    fun stage(group: PortableGroup) {
        val source = group.source?.let { portable ->
            val candidates = snapshot.linksByKey[portable.key].orEmpty()
            require(candidates.size <= 1) { "Several local profile groups match a group in the transfer." }
            val local = candidates.singleOrNull()
            SourceChange(local, portable.merge(local)).also { links[portable.key] = it }
        }
        val local = if (source == null || source.original != null) existing[source?.original?.id].orEmpty() else emptyList()
        val incoming = group.profiles
        val matches = matchProfiles(local, incoming, renewable = group.source?.address?.isNotEmpty() == true)
        val additions = mutableListOf<ProfileSlot>()
        val pending = mutableListOf<ProfileSlot>()
        val occurrences = HashMap<Identity, Int>()
        var anchor = -1
        incoming.forEachIndexed { index, profile ->
            val match = matches[index]
            val slot = if (match == null) {
                ProfileSlot(null, -1, { null }, source).also {
                    it.desired = profile
                    pending.add(it)
                    additions.add(it)
                }
            } else {
                // Use monotonically increasing anchors: keep the receiver's existing order even
                // if the sender reordered some of the same profiles.
                if (match.ordinal > anchor) {
                    anchor = match.ordinal
                    flush(pending, anchor)
                }
                val old = checkNotNull(match.content)
                if (profile.identity() != old.identity()) {
                    val current = checkNotNull(match.original)
                    val config = if (profile.digest == old.digest) current.config else profile.replacing(current)
                    match.desired = profile.copy(config = config)
                    updates.add(match)
                }
                match
            }
            transferred[occurrences.reference(group.key, profile)] = slot
        }
        flush(pending, local.lastOrNull()?.ordinal?.plus(1) ?: snapshot.profiles.size)
        additionsByGroup[group.key] = additions
    }

    private fun flush(pending: MutableList<ProfileSlot>, before: Int) {
        if (pending.isEmpty()) return
        insertions.getOrPut(before) { mutableListOf() }.addAll(pending)
        pending.clear()
    }

    fun resolve(references: List<TransferProfileRef>): List<ProfileSlot> {
        val indexes = HashMap<String?, ReferenceIndex>()
        val seen = HashSet<ProfileSlot>()
        return references.map { reference ->
            // If the group was sent, map its member occurrences to the exact merge decisions.
            val profile = transferred[reference] ?: indexes.getOrPut(reference.source) { buildIndex(reference) }.find(reference)
            if (profile == null || !seen.add(profile)) throw reference.missing()
            profile
        }
    }

    private fun buildIndex(reference: TransferProfileRef): ReferenceIndex {
        val groupId = if (reference.source == null) null else {
            val candidates = snapshot.linksByKey[reference.source].orEmpty()
            if (candidates.size != 1 && reference.source !in links) throw reference.missing()
            candidates.singleOrNull()?.id
        }
        val old = if (reference.source != null && groupId == null) emptyList() else existing[groupId].orEmpty()
        return ReferenceIndex(old + additionsByGroup[reference.source].orEmpty())
    }
}

private class ReferenceIndex(profiles: List<ProfileSlot>) {
    private val exact = HashMap<Identity, MutableList<ProfileSlot>>()
    private val byDigest = HashMap<String, MutableList<ProfileSlot>>()

    init {
        profiles.forEach { slot ->
            slot.content?.let { profile ->
                exact.getOrPut(profile.identity()) { mutableListOf() }.add(slot)
                byDigest.getOrPut(profile.digest) { mutableListOf() }.add(slot)
            }
        }
    }

    fun find(reference: TransferProfileRef): ProfileSlot? = exact[Identity(reference.name, reference.digest)]
        ?.getOrNull(reference.occurrence) ?: if (reference.occurrence == 0) byDigest[reference.digest]?.singleOrNull() else null
}

private fun matchProfiles(
    local: List<ProfileSlot>, incoming: List<PortableProfile>, renewable: Boolean,
): Array<ProfileSlot?> {
    val matches = arrayOfNulls<ProfileSlot>(incoming.size)
    if (local.isEmpty() || incoming.isEmpty()) return matches
    val exact = HashMap<Identity, ArrayDeque<ProfileSlot>>()
    for (slot in local) {
        val profile = slot.content ?: continue
        exact.getOrPut(profile.identity()) { ArrayDeque() }.addLast(slot)
    }
    val paired = HashSet<ProfileSlot>()
    incoming.forEachIndexed { index, profile ->
        exact[profile.identity()]?.removeFirstOrNull()?.let {
            matches[index] = it
            paired.add(it)
        }
    }
    if (!renewable) return matches

    fun matchUnique(key: (PortableProfile) -> String, wholeGroup: Boolean) {
        val previous = HashMap<String, MutableList<ProfileSlot>>()
        for (slot in local) {
            val profile = slot.content ?: continue
            if (wholeGroup || slot !in paired) previous.getOrPut(key(profile)) { mutableListOf() }.add(slot)
        }
        val sourceCounts = HashMap<String, Int>()
        incoming.forEachIndexed { index, profile ->
            if (wholeGroup || matches[index] == null) {
                val value = key(profile)
                sourceCounts[value] = sourceCounts.getOrDefault(value, 0) + 1
            }
        }
        incoming.forEachIndexed { index, profile ->
            if (matches[index] == null) {
                val value = key(profile)
                if (value.isNotBlank() && sourceCounts[value] == 1) {
                    val old = previous[value]?.singleOrNull()
                    if (old != null && paired.add(old)) matches[index] = old
                }
            }
        }
    }
    // Subscription updates prefer configuration identity; labels are only safe when unique on both sides.
    matchUnique(PortableProfile::digest, wholeGroup = false)
    matchUnique(PortableProfile::name, wholeGroup = true)
    return matches
}
