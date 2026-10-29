package io.github.derundevu.yaxc.helper

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.DeadObjectException
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import io.github.derundevu.yaxc.service.ValidationService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLong

/** Full Xray construction must not share the VPN's process-global native state. */
object XrayValidationHelper {
    private val requests = Mutex()
    private val requestIds = AtomicLong()

    /** Returns the native diagnostic verbatim; an empty diagnostic means the config is valid. */
    suspend fun validate(context: Context, config: String): String = withContext(Dispatchers.IO) {
        requests.withLock {
            currentCoroutineContext().ensureActive()
            val application = context.applicationContext
            val configFile = File.createTempFile("validation-", ".json", application.cacheDir)
            var outputFile: File? = null
            var failure: Throwable? = null
            try {
                val output = File.createTempFile("validation-", ".txt", application.cacheDir)
                outputFile = output
                configFile.writeText(config)
                currentCoroutineContext().ensureActive()
                val failed = awaitWorker(application, configFile, output)
                val diagnostic = output.readText()
                if (failed) throw IOException(diagnostic)
                diagnostic
            } catch (error: Throwable) {
                failure = error
                throw error
            } finally {
                try {
                    deleteFiles(configFile, outputFile)
                } catch (cleanup: Exception) {
                    if (failure != null) failure.addSuppressed(cleanup) else throw cleanup
                }
            }
        }
    }

    private fun deleteFiles(config: File, output: File?) {
        var failure: Exception? = null
        try {
            if (output != null) Files.deleteIfExists(output.toPath())
        } catch (error: Exception) {
            failure = error
        }
        try {
            Files.deleteIfExists(config.toPath())
        } catch (error: Exception) {
            if (failure != null) failure.addSuppressed(error) else failure = error
        }
        if (failure != null) throw failure
    }

    private suspend fun awaitWorker(context: Context, config: File, output: File): Boolean =
        withContext(Dispatchers.Main.immediate) {
            val caller = currentCoroutineContext()
            val id = requestIds.incrementAndGet()
            val response = CompletableDeferred<Boolean>()
            val finished = CompletableDeferred<Unit>()
            val handler = Handler(Looper.getMainLooper())
            var linkedBinder: IBinder? = null
            var active = true
            var sent = false
            var bindingAttempted = false
            var bound = false
            var failure: Throwable? = null

            fun died(error: Exception) {
                response.completeExceptionally(error)
                finished.complete(Unit)
            }

            fun bindingFailed(error: Exception) {
                response.completeExceptionally(error)
                // A dead binding alone does not prove that a live process stopped core.New.
                if (!sent || linkedBinder?.isBinderAlive == false) finished.complete(Unit)
            }

            val death = IBinder.DeathRecipient {
                handler.post { died(DeadObjectException()) }
            }
            val reply = Messenger(Handler(Looper.getMainLooper()) { message ->
                if (message.what == ValidationService.RESULT &&
                    message.data.containsKey(ValidationService.ID) &&
                    message.data.getLong(ValidationService.ID) == id
                ) {
                    when (message.data.getInt(ValidationService.STATUS, -1)) {
                        ValidationService.STATUS_OK -> response.complete(false)
                        ValidationService.STATUS_ERROR -> response.complete(true)
                        ValidationService.STATUS_WRITE_ERROR -> response.completeExceptionally(
                            IOException("Unable to write validation response: ${message.data.getString(ValidationService.ERROR)}")
                        )
                        else -> response.completeExceptionally(IOException("Invalid validation worker response"))
                    }
                    finished.complete(Unit)
                }
                true
            })
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) {
                    // Cancellation before connection must never submit native work.
                    if (!active || sent || !caller.isActive || response.isCompleted) return
                    try {
                        service.linkToDeath(death, 0)
                        linkedBinder = service
                        Messenger(service).send(Message.obtain(null, ValidationService.VALIDATE).apply {
                            replyTo = reply
                            data = Bundle().apply {
                                putLong(ValidationService.ID, id)
                                putString(ValidationService.CONFIG, config.absolutePath)
                                putString(ValidationService.OUTPUT, output.absolutePath)
                            }
                        })
                        sent = true
                    } catch (error: Exception) {
                        // Messenger is one-way: a failed send did not enqueue native work.
                        died(error)
                    }
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    // Android reports this when the service's hosting process is gone.
                    died(IOException("Validation worker disconnected"))
                }

                override fun onBindingDied(name: ComponentName) {
                    bindingFailed(IOException("Validation worker binding died"))
                }

                override fun onNullBinding(name: ComponentName) {
                    bindingFailed(IOException("Validation worker returned a null binding"))
                }
            }
            try {
                caller.ensureActive()
                bindingAttempted = true
                bound = context.bindService(
                    Intent(context, ValidationService::class.java), connection, Context.BIND_AUTO_CREATE
                )
                if (!bound) throw IOException("Unable to bind validation worker")
                response.await()
            } catch (error: Throwable) {
                failure = error
                throw error
            } finally {
                active = false
                try {
                    withContext(NonCancellable) {
                        // core.New cannot be interrupted. Retain the binding, files and mutex until
                        // its matching reply or actual worker death, even when the caller cancels.
                        if (sent && !finished.isCompleted) finished.await()
                        try {
                            linkedBinder?.unlinkToDeath(death, 0)
                        } finally {
                            if (bindingAttempted) {
                                try {
                                    context.unbindService(connection)
                                } catch (error: IllegalArgumentException) {
                                    // A failed bind may not have registered the connection.
                                    if (bound) throw error
                                }
                            }
                        }
                    }
                } catch (cleanup: Throwable) {
                    if (failure != null) failure.addSuppressed(cleanup) else throw cleanup
                }
            }
        }
}
