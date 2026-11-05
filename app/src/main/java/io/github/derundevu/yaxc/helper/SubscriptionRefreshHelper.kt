package io.github.derundevu.yaxc.helper

import android.content.Context
import android.util.JsonReader
import android.util.JsonToken
import androidx.room.withTransaction
import io.github.derundevu.yaxc.R
import io.github.derundevu.yaxc.Settings
import io.github.derundevu.yaxc.database.Link
import io.github.derundevu.yaxc.database.Profile
import io.github.derundevu.yaxc.database.YaxcDatabase
import io.github.derundevu.yaxc.dto.SubscriptionMetadata
import io.github.derundevu.yaxc.service.TProxyService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.StringReader
import java.math.BigDecimal
import java.util.WeakHashMap

class SubscriptionRefreshHelper(
    private val context: Context,
    private val settings: Settings = Settings(context.applicationContext),
) {
    data class ParsedLinkSource(
        val type: Link.Type,
        val profiles: List<Profile>,
        val title: String? = null,
        val metadata: SubscriptionMetadata? = null,
        internal val sourceSnapshot: Link? = null,
        internal val requestToken: Any? = null,
    )

    data class SourceChanges(
        val sourceId: Long,
        val sourceName: String,
        val added: List<String>,
        val removed: List<String>,
        val modified: List<String>,
    )

    data class RefreshResult(
        val attempted: Int,
        val refreshed: Int,
        val failed: Int,
        val changes: List<SourceChanges> = emptyList(),
    )

    companion object {
        // Shared across helpers, but not across databases or deleted test/application instances.
        private val requests = WeakHashMap<YaxcDatabase, MutableMap<Long, Any>>()
    }

    private val database = YaxcDatabase.ref(context.applicationContext)
    private val linkDao = database.linkDao()
    private val profileDao = database.profileDao()

    suspend fun refreshDueLinks(now: Long = System.currentTimeMillis()): RefreshResult {
        return withContext(Dispatchers.IO) {
            val links = linkDao.activeLinks()
                .filter { it.address.isNotBlank() }
                .filter { isRefreshDue(it, now) }
            refreshLinks(links, now, collectChanges = false)
        }
    }

    suspend fun refreshLinks(linkId: Long? = null): RefreshResult {
        return withContext(Dispatchers.IO) {
            val links = linkDao.activeLinks()
                .filter { it.address.isNotBlank() }
                .filter { linkId == null || it.id == linkId }
            refreshLinks(links, System.currentTimeMillis(), collectChanges = true)
        }
    }

    suspend fun resolveProfiles(link: Link): ParsedLinkSource {
        val source = link.copy()
        val token = if (source.id == 0L) null else Any().also { token ->
            synchronized(requests) {
                requests.getOrPut(database) { mutableMapOf() }[source.id] = token
            }
        }
        try {
            return withContext(Dispatchers.IO) {
                val response = HttpHelper.fetch(
                    link = source.address,
                    userAgent = HttpHelper.resolveSubscriptionUserAgent(settings, source.userAgent),
                    headers = HttpHelper.buildSubscriptionHeaders(
                        settings = settings,
                        customHeaders = source.customHeaders,
                        overrideXHwid = source.xHwid,
                    ),
                )
                val detected = detectProfiles(source, normalizeBody(response.body))
                val metadata = HttpHelper.extractSubscriptionMetadata(response.headers)
                ParsedLinkSource(
                    type = detected.first,
                    profiles = detected.second,
                    title = metadata?.profileTitle ?: HttpHelper.extractSubscriptionTitle(response.headers),
                    metadata = metadata,
                    sourceSnapshot = source,
                    requestToken = token,
                ).also {
                    check(it.profiles.isNotEmpty()) { "No profiles detected" }
                }
            }
        } catch (error: Throwable) {
            releaseRequest(source.id, token)
            throw error
        }
    }

    suspend fun saveResolvedLink(
        link: Link,
        parsed: ParsedLinkSource,
        now: Long = System.currentTimeMillis(),
    ) {
        applyResolvedLink(link, parsed, now, notifyRuntime = true)
    }

    private suspend fun applyResolvedLink(
        link: Link,
        parsed: ParsedLinkSource,
        now: Long,
        notifyRuntime: Boolean,
        collectChanges: Boolean = false,
    ): SourceChanges? {
        try {
            return withContext(Dispatchers.IO) {
                check(parsed.profiles.isNotEmpty()) { "No profiles detected" }
                val source = parsed.sourceSnapshot ?: link.copy()
                val removedIds = mutableSetOf<Long>()
                var changes: SourceChanges? = null
                val saved = database.withTransaction {
                    currentCoroutineContext().ensureActive()
                    check(link == source) { "Source changed while refreshing" }
                    if (source.id != 0L) {
                        check(linkDao.find(source.id) == source) { "Source changed while refreshing" }
                        if (parsed.requestToken != null) {
                            check(synchronized(requests) {
                                requests[database]?.get(source.id) === parsed.requestToken
                            }) { "A newer refresh has started" }
                        }
                    }
                    val existing = if (source.id == 0L) emptyList() else profileDao.linkProfiles(source.id)
                    val updated = source.copy(
                        type = parsed.type,
                        name = parsed.title?.takeIf { it.isNotBlank() } ?: source.name,
                        subscriptionMetadata = parsed.metadata?.toJsonString(),
                        lastRefreshedAt = now,
                    )
                    if (updated.id == 0L) {
                        updated.id = linkDao.insertAtEnd(updated)
                    } else {
                        linkDao.update(updated)
                    }
                    val configs = HashMap<String, Any?>()
                    val oldContent = profileContents(existing, configs)
                    val newContent = profileContents(parsed.profiles, configs)
                    if (collectChanges) changes = sourceChanges(updated, oldContent, newContent)
                    manageProfiles(
                        updated, existing, parsed.profiles,
                        matchProfiles(oldContent, newContent, safeIdentity = true), removedIds,
                    )
                    updated
                }
                // A rollback must not clear selection or mutate the caller's unsaved source.
                withContext(NonCancellable + Dispatchers.Main) {
                    if (settings.selectedProfile in removedIds) settings.selectedProfile = 0L
                    link.id = saved.id
                    link.position = saved.position
                    link.type = saved.type
                    link.name = saved.name
                    link.subscriptionMetadata = saved.subscriptionMetadata
                    link.lastRefreshedAt = saved.lastRefreshedAt
                    if (notifyRuntime) reconcileRuntime()
                }
                changes
            }
        } finally {
            releaseRequest(parsed.sourceSnapshot?.id ?: link.id, parsed.requestToken)
        }
    }

    private fun releaseRequest(id: Long, token: Any?) {
        if (token == null) return
        synchronized(requests) {
            requests[database]?.let { active ->
                if (active[id] === token) active.remove(id)
                if (active.isEmpty()) requests.remove(database)
            }
        }
    }

    private suspend fun refreshLinks(
        links: List<Link>,
        now: Long,
        collectChanges: Boolean,
    ): RefreshResult {
        var refreshed = 0
        var failed = 0
        val changes = if (collectChanges) mutableListOf<SourceChanges>() else null
        var applyAttempted = false
        try {
            links.forEach { link ->
                currentCoroutineContext().ensureActive()
                try {
                    val parsed = resolveProfiles(link)
                    // A cancellation can arrive after Room commits but before the caller resumes.
                    // Reconcile committed state once for the batch, including partial cancellation.
                    applyAttempted = true
                    val sourceChanges = applyResolvedLink(
                        link, parsed, now, notifyRuntime = false, collectChanges = collectChanges,
                    )
                    if (sourceChanges != null) changes?.add(sourceChanges)
                    refreshed += 1
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    currentCoroutineContext().ensureActive()
                    failed += 1
                }
            }
        } finally {
            if (applyAttempted) reconcileRuntime()
        }
        return RefreshResult(
            attempted = links.size,
            refreshed = refreshed,
            failed = failed,
            changes = changes ?: emptyList(),
        )
    }

    private data class ProfileContent(val name: String, val config: Any?)

    private fun profileContents(
        profiles: List<Profile>,
        configs: MutableMap<String, Any?>,
    ): List<ProfileContent> {
        return profiles.map { profile ->
            ProfileContent(profile.name, configs.getOrPut(profile.config) {
                JsonReader(StringReader(profile.config)).use { reader ->
                    readSemanticJson(reader).also {
                        check(reader.peek() == JsonToken.END_DOCUMENT) { "Unexpected content after JSON" }
                    }
                }
            })
        }
    }

    // Each new occurrence points to an old occurrence, or -1 when it needs a new identity.
    private fun matchProfiles(
        old: List<ProfileContent>,
        new: List<ProfileContent>,
        safeIdentity: Boolean,
    ): IntArray {
        val matchedOld = BooleanArray(old.size)
        val matches = IntArray(new.size) { -1 }

        fun <K> match(
            key: (ProfileContent) -> K,
            unique: Boolean = false,
            originallyUnique: Boolean = false,
        ) {
            val candidates = HashMap<K, ArrayDeque<Int>>()
            old.forEachIndexed { index, profile ->
                if (!matchedOld[index]) candidates.getOrPut(key(profile)) { ArrayDeque() }.addLast(index)
            }
            val newCounts = if (unique) new.indices.filter { matches[it] < 0 }
                .groupingBy { key(new[it]) }.eachCount() else emptyMap()
            val originalOldCounts = if (originallyUnique) old.groupingBy(key).eachCount() else emptyMap()
            val originalNewCounts = if (originallyUnique) new.groupingBy(key).eachCount() else emptyMap()
            new.forEachIndexed { index, profile ->
                if (matches[index] >= 0) return@forEachIndexed
                val value = key(profile)
                val previousCandidates = candidates[value] ?: return@forEachIndexed
                if (unique && (previousCandidates.size != 1 || newCounts[value] != 1)) return@forEachIndexed
                if (originallyUnique && (originalOldCounts[value] != 1 || originalNewCounts[value] != 1)) {
                    return@forEachIndexed
                }
                val previous = previousCandidates.removeFirstOrNull() ?: return@forEachIndexed
                matchedOld[previous] = true
                matches[index] = previous
            }
        }

        // Exact duplicate occurrences are interchangeable, paired in their existing order.
        match({ it })
        if (safeIdentity) {
            // The endpoint takes priority over its label. Never infer a changed endpoint from
            // a duplicate label, even if exact matching leaves only one unmatched occurrence.
            match({ it.config }, unique = true)
            match({ it.name }, unique = true, originallyUnique = true)
        } else {
            // Reports describe edits more liberally; they must not assign persistent identity.
            match({ it.name })
            match({ it.config })
        }
        return matches
    }

    private fun sourceChanges(link: Link, old: List<ProfileContent>, new: List<ProfileContent>): SourceChanges {
        val matches = matchProfiles(old, new, safeIdentity = false)
        val matchedOld = matches.filter { it >= 0 }.toSet()
        return SourceChanges(
            sourceId = link.id,
            sourceName = link.name,
            added = new.indices.filter { matches[it] < 0 }.map { new[it].name },
            removed = old.indices.filter { it !in matchedOld }.map { old[it].name },
            modified = new.indices.mapNotNull { current ->
                val previous = matches[current]
                when {
                    previous < 0 || old[previous] == new[current] -> null
                    old[previous].name == new[current].name -> new[current].name
                    else -> "${old[previous].name} → ${new[current].name}"
                }
            },
        )
    }

    private fun readSemanticJson(reader: JsonReader): Any? = when (reader.peek()) {
        JsonToken.BEGIN_OBJECT -> mutableMapOf<String, Any?>().apply {
            reader.beginObject()
            while (reader.hasNext()) put(reader.nextName(), readSemanticJson(reader))
            reader.endObject()
        }
        JsonToken.BEGIN_ARRAY -> mutableListOf<Any?>().apply {
            reader.beginArray()
            while (reader.hasNext()) add(readSemanticJson(reader))
            reader.endArray()
        }
        JsonToken.STRING -> reader.nextString()
        JsonToken.BOOLEAN -> reader.nextBoolean()
        JsonToken.NULL -> { reader.nextNull(); null }
        // BigDecimal avoids rounding large integers and makes numeric formatting insignificant.
        JsonToken.NUMBER -> BigDecimal(reader.nextString()).stripTrailingZeros()
        else -> error("Expected a JSON value")
    }

    private fun reconcileRuntime() {
        if (TProxyService.isActive()) TProxyService.newConfig(context.applicationContext)
    }

    private fun isRefreshDue(link: Link, now: Long): Boolean {
        val lastRefreshedAt = link.lastRefreshedAt
        if (lastRefreshedAt <= 0L) return true

        val intervalMs = effectiveRefreshIntervalMinutes(link) * 60_000L
        return now - lastRefreshedAt >= intervalMs
    }

    private fun effectiveRefreshIntervalMinutes(link: Link): Int {
        return link.refreshIntervalMinutes
            ?.coerceAtLeast(Settings.MIN_REFRESH_LINKS_INTERVAL_MINUTES)
            ?: settings.refreshLinksIntervalMinutes
    }

    private fun normalizeBody(value: String): String = value.trim().removePrefix("\uFEFF").trim()

    private fun jsonProfiles(link: Link, value: String): List<Profile>? {
        if (!value.startsWith("[") && !value.startsWith("{")) return null
        val tokener = JSONTokener(value)
        val root = tokener.nextValue()
        check(tokener.nextClean() == '\u0000') { "Unexpected content after JSON" }
        val configs = when (root) {
            is JSONArray -> root
            is JSONObject -> JSONArray().put(root)
            else -> error("Expected a JSON profile or array")
        }
        return (0 until configs.length()).map { index ->
            val configuration = configs.optJSONObject(index)
                ?: error("Invalid profile at index $index")
            val outbounds = configuration.optJSONArray("outbounds")
            check(outbounds != null && outbounds.length() > 0) { "No outbounds at index $index" }
            check((0 until outbounds.length()).all { outbounds.optJSONObject(it) != null }) {
                "Invalid outbound at index $index"
            }
            val label = configuration.optString("remarks")
                .ifBlank { configuration.optString("remark") }
                .trim()
                .ifBlank { context.getString(R.string.newProfile) }
            configuration.remove("remarks")
            configuration.remove("remark")
            Profile(linkId = link.id, name = label, config = configuration.toString(2))
        }.reversed()
    }

    private fun detectProfiles(link: Link, value: String): Pair<Link.Type, List<Profile>> {
        jsonProfiles(link, value)?.let { return Link.Type.Json to it }
        val candidate = normalizeBody(LinkHelper.tryDecodeBase64(value))
        jsonProfiles(link, candidate)?.let { return Link.Type.Subscription to it }
        val lines = candidate.lineSequence().map(::normalizeBody).filter(String::isNotEmpty).toList()
        val profiles = lines.mapIndexed { index, line ->
            check(line.contains("://") || line.startsWith("{")) { "Invalid profile at line ${index + 1}" }
            val helper = LinkHelper(settings, line)
            check(helper.isValid()) { "Invalid profile at line ${index + 1}" }
            Profile(linkId = link.id, name = helper.remark(), config = helper.json())
        }.reversed()
        return Link.Type.Subscription to profiles
    }

    private suspend fun manageProfiles(
        link: Link,
        linkProfiles: List<Profile>,
        newProfiles: List<Profile>,
        matches: IntArray,
        removedIds: MutableSet<Long>,
    ) {
        val retained = matches.filter { it >= 0 }.toSet()
        linkProfiles.forEachIndexed { index, profile ->
            if (index !in retained) {
                profileDao.remove(profile)
                removedIds.add(profile.id)
            }
        }
        newProfiles.forEachIndexed { index, profile ->
            val previous = matches[index]
            if (previous < 0) {
                profileDao.create(profile.copy(id = 0L, linkId = link.id, index = -1))
            } else {
                // Removal/insertion may have shifted the global index since the snapshot.
                // Update content only, preserving both those shifts and user rearrangements.
                profileDao.updateContent(linkProfiles[previous].id, link.id, profile.name, profile.config)
            }
        }
    }

}
