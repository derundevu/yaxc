package io.github.derundevu.yaxc.helper

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.DeadObjectException
import android.os.Handler
import android.os.IBinder
import android.os.IInterface
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Parcel
import android.os.RemoteException
import android.util.Base64
import io.github.derundevu.yaxc.R
import io.github.derundevu.yaxc.Settings
import io.github.derundevu.yaxc.database.Config as GlobalConfig
import io.github.derundevu.yaxc.database.Profile
import io.github.derundevu.yaxc.service.PingService
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
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
import java.nio.file.Files
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class XrayBatchPingHelperTest : HelperSettingsTest() {
    private lateinit var context: WorkerContext
    private lateinit var supervisor: CompletableJob
    private lateinit var scope: CoroutineScope
    private var profileId = 0L

    @Before
    fun setUpWorkers() {
        context = WorkerContext(RuntimeEnvironment.getApplication())
        supervisor = SupervisorJob()
        scope = CoroutineScope(supervisor + Dispatchers.IO)
    }

    @After
    fun tearDownWorkers() {
        scope.cancel()
        context.bindings.filterNot { it.unbound }.forEach {
            it.connection.onServiceDisconnected(it.component)
        }
        pumpUntil { supervisor.isCompleted }
        context.directory.deleteRecursively()
    }

    @Test
    fun fiveDistinctWorkersHoldTheSixthAndOutOfOrderRepliesStayWithTheirProfiles() {
        val calls = (1L..6L).associateWith { measure(it) }
        val requests = List(5) { connectNext() }
        assertEquals(5, context.bindings.size)
        assertEquals(5, requests.map { it.binding.component.className }.toSet().size)
        assertEquals(5, requests.map { it.config.absolutePath }.toSet().size)
        assertEquals(5, requests.map { it.id }.toSet().size)
        assertTrue(requests.all { it.config.exists() })
        assertTrue(calls.values.none { it.isCompleted })

        val released = requests.last()
        released.success(released.profileId * 11)
        pumpUntil { context.bindings.size == 6 }
        assertTrue(released.binding.unbound)
        assertFalse(released.config.exists())
        val sixth = connectNext()
        assertEquals(released.binding.component, sixth.binding.component)
        val all = requests + sixth
        all.filterNot { it === released }.reversed().forEach { it.success(it.profileId * 11) }
        pumpUntil { calls.values.all { it.isCompleted } }
        calls.forEach { (id, call) ->
            assertEquals(context.getString(R.string.pingDelayMs, id * 11), result(call).getOrThrow())
        }
        assertClean()
    }

    @Test
    fun queuedCancellationDoesNotBindOrLoseAnyOfTheFiveSlots() {
        val active = List(5) { measure() }
        val requests = List(5) { connectNext() }
        val queued = measure()
        queued.cancel()
        pumpUntil { queued.isCompleted }
        assertTrue(queued.isCancelled)
        assertEquals(5, context.bindings.size)
        requests.forEach { it.success(1) }
        pumpUntil { active.all { it.isCompleted } }
        assertReusableAllSlots()
    }

    @Test
    fun cancelledRequestKeepsSlotFileAndBindingUntilItsOwnCompletionAndIgnoresLateReplies() {
        val active = List(5) { measure() }
        val requests = List(5) { connectNext() }
        val cancelledRequest = requests.first()
        // Launch order is not worker assignment order; find the owning call by profile identity.
        val ownerIndex = cancelledRequest.profileId.toInt() - 1
        val owner = active[ownerIndex]
        owner.cancel()
        pumpUntil { context.cancellations.contains(cancelledRequest.id) }
        val queued = measure(100)
        cancelledRequest.reply(requestId = cancelledRequest.id + 1000)
        cancelledRequest.reply(what = PingService.START)
        cancelledRequest.reply(includeId = false)
        idleMain()
        assertFalse(owner.isCompleted)
        assertFalse(cancelledRequest.binding.unbound)
        assertTrue(cancelledRequest.config.exists())
        assertEquals(5, context.bindings.size)
        assertEquals(listOf(cancelledRequest.id), context.cancellations)

        cancelledRequest.success(999)
        pumpUntil { owner.isCompleted && context.bindings.size == 6 }
        assertTrue(owner.isCancelled)
        assertFalse(cancelledRequest.config.exists())
        val next = connectNext()
        cancelledRequest.success(777)
        cancelledRequest.reply(requestId = next.id, encoded = encodedSuccess(666))
        idleMain()
        assertFalse(queued.isCompleted)
        assertTrue(next.config.exists())
        next.success(123)
        requests.filterNot { it === cancelledRequest }.forEach { it.success(it.profileId) }
        pumpUntil { active.all { it.isCompleted } && queued.isCompleted }
        assertEquals(context.getString(R.string.pingDelayMs, 123L), result(queued).getOrThrow())
        active.forEachIndexed { index, call ->
            if (call !== owner) assertEquals(context.getString(R.string.pingDelayMs, index + 1L), result(call).getOrThrow())
        }
        assertClean()
    }

    @Test
    fun cancellationBeforeConnectionUnbindsAndLateConnectionNeverStartsNativeWork() {
        val call = measure()
        pumpUntil { context.bindings.size == 1 }
        val binding = context.bindings.single()
        assertEquals(1, context.configFiles().size)
        call.cancel()
        pumpUntil { call.isCompleted && binding.unbound }
        binding.connect()
        idleMain()
        assertTrue(call.isCancelled)
        assertTrue(context.requests.isEmpty())
        assertClean()
        assertReusableAllSlots()
    }

    @Test
    fun duplicateConnectionDoesNotSubmitTheSameNativeRequestTwice() {
        val call = measure()
        val request = connectNext()
        request.binding.connection.onServiceConnected(request.binding.component, request.binding.binder)
        idleMain()
        assertEquals(1, context.requests.size)
        request.success(17)
        pumpUntil { call.isCompleted }
        request.success(999)
        idleMain()
        assertEquals(context.getString(R.string.pingDelayMs, 17L), result(call).getOrThrow())
        assertEquals(0, request.binding.binder.deaths.size)
        assertClean()
    }

    @Test
    fun liveBindingDeathCancelsButRetainsOwnershipUntilNativeAcknowledgement() {
        val call = measure()
        val request = connectNext()
        request.binding.connection.onBindingDied(request.binding.component)
        pumpUntil { context.cancellations.contains(request.id) }
        assertFalse(call.isCompleted)
        assertFalse(request.binding.unbound)
        assertTrue(request.config.exists())
        request.success(1)
        pumpUntil { call.isCompleted }
        assertTrue(result(call).exceptionOrNull() is IllegalStateException)
        assertClean()
        assertReusableAllSlots()
    }

    @Test
    fun binderDeathOrProcessDisconnectionReleasesCancelledOwnershipAndQueue() {
        for (binderDeath in listOf(true, false)) {
            val active = List(5) { measure() }
            val requests = List(5) { connectNext() }
            val dying = requests.first()
            val owner = active[dying.profileId.toInt() - requests.minOf { it.profileId }.toInt()]
            owner.cancel()
            pumpUntil { context.cancellations.contains(dying.id) }
            val queued = measure()
            if (binderDeath) dying.binding.binder.die()
            else dying.binding.connection.onServiceDisconnected(dying.binding.component)
            pumpUntil { owner.isCompleted && context.bindings.any { !it.connected && !it.unbound } }
            assertTrue(owner.isCancelled)
            assertFalse(dying.config.exists())
            assertTrue(dying.binding.unbound)
            connectNext().success(44)
            requests.filterNot { it === dying }.forEach { it.success(1) }
            pumpUntil { queued.isCompleted && active.all { it.isCompleted } }
            assertEquals(context.getString(R.string.pingDelayMs, 44L), result(queued).getOrThrow())
            assertClean()
        }
        assertReusableAllSlots()
    }

    @Test
    fun failedThrowingAndNullBindingsAlwaysCleanUpAndLeaveAllSlotsUsable() {
        context.bindResult = false
        context.rejectUnbind = true
        val rejected = measure()
        pumpUntil { rejected.isCompleted }
        assertTrue(result(rejected).exceptionOrNull() is IllegalStateException)
        assertTrue(context.bindings.last().unbound)
        assertClean()
        context.bindResult = true
        context.rejectUnbind = false
        assertReusableAllSlots()

        context.bindError = SecurityException("synthetic bind rejection")
        val throwing = measure()
        pumpUntil { throwing.isCompleted }
        assertTrue(result(throwing).exceptionOrNull() is SecurityException)
        assertClean()
        context.bindError = null
        assertReusableAllSlots()

        val nullBinding = measure()
        pumpUntil { context.bindings.any { !it.connected && !it.unbound } }
        val binding = context.bindings.last()
        binding.connection.onNullBinding(binding.component)
        pumpUntil { nullBinding.isCompleted }
        assertTrue(result(nullBinding).exceptionOrNull() is IllegalStateException)
        binding.connect()
        idleMain()
        assertTrue(binding.unbound)
        assertClean()
        assertReusableAllSlots()
    }

    @Test
    fun deathRegistrationAndStartSendFailuresCleanUpWithoutHoldingSlots() {
        for (failLink in listOf(true, false)) {
            val call = measure()
            pumpUntil { context.bindings.any { !it.connected && !it.unbound } }
            val binding = context.bindings.last()
            if (failLink) binding.binder.failLink = true else binding.binder.failSend = true
            binding.connect()
            pumpUntil { call.isCompleted }
            assertTrue(result(call).exceptionOrNull() is RemoteException)
            assertTrue(binding.unbound)
            assertTrue(binding.binder.deaths.isEmpty())
            assertClean()
            assertReusableAllSlots()
        }
    }

    @Test
    fun failedCancelSendToLiveBinderDoesNotPretendNativeTeardownFinished() {
        val call = measure()
        val request = connectNext()
        request.binding.binder.failSend = true
        call.cancel()
        pumpUntil { request.binding.binder.failedSends == 1 }
        assertFalse(call.isCompleted)
        assertTrue(request.config.exists())
        assertFalse(request.binding.unbound)
        request.success(0)
        pumpUntil { call.isCompleted }
        assertTrue(call.isCancelled)
        assertClean()
        assertReusableAllSlots()
    }

    @Test
    fun deadObjectDuringCancellationAcknowledgesDeathWithoutWaitingForAnotherCallback() {
        val call = measure()
        val request = connectNext()
        request.binding.binder.failDeadSend = true
        call.cancel()
        pumpUntil { call.isCompleted }
        assertTrue(call.isCancelled)
        assertFalse(request.config.exists())
        assertTrue(request.binding.unbound)
        assertClean()
        assertReusableAllSlots()
    }

    @Test
    fun workerAndMalformedPayloadFailuresDoNotContaminateFollowingMeasurements() {
        val failures: List<(Request) -> Unit> = listOf(
            { it.reply(error = "synthetic worker failure") },
            { it.reply(encoded = "not valid base64!") },
            { it.reply(encoded = Base64.encodeToString("not json".toByteArray(), Base64.NO_WRAP)) },
        )
        for (fail in failures) {
            val call = measure()
            fail(connectNext())
            pumpUntil { call.isCompleted }
            assertTrue(result(call).isFailure)
            assertClean()
            assertReusableAllSlots()
        }
        for (payload in listOf("{\"success\":true,\"data\":-1}", "{\"success\":false}")) {
            val call = measure()
            connectNext().reply(encoded = Base64.encodeToString(payload.toByteArray(), Base64.NO_WRAP))
            pumpUntil { call.isCompleted }
            assertEquals(context.getString(R.string.pingFailedGeneric), result(call).getOrThrow())
            assertClean()
        }
        val nativeError = measure()
        connectNext().reply(encoded = Base64.encodeToString("{\"success\":false,\"error\":\"synthetic timeout\"}".toByteArray(), Base64.NO_WRAP))
        pumpUntil { nativeError.isCompleted }
        assertEquals("synthetic timeout", result(nativeError).getOrThrow())
        assertReusableAllSlots()
    }

    @Test
    fun invalidTimeoutAndConfigFailBeforeBindingAndReturnEveryWorkerSlot() {
        for (timeout in listOf(0, -1)) {
            val count = context.bindings.size
            val call = measure(options = XrayBatchPingHelper.Options(timeout, "http://127.0.0.1/probe", Settings.PingType.Get))
            pumpUntil { call.isCompleted }
            assertTrue(result(call).exceptionOrNull() is IllegalArgumentException)
            assertEquals(count, context.bindings.size)
            assertClean()
        }
        val count = context.bindings.size
        val malformed = measure(profile = Profile(id = 700, config = "not JSON"))
        pumpUntil { malformed.isCompleted }
        assertTrue(result(malformed).isFailure)
        assertEquals(count, context.bindings.size)
        assertClean()
        assertReusableAllSlots()
    }

    @Test
    fun runtimeConfigUsesPrivateLoopbackListenerAndFrozenOptionsWithoutMutatingProfile() {
        settings.transparentProxy = true
        settings.socksAddress = "0.0.0.0"
        settings.socksPort = "19080"
        val profile = profile(99)
        val original = profile.copy()
        val global = GlobalConfig(
            inbounds = "[{\"protocol\":\"http\",\"listen\":\"0.0.0.0\",\"port\":19081}]",
            inboundsMode = GlobalConfig.Mode.Replace,
            routing = "{\"rules\":[{\"domain\":[\"full:synthetic.invalid\"],\"outboundTag\":\"block\"}]}",
            routingMode = GlobalConfig.Mode.Replace,
        )
        val originalGlobal = global.copy()
        settings.pingTimeout = 7
        settings.pingAddress = "http://127.0.0.1:18881/frozen"
        settings.pingType = Settings.PingType.Tcp
        val options = XrayBatchPingHelper.Options.from(settings)
        settings.pingTimeout = 50
        settings.pingAddress = "http://127.0.0.1:18882/later"
        settings.pingType = Settings.PingType.Head
        val call = measure(profile = profile, global = global, options = options)
        val request = connectNext()
        val runtime = JSONObject(request.config.readText())
        val inbounds = runtime.getJSONArray("inbounds")
        assertEquals(1, inbounds.length())
        val listener = inbounds.getJSONObject(0)
        assertEquals("127.0.0.1", listener.getString("listen"))
        assertEquals("socks", listener.getString("protocol"))
        assertEquals("noauth", listener.getJSONObject("settings").getString("auth"))
        assertFalse(listener.getJSONObject("settings").getBoolean("udp"))
        assertFalse(listener.getJSONObject("settings").has("accounts"))
        assertEquals("socks5://127.0.0.1:${listener.getInt("port")}", request.message.data.getString(PingService.PROXY))
        assertTrue(listener.getInt("port") in 1..65535)
        assertEquals(7, request.message.data.getInt(PingService.TIMEOUT))
        assertEquals("http://127.0.0.1:18881/frozen", request.message.data.getString(PingService.URL))
        assertEquals("tcp", request.message.data.getString(PingService.TYPE))
        assertFalse(runtime.getJSONObject("routing").toString().contains("synthetic.invalid"))
        assertEquals("profile-99", runtime.getJSONArray("outbounds").getJSONObject(0).getString("tag"))
        assertEquals(original, profile)
        assertEquals(originalGlobal, global)
        request.success(0)
        pumpUntil { call.isCompleted }
        assertEquals(context.getString(R.string.pingDelayMs, 0L), result(call).getOrThrow())
        assertClean()
    }

    @Test
    fun nativeOmittedZeroDelayIsDisplayedAsSuccessfulZeroMilliseconds() {
        val call = measure()
        connectNext().reply(encoded = Base64.encodeToString("""{"success":true}""".toByteArray(), Base64.NO_WRAP))
        pumpUntil { call.isCompleted }
        assertEquals(context.getString(R.string.pingDelayMs, 0L), result(call).getOrThrow())
        assertClean()
        assertReusableAllSlots()
    }

    private fun measure(
        id: Long = ++profileId,
        profile: Profile = profile(id),
        global: GlobalConfig = GlobalConfig(),
        options: XrayBatchPingHelper.Options = XrayBatchPingHelper.Options(3, "http://127.0.0.1:18881/probe", Settings.PingType.Get),
    ): Deferred<Result<String>> = scope.async(start = CoroutineStart.UNDISPATCHED) {
        runCatching { XrayBatchPingHelper.measureProfileDelay(context, settings, global, profile, options) }
    }

    private fun profile(id: Long) = Profile(
        id = id,
        name = "synthetic-$id",
        config = """{"syntheticProfile":$id,"inbounds":[{"protocol":"http","listen":"0.0.0.0","port":19082}],"outbounds":[{"protocol":"freedom","tag":"profile-$id"}]}""",
    )

    private fun result(call: Deferred<Result<String>>): Result<String> = runBlocking { call.await() }

    private fun connectNext(): Request {
        pumpUntil { context.bindings.any { !it.connected && !it.unbound } }
        val binding = context.bindings.first { !it.connected && !it.unbound }
        val count = context.requests.size
        binding.connect()
        pumpUntil { context.requests.size == count + 1 }
        return context.requests.last()
    }

    private fun assertReusableAllSlots() {
        val calls = List(5) { measure() }
        val requests = List(5) { connectNext() }
        assertEquals(5, requests.map { it.binding.component }.toSet().size)
        requests.forEach { it.success(22) }
        pumpUntil { calls.all { it.isCompleted } }
        calls.forEach { assertEquals(context.getString(R.string.pingDelayMs, 22L), result(it).getOrThrow()) }
        assertClean()
    }

    private fun assertClean() {
        assertTrue(context.configFiles().isEmpty())
        assertTrue(context.bindings.all { it.unbound })
        assertTrue(context.bindings.all { it.binder.deaths.isEmpty() })
    }

    private fun idleMain() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun pumpUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition()) {
            idleMain()
            check(System.nanoTime() < deadline) { "Ping coroutine did not reach the expected state" }
            Thread.sleep(1)
        }
        idleMain()
    }

    private class Request(val message: Message, val binding: Binding) {
        val id = message.data.getLong(PingService.ID)
        val config = File(message.data.getString(PingService.CONFIG)!!)
        val profileId = JSONObject(config.readText()).getLong("syntheticProfile")

        fun success(delay: Long) = reply(encoded = encodedSuccess(delay))

        fun reply(
            requestId: Long = id,
            encoded: String = encodedSuccess(0),
            error: String? = null,
            what: Int = PingService.RESULT,
            includeId: Boolean = true,
        ) {
            message.replyTo.send(Message.obtain(null, what).apply {
                data = Bundle().apply {
                    if (includeId) putLong(PingService.ID, requestId)
                    putString(PingService.RESPONSE, encoded)
                    putString(PingService.ERROR, error)
                }
            })
        }
    }

    private class Binding(val component: ComponentName, val connection: ServiceConnection, context: WorkerContext) {
        var connected = false
        var unbound = false
        private val worker = Messenger(Handler(Looper.getMainLooper()) { message ->
            when (message.what) {
                PingService.START -> context.requests.add(Request(Message.obtain(message), this))
                PingService.CANCEL -> context.cancellations.add(message.data.getLong(PingService.ID))
            }
            true
        })
        val binder = WorkerBinder(worker.binder)

        fun connect() {
            connected = true
            connection.onServiceConnected(component, binder)
        }
    }

    private class WorkerBinder(private val delegate: IBinder) : IBinder by delegate {
        val deaths = mutableSetOf<IBinder.DeathRecipient>()
        var failLink = false
        var failSend = false
        var failDeadSend = false
        var failedSends = 0
        private var alive = true

        override fun queryLocalInterface(descriptor: String): IInterface? = null
        override fun isBinderAlive(): Boolean = alive
        override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) {
            if (failLink || !alive) throw DeadObjectException()
            deaths.add(recipient)
        }
        override fun unlinkToDeath(recipient: IBinder.DeathRecipient, flags: Int): Boolean = deaths.remove(recipient)
        override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (!alive) throw DeadObjectException()
            if (failDeadSend) throw DeadObjectException()
            if (failSend) {
                failedSends++
                throw RemoteException("synthetic transaction failure")
            }
            return delegate.transact(code, data, reply, flags)
        }
        fun die() {
            alive = false
            deaths.toList().forEach { it.binderDied() }
        }
    }

    private class WorkerContext(base: Context) : ContextWrapper(base) {
        val directory = Files.createTempDirectory(base.cacheDir.toPath(), "batch-ping-test-").toFile()
        val bindings = mutableListOf<Binding>()
        val requests = mutableListOf<Request>()
        val cancellations = mutableListOf<Long>()
        var bindResult = true
        var bindError: Exception? = null
        var rejectUnbind = false

        override fun getApplicationContext(): Context = this
        override fun getCacheDir(): File = directory
        override fun bindService(intent: Intent, connection: ServiceConnection, flags: Int): Boolean {
            bindings.add(Binding(intent.component!!, connection, this))
            bindError?.let { throw it }
            return bindResult
        }
        override fun unbindService(connection: ServiceConnection) {
            bindings.single { it.connection === connection }.unbound = true
            if (rejectUnbind) throw IllegalArgumentException("synthetic unregistered connection")
        }
        fun configFiles(): List<File> = File(directory, "batch-ping").listFiles().orEmpty().toList()
    }

    companion object {
        private fun encodedSuccess(delay: Long): String = Base64.encodeToString(
            (if (delay == 0L) "{\"success\":true}" else "{\"success\":true,\"data\":$delay}").toByteArray(),
            Base64.NO_WRAP,
        )
    }
}
