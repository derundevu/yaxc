package io.github.derundevu.yaxc.helper

import android.os.Build
import io.github.derundevu.yaxc.BuildConfig
import io.github.derundevu.yaxc.Settings
import io.github.derundevu.yaxc.dto.SubscriptionMetadata
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.IOException
import java.net.Authenticator
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.URL
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64

class HttpHelper private constructor() {

    companion object {
        data class HttpResponse(
            val body: String,
            val headers: Map<String, List<String>>,
        )

        private fun getConnection(
            link: String,
            method: String = "GET",
            proxy: Proxy? = null,
            timeout: Int = 5000,
            userAgent: String? = null,
            headers: Map<String, String> = emptyMap(),
        ): HttpURLConnection {
            val url = URL(link)
            val connection = if (proxy == null) {
                url.openConnection() as HttpURLConnection
            } else {
                url.openConnection(proxy) as HttpURLConnection
            }
            connection.requestMethod = method
            connection.connectTimeout = timeout
            connection.readTimeout = timeout
            userAgent?.let { connection.setRequestProperty("User-Agent", it) }
            headers.forEach { (key, value) ->
                connection.setRequestProperty(key, value)
            }
            connection.setRequestProperty("Connection", "close")
            return connection
        }

        suspend fun fetch(
            link: String,
            userAgent: String? = null,
            headers: Map<String, String> = emptyMap(),
        ): HttpResponse {
            return withContext(Dispatchers.IO) {
                currentCoroutineContext().ensureActive()
                val defaultUserAgent = "yaxc/${BuildConfig.VERSION_NAME}"
                val connection = getConnection(
                    link = link,
                    userAgent = userAgent ?: defaultUserAgent,
                    headers = headers,
                )
                try {
                    connection.connect()
                    val responseCode = connection.responseCode
                    val responseBody = connection.readResponseText()
                    currentCoroutineContext().ensureActive()
                    if (responseCode != HttpURLConnection.HTTP_OK || responseBody == null) {
                        throw IOException("HTTP Error: $responseCode")
                    }
                    HttpResponse(
                        body = responseBody,
                        headers = connection.headerFields
                            .filterKeys { it != null }
                            .mapKeys { it.key!! },
                    )
                } catch (error: Exception) {
                    currentCoroutineContext().ensureActive()
                    throw error
                } finally {
                    connection.disconnect()
                }
            }
        }

        suspend fun get(
            link: String,
            userAgent: String? = null,
            headers: Map<String, String> = emptyMap(),
        ): String {
            return fetch(link, userAgent, headers).body
        }

        fun parseHeaders(rawHeaders: String?): Map<String, String> {
            if (rawHeaders.isNullOrBlank()) return emptyMap()
            return buildMap {
                rawHeaders.lineSequence()
                    .map(String::trim)
                    .filter { it.isNotEmpty() }
                    .forEach { line ->
                        val separatorIndex = line.indexOf(':')
                        if (separatorIndex <= 0 || separatorIndex >= line.lastIndex) return@forEach
                        val key = line.substring(0, separatorIndex).trim()
                        val value = line.substring(separatorIndex + 1).trim()
                        if (key.isNotEmpty() && value.isNotEmpty()) {
                            putHeader(this, key, value)
                        }
                    }
            }
        }

        fun resolveSubscriptionUserAgent(
            settings: Settings,
            overrideUserAgent: String?,
        ): String {
            return overrideUserAgent?.trim()?.takeIf { it.isNotEmpty() } ?: settings.userAgent
        }

        fun buildSubscriptionHeaders(
            settings: Settings,
            customHeaders: String?,
            overrideXHwid: String? = null,
        ): Map<String, String> {
            val headers = parseHeaders(customHeaders).toMutableMap()
            val normalizedOverrideXHwid = overrideXHwid?.trim()?.takeIf { it.isNotEmpty() }

            when {
                normalizedOverrideXHwid != null -> putHeader(headers, "x-hwid", normalizedOverrideXHwid)
                !hasHeader(headers, "x-hwid") -> putHeader(headers, "x-hwid", settings.xHwid)
            }

            if (hasHeader(headers, "x-hwid")) {
                putHeaderIfMissing(headers, "x-device-os", "Android")
                putHeaderIfMissing(
                    headers,
                    "x-ver-os",
                    Build.VERSION.RELEASE.orEmpty().ifBlank { Build.VERSION.SDK_INT.toString() },
                )
                defaultDeviceModelHeader()?.let { putHeaderIfMissing(headers, "x-device-model", it) }
            }

            return headers
        }

        suspend fun resolveExitIpViaSocks(
            socksAddress: String,
            socksPort: String,
            socksUsername: String,
            socksPassword: String,
            timeout: Int = 5000,
        ): String {
            return withContext(Dispatchers.IO) {
                val port = socksPort.toIntOrNull() ?: throw IllegalArgumentException("Invalid SOCKS port")
                val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(socksAddress, port))
                val auth = if (socksUsername.isBlank() || socksPassword.isBlank()) {
                    null
                } else {
                    object : Authenticator() {
                        override fun getPasswordAuthentication(): PasswordAuthentication {
                            return PasswordAuthentication(
                                socksUsername,
                                socksPassword.toCharArray(),
                            )
                        }
                    }
                }
                val services = listOf(
                    "https://api64.ipify.org",
                    "https://api.ipify.org",
                )
                val defaultUserAgent = "yaxc/${BuildConfig.VERSION_NAME}"
                var lastError: Throwable? = null

                services.forEach { service ->
                    try {
                        Authenticator.setDefault(auth)
                        val connection = getConnection(
                            link = service,
                            method = "GET",
                            proxy = proxy,
                            timeout = timeout,
                            userAgent = defaultUserAgent,
                        )
                        try {
                            connection.connect()
                            val responseCode = connection.responseCode
                            val responseBody = connection.readResponseText()?.trim().orEmpty()
                            if (responseCode == HttpURLConnection.HTTP_OK && responseBody.isNotEmpty()) {
                                return@withContext responseBody
                            }
                            lastError = IllegalStateException("HTTP Error: $responseCode")
                        } finally {
                            connection.disconnect()
                        }
                    } catch (error: Throwable) {
                        lastError = error
                    } finally {
                        Authenticator.setDefault(null)
                    }
                }

                throw lastError ?: IllegalStateException("Exit IP unavailable")
            }
        }

