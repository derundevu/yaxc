package io.github.derundevu.yaxc.helper

import android.content.Context
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

    data class RefreshResult(
        val attempted: Int,
        val refreshed: Int,
        val failed: Int,
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
            refreshLinks(links, now)
        }
    }

    suspend fun refreshLinks(linkId: Long? = null): RefreshResult {
        return withContext(Dispatchers.IO) {
            val links = linkDao.activeLinks()
                .filter { it.address.isNotBlank() }
                .filter { linkId == null || it.id == linkId }
            refreshLinks(links, System.currentTimeMillis())
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
    ) {
        try {
            withContext(Dispatchers.IO) {
                check(parsed.profiles.isNotEmpty()) { "No profiles detected" }
                val source = parsed.sourceSnapshot ?: link.copy()
                val removedIds = mutableSetOf<Long>()
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
                    manageProfiles(updated, existing, parsed.profiles, removedIds)
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
    ): RefreshResult {
        var refreshed = 0
        var failed = 0
        var applyAttempted = false
        try {
            links.forEach { link ->
                currentCoroutineContext().ensureActive()
                try {
                    val parsed = resolveProfiles(link)
                    // A cancellation can arrive after Room commits but before the caller resumes.
                    // Reconcile committed state once for the batch, including partial cancellation.
                    applyAttempted = true
                    applyResolvedLink(link, parsed, now, notifyRuntime = false)
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
        )
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
        return SubscriptionMetadata.fromJsonString(link.subscriptionMetadata)
            ?.updateIntervalMinutes
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
        removedIds: MutableSet<Long>,
    ) {
        if (newProfiles.size >= linkProfiles.size) {
            newProfiles.forEachIndexed { index, newProfile ->
                if (index >= linkProfiles.size) {
                    profileDao.create(newProfile.copy(id = 0L, linkId = link.id, index = -1))
                } else {
                    updateProfile(linkProfiles[index], newProfile)
                }
            }
            return
        }

        linkProfiles.forEachIndexed { index, linkProfile ->
            if (index >= newProfiles.size) {
                profileDao.remove(linkProfile)
                removedIds.add(linkProfile.id)
            } else {
                updateProfile(linkProfile, newProfiles[index])
            }
        }
    }

    private suspend fun updateProfile(linkProfile: Profile, newProfile: Profile) {
        profileDao.update(linkProfile.copy(name = newProfile.name, config = newProfile.config))
    }

}
