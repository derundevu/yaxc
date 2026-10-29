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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Only constructs and closes Xray configs; never starts a core or touches the VPN. */
class ValidationService : Service() {
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { message ->
        if (message.what == VALIDATE && message.replyTo != null) {
            validate(message.data, message.replyTo)
        }
        true
    })

    override fun onBind(intent: Intent): IBinder = messenger.binder

    private fun validate(data: Bundle, reply: Messenger) {
        val id = data.getLong(ID)
        val config = data.getString(CONFIG)
        val output = data.getString(OUTPUT)
        val application = applicationContext
        nativeScope.launch {
            var status = STATUS_OK
            val diagnostic = try {
                requireNotNull(config) { "Missing validation config path" }
                XrayCore.validateInWorker(application.filesDir.absolutePath, config)
            } catch (error: Exception) {
                status = STATUS_ERROR
                "${error.javaClass.name}: ${error.message.orEmpty()}"
            }
            var outputError: String? = null
            try {
                requireNotNull(output) { "Missing validation output path" }
                File(output).writeText(diagnostic)
            } catch (error: Exception) {
                status = STATUS_WRITE_ERROR
                outputError = error.javaClass.name
            }
            withContext(Dispatchers.Main.immediate) {
                sendResult(reply, id, status, outputError)
            }
        }
    }

    companion object {
        // Process lifetime, not Service lifetime: unbind/destruction cannot cancel core.New.
        // Every recreated instance shares this dispatcher, so native construction never overlaps.
        private val nativeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

        const val VALIDATE = 1
        const val RESULT = 2
        const val ID = "id"
        const val CONFIG = "config"
        const val OUTPUT = "output"
        const val STATUS = "status"
        const val ERROR = "error"
        const val STATUS_OK = 0
        const val STATUS_ERROR = 1
        const val STATUS_WRITE_ERROR = 2

        private fun sendResult(reply: Messenger, id: Long, status: Int, outputError: String?) {
            val message = Message.obtain(null, RESULT).apply {
                data = Bundle().apply {
                    putLong(ID, id)
                    putInt(STATUS, status)
                    putString(ERROR, outputError)
                }
            }
            try {
                reply.send(message)
            } catch (_: RemoteException) {
                // The client process is gone; native construction and output writing already ended.
            }
        }
    }
}
