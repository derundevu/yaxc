package io.github.derundevu.yaxc.dto

import org.json.JSONObject

data class SubscriptionMetadata(
    val profileTitle: String? = null,
    val updateIntervalMinutes: Int? = null,
    val supportUrl: String? = null,
    val profileWebPageUrl: String? = null,
    val uploadBytes: Long? = null,
    val downloadBytes: Long? = null,
    val totalBytes: Long? = null,
    val expireAtEpochSeconds: Long? = null,
) {
    val usedBytes: Long?
        get() {
            val upload = uploadBytes
            val download = downloadBytes
            return when {
                upload == null && download == null -> null
                else -> (upload ?: 0L) + (download ?: 0L)
            }
        }

    fun isEmpty(): Boolean {
        return profileTitle.isNullOrBlank() &&
            updateIntervalMinutes == null &&
            supportUrl.isNullOrBlank() &&
            profileWebPageUrl.isNullOrBlank() &&
            uploadBytes == null &&
            downloadBytes == null &&
            totalBytes == null &&
            expireAtEpochSeconds == null
    }

    fun toJsonString(): String? {
        if (isEmpty()) return null
        return JSONObject().apply {
            profileTitle?.takeIf { it.isNotBlank() }?.let { put(KEY_PROFILE_TITLE, it) }
            updateIntervalMinutes?.let { put(KEY_UPDATE_INTERVAL_MINUTES, it) }
            supportUrl?.takeIf { it.isNotBlank() }?.let { put(KEY_SUPPORT_URL, it) }
            profileWebPageUrl?.takeIf { it.isNotBlank() }?.let { put(KEY_PROFILE_WEB_PAGE_URL, it) }
            uploadBytes?.let { put(KEY_UPLOAD_BYTES, it) }
            downloadBytes?.let { put(KEY_DOWNLOAD_BYTES, it) }
            totalBytes?.let { put(KEY_TOTAL_BYTES, it) }
            expireAtEpochSeconds?.let { put(KEY_EXPIRE_AT_EPOCH_SECONDS, it) }
        }.toString()
    }

    companion object {
        private const val KEY_PROFILE_TITLE = "profileTitle"
        private const val KEY_UPDATE_INTERVAL_MINUTES = "updateIntervalMinutes"
        private const val KEY_UPDATE_INTERVAL_HOURS = "updateIntervalHours"
        private const val KEY_SUPPORT_URL = "supportUrl"
        private const val KEY_PROFILE_WEB_PAGE_URL = "profileWebPageUrl"
        private const val KEY_UPLOAD_BYTES = "uploadBytes"
        private const val KEY_DOWNLOAD_BYTES = "downloadBytes"
        private const val KEY_TOTAL_BYTES = "totalBytes"
        private const val KEY_EXPIRE_AT_EPOCH_SECONDS = "expireAtEpochSeconds"

        fun fromJsonString(raw: String?): SubscriptionMetadata? {
            if (raw.isNullOrBlank()) return null
            return runCatching {
                val json = JSONObject(raw)
                SubscriptionMetadata(
                    profileTitle = json.optString(KEY_PROFILE_TITLE).ifBlank { null },
                    updateIntervalMinutes = json.optInt(KEY_UPDATE_INTERVAL_MINUTES)
                        .takeIf { json.has(KEY_UPDATE_INTERVAL_MINUTES) && it > 0 }
                        ?: json.optInt(KEY_UPDATE_INTERVAL_HOURS)
                            .takeIf { json.has(KEY_UPDATE_INTERVAL_HOURS) && it > 0 }
                            ?.let { it * 60 },
                    supportUrl = json.optString(KEY_SUPPORT_URL).ifBlank { null },
                    profileWebPageUrl = json.optString(KEY_PROFILE_WEB_PAGE_URL).ifBlank { null },
                    uploadBytes = json.optLong(KEY_UPLOAD_BYTES)
                        .takeIf { json.has(KEY_UPLOAD_BYTES) && it >= 0L },
                    downloadBytes = json.optLong(KEY_DOWNLOAD_BYTES)
                        .takeIf { json.has(KEY_DOWNLOAD_BYTES) && it >= 0L },
                    totalBytes = json.optLong(KEY_TOTAL_BYTES)
                        .takeIf { json.has(KEY_TOTAL_BYTES) && it >= 0L },
                    expireAtEpochSeconds = json.optLong(KEY_EXPIRE_AT_EPOCH_SECONDS)
                        .takeIf { json.has(KEY_EXPIRE_AT_EPOCH_SECONDS) && it >= 0L },
                ).takeUnless(SubscriptionMetadata::isEmpty)
            }.getOrNull()
        }
    }
}
