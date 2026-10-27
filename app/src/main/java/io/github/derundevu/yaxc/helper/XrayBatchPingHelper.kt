package io.github.derundevu.yaxc.helper

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.os.SystemClock
import android.util.Base64
import io.github.derundevu.yaxc.R
import io.github.derundevu.yaxc.Settings
import io.github.derundevu.yaxc.database.Config
import io.github.derundevu.yaxc.database.Profile
import io.github.derundevu.yaxc.service.PingService
import io.github.derundevu.yaxc.service.PingWorker1Service
import io.github.derundevu.yaxc.service.PingWorker2Service
import io.github.derundevu.yaxc.service.PingWorker3Service
import io.github.derundevu.yaxc.service.PingWorker4Service
import io.github.derundevu.yaxc.service.PingWorker5Service
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicLong

object XrayBatchPingHelper {
    data class Options(val timeoutSeconds: Int, val address: String, val type: Settings.PingType) {
        companion object {
            fun from(settings: Settings) = Options(settings.pingTimeout, settings.pingAddress, settings.pingType)
        }
    }

    private const val LOCAL_PING_HOST = "127.0.0.1"
    private val workerServices = arrayOf(
        PingWorker1Service::class.java,
        PingWorker2Service::class.java,
        PingWorker3Service::class.java,
        PingWorker4Service::class.java,
        PingWorker5Service::class.java,
    )
    val workerCount: Int get() = workerServices.size
    private val availableWorkers: Channel<Class<out PingService>> = Channel<Class<out PingService>>(
        capacity = workerServices.size,
        // A cancelled receive may remove a slot before its caller resumes.
        onUndeliveredElement = { availableWorkers.trySend(it).getOrThrow() },
    ).apply {
        workerServices.forEach { trySend(it).getOrThrow() }
    }
    private val requestIds = AtomicLong(SystemClock.elapsedRealtimeNanos())

    suspend fun measureProfileDelay(
        context: Context,
        settings: Settings,
        globalConfig: Config,
        profile: Profile,
        options: Options = Options.from(settings),
    ): String = withContext(Dispatchers.IO) {
        val service = availableWorkers.receive()
        try {
            require(options.timeoutSeconds > 0) { "Ping timeout must be positive" }
            val pingPort = ServerSocket(0).use { it.localPort }
            val runtimeConfig = buildPingConfig(settings, globalConfig, profile, pingPort)
            val workDir = File(context.cacheDir, "batch-ping").apply { mkdirs() }
            val configFile = File.createTempFile("profile-${profile.id}-", ".json", workDir)
            try {
                configFile.writeText(runtimeConfig)
                val response = measureInWorker(context.applicationContext, service, configFile, pingPort, options)
                val payload = JSONObject(String(Base64.decode(response, Base64.DEFAULT)))
                if (!payload.optBoolean("success")) {
                    payload.optString("error").ifBlank { context.getString(R.string.pingFailedGeneric) }
                } else {
                    val delay = payload.optLong("data", -1L)
                    if (delay >= 0L) context.getString(R.string.pingDelayMs, delay)
                    else context.getString(R.string.pingFailedGeneric)
                }
            } finally {
                configFile.delete()
            }
        } finally {
            availableWorkers.trySend(service).getOrThrow()
        }
    }

    private fun buildPingConfig(settings: Settings, globalConfig: Config, profile: Profile, port: Int): String {
        val pingConfig = globalConfig.copy(routing = "{}", routingMode = Config.Mode.Disable)
        val runtimeConfig = JSONObject(ConfigHelper(settings, pingConfig, profile.config).toString())
        // Replace all runtime listeners, including transparent/root-mode listeners. Never bind VPN ports.
        runtimeConfig.put("inbounds", JSONArray().put(JSONObject()
            .put("listen", LOCAL_PING_HOST)
            .put("port", port)
            .put("protocol", "socks")
            .put("tag", "socks")
            .put("settings", JSONObject().put("auth", "noauth").put("udp", false))))
        return runtimeConfig.toString()
    }

    private suspend fun measureInWorker(
        context: Context,
        service: Class<out PingService>,
        config: File,
        port: Int,
        options: Options,
    ): String =
        withContext(Dispatchers.Main.immediate) {
            val id = requestIds.incrementAndGet()
            val response = CompletableDeferred<String>()
            val finished = CompletableDeferred<Unit>()
            var worker: Messenger? = null
            var sent = false
            var active = true
            val reply = Messenger(Handler(Looper.getMainLooper()) { message ->
                if (message.what == PingService.RESULT && message.data.getLong(PingService.ID) == id) {
                    val error = message.data.getString(PingService.ERROR)
                    if (error != null) response.completeExceptionally(IllegalStateException(error))
                    else response.complete(message.data.getString(PingService.RESPONSE).orEmpty())
                    finished.complete(Unit)
                }
                true
            })
            fun disconnected() {
                worker = null
                response.completeExceptionally(IllegalStateException(context.getString(R.string.pingFailedGeneric)))
                finished.complete(Unit)
            }
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) {
                    if (!active) return
                    worker = Messenger(service)
                    try {
                        worker!!.send(Message.obtain(null, PingService.START).apply {
                            replyTo = reply
                            data = Bundle().apply {
                                putLong(PingService.ID, id)
                                putString(PingService.CONFIG, config.absolutePath)
                                putInt(PingService.TIMEOUT, options.timeoutSeconds)
                                putString(PingService.URL, options.address)
                                putString(PingService.PROXY, "socks5://$LOCAL_PING_HOST:$port")
                                putString(PingService.TYPE, options.type.value)
                            }
                        })
                        sent = true
                    } catch (_: RemoteException) {
                        disconnected()
                    }
                }
                override fun onServiceDisconnected(name: ComponentName) = disconnected()
                override fun onBindingDied(name: ComponentName) = disconnected()
                override fun onNullBinding(name: ComponentName) = disconnected()
            }
            check(context.bindService(Intent(context, service), connection, Context.BIND_AUTO_CREATE)) {
                context.getString(R.string.pingFailedGeneric)
            }
            try {
                response.await()
            } finally {
                active = false
                withContext(NonCancellable) {
                    if (sent && !finished.isCompleted) {
                        try {
                            worker?.send(Message.obtain(null, PingService.CANCEL).apply {
                                data = Bundle().apply { putLong(PingService.ID, id) }
                            })
                        } catch (_: RemoteException) {
                            disconnected()
                        }
                        // Keep the file, binding and worker slot until native teardown is acknowledged.
                        finished.await()
                    }
                    context.unbindService(connection)
                }
            }
        }
}
