package io.github.derundevu.yaxc.helper

import io.github.derundevu.yaxc.BuildConfig
import io.github.derundevu.yaxc.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.Authenticator
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.Socket
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64

class HttpHelper(
    val scope: CoroutineScope,
    val settings: Settings,
) {

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
            connection.setRequestProperty("Connection", "close")
            return connection
        }

        suspend fun fetch(link: String, userAgent: String? = null): HttpResponse {
            return withContext(Dispatchers.IO) {
                val defaultUserAgent = "yaxc/${BuildConfig.VERSION_NAME}"
                val connection = getConnection(link, userAgent = userAgent ?: defaultUserAgent)
                var responseCode = 0
                val responseBody = try {
                    connection.connect()
                    responseCode = connection.responseCode
                    connection.readResponseText()
                } catch (_: Exception) {
                    null
                }
                val headers = connection.headerFields
                    .filterKeys { it != null }
                    .mapKeys { it.key!! }
                connection.disconnect()
                if (responseCode != HttpURLConnection.HTTP_OK || responseBody == null) {
                    throw Exception("HTTP Error: $responseCode")
                }
                HttpResponse(
                    body = responseBody,
                    headers = headers,
                )
            }
        }

        suspend fun get(link: String, userAgent: String? = null): String {
            return fetch(link, userAgent).body
        }

        fun extractSubscriptionTitle(headers: Map<String, List<String>>): String? {
            val normalizedHeaders = headers.entries.associate { (key, value) ->
                key.lowercase() to value.filter { it.isNotBlank() }
            }

            val profileTitle = normalizedHeaders["profile-title"]
                ?.firstNotNullOfOrNull(::decodeHeaderValue)
            if (!profileTitle.isNullOrBlank()) {
                return profileTitle
            }

            val xProfileTitle = normalizedHeaders["x-profile-title"]
                ?.firstNotNullOfOrNull(::decodeHeaderValue)
            if (!xProfileTitle.isNullOrBlank()) {
                return xProfileTitle
            }

            val contentDisposition = normalizedHeaders["content-disposition"]
                ?.firstNotNullOfOrNull(::extractFilenameFromContentDisposition)
            if (!contentDisposition.isNullOrBlank()) {
                return contentDisposition
            }

            return null
        }

        private fun HttpURLConnection.readResponseText(): String? {
            val stream = responseStream() ?: return null
            return stream.bufferedReader().use { it.readText() }
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
            if (!value.matches(Regex("""[A-Za-z0-9+/=_-]+"""))) return null
            return runCatching {
                val normalized = value.replace('-', '+').replace('_', '/')
                String(Base64.getDecoder().decode(normalized), StandardCharsets.UTF_8)
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

    fun measureDelay(proxy: Boolean, callback: (result: String) -> Unit) {
        scope.launch(Dispatchers.IO) {
            val start = System.currentTimeMillis()
            val result = try {
                setSocksAuth(getSocksAuth())
                when (settings.pingType) {
                    Settings.PingType.Tcp -> {
                        val target = resolveTcpTarget(settings.pingAddress)
                        val socket = if (proxy) {
                            Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress(settings.socksAddress, settings.socksPort.toInt())))
                        } else {
                            Socket()
                        }
                        socket.use {
                            it.connect(target, settings.pingTimeout * 1000)
                        }
                        val delay = System.currentTimeMillis() - start
                        "TCP, $delay ms"
                    }

                    Settings.PingType.Get,
                    Settings.PingType.Head,
                    -> {
                        val connection = getConnection(proxy, settings.pingType)
                        try {
                            val responseCode = connection.responseCode
                            val delay = System.currentTimeMillis() - start
                            "HTTP $responseCode, $delay ms"
                        } finally {
                            connection.disconnect()
                        }
                    }
                }
            } catch (error: Exception) {
                error.message ?: "Http delay measure failed"
            } finally {
                setSocksAuth(null)
            }
            withContext(Dispatchers.Main) {
                callback(result)
            }
        }
    }

    private suspend fun getConnection(
        withProxy: Boolean,
        pingType: Settings.PingType,
    ): HttpURLConnection {
        return withContext(Dispatchers.IO) {
            val link = settings.pingAddress
            val method = when (pingType) {
                Settings.PingType.Head -> "HEAD"
                Settings.PingType.Get -> "GET"
                Settings.PingType.Tcp -> "GET"
            }
            val address = InetSocketAddress(settings.socksAddress, settings.socksPort.toInt())
            val proxy = if (withProxy) Proxy(Proxy.Type.SOCKS, address) else null
            val timeout = settings.pingTimeout * 1000

            getConnection(link, method, proxy, timeout, settings.userAgent)
        }
    }

    private fun resolveTcpTarget(value: String): InetSocketAddress {
        val trimmed = value.trim()
        val uri = if (trimmed.contains("://")) {
            URI(trimmed)
        } else {
            URI("tcp://$trimmed")
        }
        val host = uri.host ?: throw IllegalArgumentException("Invalid ping address")
        val port = when {
            uri.port != -1 -> uri.port
            uri.scheme.equals("http", ignoreCase = true) -> 80
            else -> 443
        }
        return InetSocketAddress(host, port)
    }

    private fun getSocksAuth(): Authenticator? {
        if (
            settings.socksUsername.trim().isEmpty() || settings.socksPassword.trim().isEmpty()
        ) return null
        return object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication {
                return PasswordAuthentication(
                    settings.socksUsername,
                    settings.socksPassword.toCharArray()
                )
            }
        }
    }

    private fun setSocksAuth(auth: Authenticator?) {
        Authenticator.setDefault(auth)
    }

}
