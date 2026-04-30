package io.github.derundevu.yaxc.helper

import android.content.Context
import io.github.derundevu.yaxc.R
import io.github.derundevu.yaxc.Settings
import io.github.derundevu.yaxc.database.Link
import io.github.derundevu.yaxc.database.Profile
import io.github.derundevu.yaxc.database.YaxcDatabase
import io.github.derundevu.yaxc.dto.SubscriptionMetadata
import io.github.derundevu.yaxc.service.TProxyService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.reflect.cast

class SubscriptionRefreshHelper(
    private val context: Context,
    private val settings: Settings = Settings(context.applicationContext),
) {
    data class ParsedLinkSource(
        val type: Link.Type,
        val profiles: List<Profile>,
        val title: String? = null,
        val metadata: SubscriptionMetadata? = null,
    )

    data class RefreshResult(
        val attempted: Int,
        val refreshed: Int,
        val failed: Int,
    )

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
        return withContext(Dispatchers.IO) {
            val response = HttpHelper.fetch(
                link = link.address,
                userAgent = HttpHelper.resolveSubscriptionUserAgent(settings, link.userAgent),
                headers = HttpHelper.buildSubscriptionHeaders(
                    settings = settings,
                    customHeaders = link.customHeaders,
                    overrideXHwid = link.xHwid,
                ),
            )
            val detected = detectProfiles(link, response.body.trim())
            val metadata = HttpHelper.extractSubscriptionMetadata(response.headers)
            ParsedLinkSource(
                type = detected.first,
                profiles = detected.second,
                title = metadata?.profileTitle ?: HttpHelper.extractSubscriptionTitle(response.headers),
                metadata = metadata,
            ).also {
                if (it.profiles.isEmpty()) error("No profiles detected")
            }
        }
    }

    suspend fun saveResolvedLink(
        link: Link,
        parsed: ParsedLinkSource,
        now: Long = System.currentTimeMillis(),
    ) {
        withContext(Dispatchers.IO) {
            val existingProfiles = if (link.id == 0L) emptyList() else profileDao.linkProfiles(link.id)
            applyResolvedLink(link, parsed, existingProfiles, now)
        }
    }

    private suspend fun refreshLinks(
        links: List<Link>,
        now: Long,
    ): RefreshResult {
        var refreshed = 0
        var failed = 0
        links.forEach { link ->
            runCatching {
                val parsed = resolveProfiles(link)
                val existingProfiles = profileDao.linkProfiles(link.id)
                applyResolvedLink(link, parsed, existingProfiles, now)
            }.onSuccess {
                refreshed += 1
            }.onFailure {
                failed += 1
            }
        }
        if (refreshed > 0 && TProxyService.isActive()) {
            TProxyService.newConfig(context.applicationContext)
        }
        return RefreshResult(
            attempted = links.size,
            refreshed = refreshed,
            failed = failed,
        )
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

    private suspend fun applyResolvedLink(
        link: Link,
        parsed: ParsedLinkSource,
        existingProfiles: List<Profile>,
        now: Long,
    ) {
        if (link.type != parsed.type) link.type = parsed.type
        parsed.title?.takeIf { it.isNotBlank() }?.let { link.name = it }
        link.subscriptionMetadata = parsed.metadata?.toJsonString()
        link.lastRefreshedAt = now

        if (link.id == 0L) {
            link.position = linkDao.nextPosition()
            link.id = linkDao.insert(link)
        } else {
            linkDao.update(link)
        }

        manageProfiles(link, existingProfiles, parsed.profiles)
    }

    private fun jsonProfiles(link: Link, value: String): List<Profile> {
        val list = arrayListOf<Profile>()
        val trimmed = value.trim()
        val configs = when {
            trimmed.startsWith("[") -> runCatching { JSONArray(trimmed) }.getOrNull() ?: JSONArray()
            trimmed.startsWith("{") -> runCatching { JSONObject(trimmed) }
                .getOrNull()
                ?.let { JSONArray().put(it) }
                ?: JSONArray()
            else -> JSONArray()
        }
        for (i in 0 until configs.length()) {
            runCatching { JSONObject::class.cast(configs[i]) }.getOrNull()?.let { configuration ->
                val label = if (configuration.has("remarks")) {
                    val remarks = configuration.getString("remarks")
                    configuration.remove("remarks")
                    remarks
                } else {
                    context.getString(R.string.newProfile)
                }
                val json = configuration.toString(2)
                val profile = Profile().apply {
                    linkId = link.id
                    name = label
                    config = json
                }
                list.add(profile)
            }
        }
        return list.reversed().toList()
    }

    private fun subscriptionProfiles(link: Link, value: String): List<Profile> {
        val decoded = runCatching { LinkHelper.tryDecodeBase64(value).trim() }.getOrNull().orEmpty()
        val candidate = if (decoded.isNotBlank()) decoded else value.trim()
        val decodedJsonProfiles = jsonProfiles(link, candidate)
        if (decodedJsonProfiles.isNotEmpty()) return decodedJsonProfiles

        return candidate.split("\n")
            .reversed()
            .map { LinkHelper(settings, it) }
            .filter { it.isValid() }
            .map { linkHelper ->
                Profile().apply {
                    linkId = link.id
                    config = linkHelper.json()
                    name = linkHelper.remark()
                }
            }
    }

    private fun detectProfiles(link: Link, value: String): Pair<Link.Type, List<Profile>> {
        val jsonProfiles = jsonProfiles(link, value)
        if (jsonProfiles.isNotEmpty()) return Link.Type.Json to jsonProfiles

        val subscriptionProfiles = subscriptionProfiles(link, value)
        if (subscriptionProfiles.isNotEmpty()) return Link.Type.Subscription to subscriptionProfiles

        return link.type to emptyList()
    }

    private suspend fun manageProfiles(
        link: Link,
        linkProfiles: List<Profile>,
        newProfiles: List<Profile>,
    ) {
        if (newProfiles.size >= linkProfiles.size) {
            newProfiles.forEachIndexed { index, newProfile ->
                if (index >= linkProfiles.size) {
                    newProfile.linkId = link.id
                    profileDao.create(newProfile)
                } else {
                    updateProfile(linkProfiles[index], newProfile)
                }
            }
            return
        }

        linkProfiles.forEachIndexed { index, linkProfile ->
            if (index >= newProfiles.size) {
                deleteProfile(linkProfile)
            } else {
                updateProfile(linkProfile, newProfiles[index])
            }
        }
    }

    private suspend fun updateProfile(linkProfile: Profile, newProfile: Profile) {
        linkProfile.name = newProfile.name
        linkProfile.config = newProfile.config
        profileDao.update(linkProfile)
    }

    private suspend fun deleteProfile(linkProfile: Profile) {
        profileDao.remove(linkProfile)
        withContext(Dispatchers.Main) {
            val selectedProfile = settings.selectedProfile
            if (selectedProfile == linkProfile.id) {
                settings.selectedProfile = 0L
            }
        }
    }
}
