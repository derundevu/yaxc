package io.github.derundevu.yaxc.helper

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger
import io.github.derundevu.yaxc.service.ValidationService
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class XrayValidationHelperTest {
    private lateinit var context: WorkerContext
    private lateinit var supervisor: CompletableJob
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        context = WorkerContext(RuntimeEnvironment.getApplication())
        supervisor = SupervisorJob()
        scope = CoroutineScope(supervisor + Dispatchers.Default)
    }

    @After
    fun tearDown() {
        scope.cancel()
        context.bindings.filterNot { it.unbound }.forEach {
            it.connection.onServiceDisconnected(context.component)
        }
        pumpUntil { supervisor.isCompleted }
        context.directory.deleteRecursively()
    }

    @Test
    fun cancellationBeforeConnectionUnbindsAndNeverSubmitsLateWork() {
        val call = validate()
        pumpUntil { context.bindings.size == 1 }
        val binding = context.bindings.single()
        call.cancel()
        pumpUntil { call.isCompleted && binding.unbound }
        binding.connection.onServiceConnected(context.component, context.worker.binder)
        idleMain()
        assertTrue(call.isCancelled)
        assertTrue(context.requests.isEmpty())
        assertTrue(context.directory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun cancelledNativeRequestRetainsFilesBindingAndSlotUntilMatchingCompletion() {
        val call = validate()
        val request = connectNext()
        call.cancel()
        val queued = validate()
        idleMain()
        assertFalse(call.isCompleted)
        assertTrue(request.config.exists())
        assertTrue(request.output.exists())
        assertFalse(context.bindings.single().unbound)
        assertEquals(1, context.bindings.size)

        request.reply(request.id + 1)
        idleMain()
        assertFalse(call.isCompleted)
        assertTrue(request.config.exists())

        queued.cancel()
        pumpUntil { queued.isCompleted }
        request.complete("")
        pumpUntil { call.isCompleted }
        assertTrue(call.isCancelled)
        assertTrue(context.bindings.single().unbound)
        assertFalse(request.config.exists())
        assertFalse(request.output.exists())
        assertEquals(1, context.bindings.size)

        val next = validate()
        connectNext().complete("still usable")
        pumpUntil { next.isCompleted }
        assertEquals("still usable", runBlocking { next.await().getOrThrow() })
    }

    @Test
    fun workerDeathAcknowledgesCancelledNativeRequestAndReleasesQueue() {
        val call = validate()
        val request = connectNext()
        call.cancel()
        val next = validate()
        context.bindings.single().connection.onServiceDisconnected(context.component)
        pumpUntil { call.isCompleted && context.bindings.size == 2 }
        assertTrue(call.isCancelled)
        assertFalse(request.config.exists())
        assertFalse(request.output.exists())
        assertTrue(context.bindings.first().unbound)
        connectNext().complete("")
        pumpUntil { next.isCompleted }
        assertEquals("", runBlocking { next.await().getOrThrow() })
    }

    @Test
    fun deadBindingInLiveProcessWaitsForNativeCompletionBeforeThrowing() {
        val call = validate()
        val request = connectNext()
        val binding = context.bindings.single()
        binding.connection.onBindingDied(context.component)
        idleMain()
        assertFalse(call.isCompleted)
        assertFalse(binding.unbound)
        assertTrue(request.config.exists())
        request.complete("")
        pumpUntil { call.isCompleted }
        val error = runBlocking { call.await().exceptionOrNull() }
        assertTrue(error is IOException)
        assertTrue(binding.unbound)
        assertFalse(request.config.exists())
        assertFalse(request.output.exists())
    }

    @Test
    fun failedAndNullBindingsCleanUpAndLeaveSlotUsable() {
        context.bindResult = false
        val failed = validate()
        pumpUntil { failed.isCompleted }
        assertTrue(runBlocking { failed.await().exceptionOrNull() } is IOException)
        assertTrue(context.bindings.single().unbound)
        assertTrue(context.directory.listFiles().orEmpty().isEmpty())

        context.bindResult = true
        val nullBinding = validate()
        pumpUntil { context.bindings.size == 2 }
        context.bindings.last().connection.onNullBinding(context.component)
        pumpUntil { nullBinding.isCompleted }
        assertTrue(runBlocking { nullBinding.await().exceptionOrNull() } is IOException)
        assertTrue(context.bindings.last().unbound)
        assertTrue(context.directory.listFiles().orEmpty().isEmpty())

        val next = validate()
        connectNext().complete("")
        pumpUntil { next.isCompleted }
        assertEquals("", runBlocking { next.await().getOrThrow() })
    }

    @Test
    fun workerAndOutputFailuresThrowInsteadOfBecomingValidationDiagnostics() {
        val failed = validate()
        connectNext().complete("java.io.IOException: synthetic worker error", ValidationService.STATUS_ERROR)
        pumpUntil { failed.isCompleted }
        val error = runBlocking { failed.await().exceptionOrNull() }
        assertTrue(error is IOException)

        val outputFailed = validate()
        connectNext().reply(status = ValidationService.STATUS_WRITE_ERROR)
        pumpUntil { outputFailed.isCompleted }
        assertTrue(runBlocking { outputFailed.await().exceptionOrNull() } is IOException)
        assertTrue(context.directory.listFiles().orEmpty().isEmpty())
    }

    private fun validate(): Deferred<Result<String>> = scope.async {
        // Keep expected failures local instead of letting async cancel unrelated test requests.
        runCatching { XrayValidationHelper.validate(context, CONFIG) }
    }

    private fun connectNext(): Request {
        pumpUntil { context.bindings.any { !it.connected && !it.unbound } }
        val binding = context.bindings.first { !it.connected && !it.unbound }
        binding.connected = true
        val count = context.requests.size
        binding.connection.onServiceConnected(context.component, context.worker.binder)
        pumpUntil { context.requests.size == count + 1 }
        return context.requests.last()
    }

    private fun idleMain() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun pumpUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition()) {
            idleMain()
            check(System.nanoTime() < deadline) { "Validation coroutine did not reach the expected state" }
            Thread.sleep(1)
        }
        idleMain()
    }

    private class Binding(val connection: ServiceConnection) {
        var connected = false
        var unbound = false
    }

    private class Request(val message: Message) {
        val id = message.data.getLong(ValidationService.ID)
        val config = File(message.data.getString(ValidationService.CONFIG)!!)
        val output = File(message.data.getString(ValidationService.OUTPUT)!!)

        fun complete(diagnostic: String, status: Int = ValidationService.STATUS_OK) {
            output.writeText(diagnostic)
            reply(status = status)
        }

        fun reply(requestId: Long = id, status: Int = ValidationService.STATUS_OK) {
            message.replyTo.send(Message.obtain(null, ValidationService.RESULT).apply {
                data = Bundle().apply {
                    putLong(ValidationService.ID, requestId)
                    putInt(ValidationService.STATUS, status)
                    if (status == ValidationService.STATUS_WRITE_ERROR) {
                        putString(ValidationService.ERROR, IOException::class.java.name)
                    }
                }
            })
        }
    }

    private class WorkerContext(base: Context) : ContextWrapper(base) {
        val directory = Files.createTempDirectory(base.cacheDir.toPath(), "validation-test-").toFile()
        val component = ComponentName(base, ValidationService::class.java)
        val bindings = mutableListOf<Binding>()
        val requests = mutableListOf<Request>()
        var bindResult = true
        val worker = Messenger(Handler(Looper.getMainLooper()) { message ->
            requests.add(Request(Message.obtain(message)))
            true
        })

        override fun getApplicationContext(): Context = this
        override fun getCacheDir(): File = directory

        override fun bindService(intent: Intent, connection: ServiceConnection, flags: Int): Boolean {
            bindings.add(Binding(connection))
            return bindResult
        }

        override fun unbindService(connection: ServiceConnection) {
            bindings.single { it.connection === connection }.unbound = true
        }
    }

    companion object {
        private const val CONFIG = "{\"synthetic-validation-config\":true}"
    }
}
