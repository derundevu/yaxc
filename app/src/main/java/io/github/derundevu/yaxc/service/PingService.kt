package io.github.derundevu.yaxc.service

import XrayCore.XrayCore
import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import io.github.derundevu.yaxc.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Xray has process-global dialer/logger state; it must not share the VPN's process. */
abstract class PingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val requests = mutableSetOf<Long>()
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { message ->
        val id = message.data.getLong(ID)
        when (message.what) {
            START -> startMeasurement(id, message.data, message.replyTo)
            CANCEL -> if (id in requests) cancelMeasurement(id)
        }
        true
    })

    override fun onBind(intent: Intent): IBinder = messenger.binder

    private fun startMeasurement(id: Long, data: Bundle, reply: Messenger?) {
        if (reply == null) return
        // A duplicate START must not acknowledge an ID whose native request is still running.
        if (id in requests) return
        // The client waits for cancellation acknowledgement before submitting its next request.
        if (requests.isNotEmpty()) {
            sendResult(reply, id, error = getString(R.string.pingFailedGeneric))
            return
        }
        requests.add(id)
        try {
            prepareMeasurement(id)
        } catch (error: Exception) {
            requests.remove(id)
            sendResult(reply, id, error = error.message ?: getString(R.string.pingFailedGeneric))
            return
        }
        scope.launch {
            try {
                val result = withContext(nativeDispatcher) {
                    measurePrepared(id, data)
                }
                sendResult(reply, id, result = result)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                sendResult(reply, id, error = error.message ?: getString(R.string.pingFailedGeneric))
            } finally {
                requests.remove(id)
            }
        }
    }

    protected open fun prepareMeasurement(id: Long) = XrayCore.preparePing(id)

    protected open fun measurePrepared(id: Long, data: Bundle): String = XrayCore.pingPrepared(
        id,
        filesDir.absolutePath,
        data.getString(CONFIG).orEmpty(),
        data.getInt(TIMEOUT).toLong(),
        data.getString(URL).orEmpty(),
        data.getString(PROXY).orEmpty(),
        data.getString(TYPE).orEmpty(),
    )

    protected open fun cancelMeasurement(id: Long) = XrayCore.cancelPing(id)

    private fun sendResult(reply: Messenger, id: Long, result: String? = null, error: String? = null) {
        val message = Message.obtain(null, RESULT).apply {
            data = Bundle().apply {
                putLong(ID, id)
                putString(RESPONSE, result)
                putString(ERROR, error)
            }
        }
        try {
            reply.send(message)
        } catch (_: RemoteException) {
            // The requesting Activity/process no longer exists; the native request already ended.
        }
    }

    override fun onUnbind(intent: Intent): Boolean {
        requests.forEach(::cancelMeasurement)
        return false
    }

    override fun onDestroy() {
        requests.forEach(::cancelMeasurement)
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        // Shared across recreation within each worker process; never overlap its native cores.
        private val nativeDispatcher = Dispatchers.IO.limitedParallelism(1)
        const val START = 1
        const val CANCEL = 2
        const val RESULT = 3
        const val ID = "id"
        const val CONFIG = "config"
        const val TIMEOUT = "timeout"
        const val URL = "url"
        const val PROXY = "proxy"
        const val TYPE = "type"
        const val RESPONSE = "response"
        const val ERROR = "error"
    }
}

class PingWorker1Service : PingService()
class PingWorker2Service : PingService()
class PingWorker3Service : PingService()
class PingWorker4Service : PingService()
class PingWorker5Service : PingService()