        fun extractSubscriptionTitle(headers: Map<String, List<String>>): String? {
            val normalizedHeaders = normalizeHeaders(headers)

            val profileTitle = firstDecodedHeaderValue(
                normalizedHeaders,
                "profile-title",
                "x-profile-title",
            )
            if (!profileTitle.isNullOrBlank()) {
                return profileTitle
            }

            val contentDisposition = normalizedHeaders["content-disposition"]
                ?.firstNotNullOfOrNull(::extractFilenameFromContentDisposition)
            if (!contentDisposition.isNullOrBlank()) {
                return contentDisposition
            }

            return null
        }

        fun extractSubscriptionMetadata(headers: Map<String, List<String>>): SubscriptionMetadata? {
            val normalizedHeaders = normalizeHeaders(headers)
            val userInfo = parseSubscriptionUserInfo(
                firstDecodedHeaderValue(
                    normalizedHeaders,
                    "subscription-userinfo",
                    "x-subscription-userinfo",
                )
            )

            return SubscriptionMetadata(
                profileTitle = extractSubscriptionTitle(headers),
                updateIntervalMinutes = firstDecodedHeaderValue(
                    normalizedHeaders,
                    "profile-update-interval",
                    "x-profile-update-interval",
                )?.toIntOrNull()?.takeIf { it > 0 && it <= Int.MAX_VALUE / 60 }?.let { it * 60 },
                supportUrl = firstDecodedHeaderValue(
                    normalizedHeaders,
                    "support-url",
                    "x-support-url",
                ),
                profileWebPageUrl = firstDecodedHeaderValue(
                    normalizedHeaders,
                    "profile-web-page-url",
                    "x-profile-web-page-url",
                ),
                uploadBytes = userInfo?.uploadBytes,
                downloadBytes = userInfo?.downloadBytes,
                totalBytes = userInfo?.totalBytes,
                expireAtEpochSeconds = userInfo?.expireAtEpochSeconds,
            ).takeUnless(SubscriptionMetadata::isEmpty)
        }

        private fun HttpURLConnection.readResponseText(): String? {
            val stream = responseStream() ?: return null
            return stream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        }

        private fun HttpURLConnection.responseStream(): InputStream? {
            return runCatching {
                if (responseCode in 200..299) inputStream else errorStream
            }.getOrNull()
        }

        private fun extractFilenameFromContentDisposition(value: String): String? {
            val filenameStar = Regex("""filename\*\s*=\s*(?:UTF-8''|utf-8''|)([^;]+)""")
                .find(value)
                ?.groupValues
                ?.getOrNull(1)
                ?.let(::decodeHeaderValue)
            if (!filenameStar.isNullOrBlank()) {
                return filenameStar
            }

            return Regex("""filename\s*=\s*"?([^\";]+)""")
                .find(value)
                ?.groupValues
                ?.getOrNull(1)
                ?.let(::decodeHeaderValue)
        }

        private fun normalizeHeaders(headers: Map<String, List<String>>): Map<String, List<String>> {
            return headers.entries.associate { (key, value) ->
                key.lowercase() to value.filter { it.isNotBlank() }
            }
        }

        private fun hasHeader(
            headers: Map<String, String>,
            name: String,
        ): Boolean {
            return headers.keys.any { it.equals(name, ignoreCase = true) }
        }

        private fun putHeader(
            headers: MutableMap<String, String>,
            name: String,
            value: String,
        ) {
            val existingKey = headers.keys.firstOrNull { it.equals(name, ignoreCase = true) }
            if (existingKey != null) {
                headers[existingKey] = value
            } else {
                headers[name] = value
            }
        }

