package io.github.derundevu.yaxc.helper

import android.content.Context
import android.util.Base64
import android.util.Log
import io.github.derundevu.yaxc.R
import io.github.derundevu.yaxc.Settings
import io.github.derundevu.yaxc.database.Config
import io.github.derundevu.yaxc.database.Config.Mode
import io.github.derundevu.yaxc.database.Profile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.ServerSocket

object XrayBatchPingHelper {

    private const val LOCAL_PING_HOST = "127.0.0.1"
    private const val TAG = "XrayBatchPingHelper"

    fun supportsIsolatedPing(): Boolean {
        if (supportsTypedIsolatedPing()) return true
        return runCatching {
            Class.forName("XrayCore.XrayCore").getMethod(
                "ping",
                String::class.java,
                String::class.java,
                Long::class.javaPrimitiveType!!,
                String::class.java,
                String::class.java,
            )
        }.isSuccess
    }

    private fun supportsTypedIsolatedPing(): Boolean {
        return runCatching {
            Class.forName("XrayCore.XrayCore").getMethod(
                "pingWithType",
                String::class.java,
                String::class.java,
                Long::class.javaPrimitiveType!!,
                String::class.java,
                String::class.java,
                String::class.java,
            )
        }.isSuccess
    }

    suspend fun measureProfileDelay(
        context: Context,
        settings: Settings,
        globalConfig: Config,
        profile: Profile,
    ): String {
        if (!supportsIsolatedPing()) {
            return context.getString(R.string.pingIsolatedUnavailable)
        }

        val pingPort = findFreePort()
        val runtimeConfig = buildPingConfig(
            settings = settings,
            globalConfig = globalConfig,
            profile = profile,
            localPort = pingPort,
        )
        val workDir = File(context.cacheDir, "batch-ping").apply { mkdirs() }
        val configFile = File(workDir, "profile-${profile.id}-$pingPort.json")
        FileHelper.createOrUpdate(configFile, runtimeConfig)

        return try {
            val validationError = validateConfig(
                dir = context.filesDir.absolutePath,
                configPath = configFile.absolutePath,
            )
            if (!validationError.isNullOrBlank()) {
                Log.e(
                    TAG,
                    "Isolated ping config validation failed: $validationError; " +
                        "profileId=${profile.id}; configPath=${configFile.absolutePath}"
                )
                return validationError
            }
            invokePing(
                context = context,
                dir = context.filesDir.absolutePath,
                configPath = configFile.absolutePath,
                timeout = settings.pingTimeout,
                url = settings.pingAddress,
                proxy = "socks5://$LOCAL_PING_HOST:$pingPort",
                pingType = settings.pingType,
            )
        } finally {
            configFile.delete()
        }
    }

    private fun buildPingConfig(
        settings: Settings,
        globalConfig: Config,
        profile: Profile,
        localPort: Int,
    ): String {
        val pingConfig = globalConfig.copy(
            routing = "{}",
            routingMode = Mode.Disable,
        )
        val runtimeConfig = JSONObject(ConfigHelper(settings, pingConfig, profile.config).toString())
        val inbounds = runtimeConfig.optJSONArray("inbounds") ?: JSONArray()

        for (index in 0 until inbounds.length()) {
            val inbound = inbounds.optJSONObject(index) ?: continue
            if (inbound.optString("protocol") != "socks") continue

            inbound.put("listen", LOCAL_PING_HOST)
            inbound.put("port", localPort)

            val settingsObject = inbound.optJSONObject("settings") ?: JSONObject()
            settingsObject.put("auth", "noauth")
            settingsObject.remove("accounts")
            inbound.put("settings", settingsObject)
            inbounds.put(index, inbound)
            break
        }

        runtimeConfig.put("inbounds", inbounds)
        return runtimeConfig.toString(4)
    }

    private fun invokePing(
        context: Context,
        dir: String,
        configPath: String,
        timeout: Int,
        url: String,
        proxy: String,
        pingType: Settings.PingType,
    ): String {
        val xrayCoreClass = Class.forName("XrayCore.XrayCore")
        val response = when {
            supportsTypedIsolatedPing() -> {
                xrayCoreClass
                    .getMethod(
                        "pingWithType",
                        String::class.java,
                        String::class.java,
                        Long::class.javaPrimitiveType!!,
                        String::class.java,
                        String::class.java,
                        String::class.java,
                    )
                    .invoke(
                        null,
                        dir,
                        configPath,
                        timeout.toLong(),
                        url,
                        proxy,
                        pingType.value,
                    ) as? String
            }

            pingType == Settings.PingType.Head -> {
                xrayCoreClass
                    .getMethod(
                        "ping",
                        String::class.java,
                        String::class.java,
                        Long::class.javaPrimitiveType!!,
                        String::class.java,
                        String::class.java,
                    )
                    .invoke(null, dir, configPath, timeout.toLong(), url, proxy) as? String
            }

            else -> {
                Log.e(
                    TAG,
                    "Typed isolated ping is unavailable for pingType=${pingType.value}; configPath=$configPath"
                )
                return context.getString(R.string.pingTypeRequiresCoreUpdate)
            }
        } ?: return context.getString(R.string.pingFailedGeneric)

        val decoded = String(Base64.decode(response, Base64.DEFAULT))
        val payload = JSONObject(decoded)
        if (!payload.optBoolean("success")) {
            val error = payload.optString("error").ifBlank {
                context.getString(R.string.pingFailedGeneric)
            }
            Log.e(
                TAG,
                "Isolated ping failed: $error; configPath=$configPath; url=$url; proxy=$proxy; pingType=${pingType.value}"
            )
            return error
        }

        val delay = payload.optLong("data", -1L)
        return if (delay >= 0) {
            context.getString(R.string.pingDelayMs, delay)
        } else {
            Log.e(
                TAG,
                "Isolated ping returned invalid delay payload; configPath=$configPath; url=$url; pingType=${pingType.value}"
            )
            context.getString(R.string.pingFailedGeneric)
        }
    }

    private fun validateConfig(
        dir: String,
        configPath: String,
    ): String? {
        return runCatching {
            Class.forName("XrayCore.XrayCore")
                .getMethod(
                    "test",
                    String::class.java,
                    String::class.java,
                )
                .invoke(null, dir, configPath) as? String
        }.onFailure { error ->
            Log.e(TAG, "Failed to validate isolated ping config; configPath=$configPath", error)
        }.getOrNull()
    }

    private fun findFreePort(): Int {
        ServerSocket(0).use { socket ->
            socket.reuseAddress = true
            return socket.localPort
        }
    }
}