        private fun putHeaderIfMissing(
            headers: MutableMap<String, String>,
            name: String,
            value: String,
        ) {
            if (!hasHeader(headers, name)) {
                headers[name] = value
            }
        }

        private fun defaultDeviceModelHeader(): String? {
            val manufacturer = Build.MANUFACTURER?.trim().orEmpty()
            val model = Build.MODEL?.trim().orEmpty()
            return when {
                manufacturer.isEmpty() && model.isEmpty() -> null
                manufacturer.isEmpty() -> model
                model.isEmpty() -> manufacturer
                model.startsWith(manufacturer, ignoreCase = true) -> model
                else -> "$manufacturer $model"
            }
        }

        private fun firstDecodedHeaderValue(
            headers: Map<String, List<String>>,
            vararg keys: String,
        ): String? {
            return keys.asSequence()
                .mapNotNull(headers::get)
                .flatten()
                .mapNotNull(::decodeHeaderValue)
                .firstOrNull()
        }

        private data class ParsedSubscriptionUserInfo(
            val uploadBytes: Long? = null,
            val downloadBytes: Long? = null,
            val totalBytes: Long? = null,
            val expireAtEpochSeconds: Long? = null,
        )

        private fun parseSubscriptionUserInfo(value: String?): ParsedSubscriptionUserInfo? {
            if (value.isNullOrBlank()) return null
            var uploadBytes: Long? = null
            var downloadBytes: Long? = null
            var totalBytes: Long? = null
            var expireAtEpochSeconds: Long? = null

            value.split(';')
                .map(String::trim)
                .filter { it.isNotEmpty() }
                .forEach { segment ->
                    val separatorIndex = segment.indexOf('=')
                    if (separatorIndex <= 0 || separatorIndex >= segment.lastIndex) return@forEach
                    val key = segment.substring(0, separatorIndex).trim().lowercase()
                    val rawValue = segment.substring(separatorIndex + 1).trim()
                    val parsedValue = rawValue.toLongOrNull()?.takeIf { it >= 0L } ?: return@forEach
                    when (key) {
                        "upload" -> uploadBytes = parsedValue
                        "download" -> downloadBytes = parsedValue
                        "total" -> totalBytes = parsedValue
                        "expire" -> expireAtEpochSeconds = parsedValue
                    }
                }

            return ParsedSubscriptionUserInfo(
                uploadBytes = uploadBytes,
                downloadBytes = downloadBytes,
                totalBytes = totalBytes,
                expireAtEpochSeconds = expireAtEpochSeconds,
            ).takeIf {
                it.uploadBytes != null ||
                    it.downloadBytes != null ||
                    it.totalBytes != null ||
                    it.expireAtEpochSeconds != null
            }
        }

        private fun decodeHeaderValue(value: String): String? {
            val trimmed = value.trim().trim('"')
            if (trimmed.isEmpty()) return null

            decodePrefixedBase64(trimmed)?.let { decoded ->
                return sanitizeHeaderValue(decoded)
            }

            decodeMimeEncodedWord(trimmed)?.let { decoded ->
                return sanitizeHeaderValue(decoded)
            }

            decodePercentEncoded(trimmed)?.let { decoded ->
                return sanitizeHeaderValue(decoded)
            }

            decodeBase64Header(trimmed)?.let { decoded ->
                return sanitizeHeaderValue(decoded)
            }

            return sanitizeHeaderValue(trimmed)
        }

        private fun decodePrefixedBase64(value: String): String? {
            val prefix = "base64:"
            if (!value.startsWith(prefix, ignoreCase = true)) return null
            return runCatching {
                String(
                    Base64.getDecoder().decode(value.substring(prefix.length)),
                    StandardCharsets.UTF_8
                )
            }.getOrNull()
        }

        private fun decodeMimeEncodedWord(value: String): String? {
            val match = Regex("""=\?([^?]+)\?([Bb])\?([^?]+)\?=""").matchEntire(value) ?: return null
            val charset = match.groupValues[1]
            val data = match.groupValues[3]
            return runCatching {
                String(
                    Base64.getDecoder().decode(data),
                    charset(charset)
                )
            }.getOrNull()
        }

        private fun decodePercentEncoded(value: String): String? {
            if (!value.contains('%') && !value.contains('+')) return null
            return runCatching {
                URLDecoder.decode(value, StandardCharsets.UTF_8.name())
            }.getOrNull()
        }

        private fun decodeBase64Header(value: String): String? {
            if (value.all { it in '0'..'9' } || !value.matches(Regex("""[A-Za-z0-9+/=_-]+"""))) return null
            return runCatching {
                val normalized = value.replace('-', '+').replace('_', '/')
                Base64.getDecoder().decode(normalized).decodeToString(throwOnInvalidSequence = true)
            }.getOrNull()
        }

        private fun sanitizeHeaderValue(value: String): String? {
            return value
                .replace('\u0000', ' ')
                .replace(Regex("""\s+"""), " ")
                .trim()
                .takeIf { it.isNotEmpty() }
        }
    }
}
