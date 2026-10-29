package io.github.derundevu.yaxc.service

import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.DeadObjectException
import android.os.Handler
import android.os.IBinder
import android.os.IInterface
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Parcel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class PingServiceTest {
    private val controllers = mutableListOf<ServiceController<ControlledPingService>>()
    private val destroyed = mutableSetOf<ServiceController<ControlledPingService>>()
    private val replies = mutableListOf<Message>()
    private val reply = Messenger(Handler(Looper.getMainLooper()) { message ->
        replies.add(Message.obtain(message))
        true
    })

    @After
    fun tearDown() {
        controllers.forEach { controller ->
            controller.get().tasks.values.forEach { it.finish("synthetic cleanup") }
            if (controller !in destroyed) controller.destroy()
        }
        pumpUntil { controllers.flatMap { it.get().tasks.values }.all { !it.started || it.finished } }
    }

    @Test
    fun anOccupiedWorkerRejectsAnotherIdWithoutAcknowledgingOrReplacingItsOwner() {
        val service = create()
        start(service, 41)
        pumpUntil { service.tasks[41]?.started == true }
        start(service, 42)
        idleMain()
        assertEquals(listOf(41L), service.prepared)
        assertEquals(1, replies.size)
        assertEquals(42L, replies.single().data.getLong(PingService.ID))
        assertTrue(replies.single().data.containsKey(PingService.ERROR))
        assertNull(replies.single().data.getString(PingService.RESPONSE))
        assertFalse(service.tasks.getValue(41).finished)

        service.tasks.getValue(41).finish("completed owner")
        pumpUntil { replies.size == 2 }
        val ownerReply = replies.single { it.data.getLong(PingService.ID) == 41L }
        assertEquals("completed owner", ownerReply.data.getString(PingService.RESPONSE))
        assertNull(ownerReply.data.getString(PingService.ERROR))
        start(service, 43)
        pumpUntil { service.tasks[43]?.started == true }
        service.tasks.getValue(43).finish("next owner")
        pumpUntil { replies.size == 3 }
        assertEquals(listOf(41L, 43L), service.prepared)
        assertEquals(43L, replies.last().data.getLong(PingService.ID))
    }

    @Test
    fun duplicateStartAndWrongCancelCannotCompleteOrCancelAnotherRequest() {
        val service = create()
        start(service, 51)
        pumpUntil { service.tasks[51]?.started == true }
        start(service, 51)
        command(service, PingService.CANCEL, 999)
        command(service, PingService.RESULT, 51)
        idleMain()
        assertEquals(listOf(51L), service.prepared)
        assertTrue(service.cancelled.isEmpty())
        assertTrue(replies.isEmpty())

        command(service, PingService.CANCEL, 51)
        idleMain()
        assertEquals(listOf(51L), service.cancelled)
        assertTrue(replies.isEmpty())
        assertFalse(service.tasks.getValue(51).finished)
        service.tasks.getValue(51).finish("native cancellation acknowledged")
        pumpUntil { replies.size == 1 }
        assertEquals(51L, replies.single().data.getLong(PingService.ID))
        assertEquals("native cancellation acknowledged", replies.single().data.getString(PingService.RESPONSE))
        command(service, PingService.CANCEL, 51)
        idleMain()
        assertEquals(listOf(51L), service.cancelled)
    }

    @Test
    fun missingReplyNeverPreparesNativeWorkAndUnbindCancelsOnlyTheActiveOwner() {
        val service = create()
        start(service, 61, withReply = false)
        idleMain()
        assertTrue(service.prepared.isEmpty())
        assertTrue(service.tasks.isEmpty())
        start(service, 62)
        pumpUntil { service.tasks[62]?.started == true }
        assertFalse(service.onUnbind(Intent()))
        assertEquals(listOf(62L), service.cancelled)
        assertTrue(replies.isEmpty())
        service.tasks.getValue(62).finish("stopped after unbind")
        pumpUntil { replies.size == 1 }
        assertEquals(62L, replies.single().data.getLong(PingService.ID))
        service.onUnbind(Intent())
        assertEquals(listOf(62L), service.cancelled)
    }

    @Test
    fun preparationAndMeasurementExceptionsReportTheirOwnIdAndLeaveWorkerReusable() {
        val service = create()
        service.preparationFailure = IllegalStateException("synthetic preparation failure")
        start(service, 71)
        pumpUntil { replies.size == 1 }
        assertEquals(71L, replies.single().data.getLong(PingService.ID))
        assertEquals("synthetic preparation failure", replies.single().data.getString(PingService.ERROR))
        assertTrue(service.tasks.isEmpty())

        service.preparationFailure = null
        start(service, 72)
        pumpUntil { service.tasks[72]?.started == true }
        service.tasks.getValue(72).fail(IllegalArgumentException("synthetic native failure"))
        pumpUntil { replies.size == 2 }
        assertEquals(72L, replies.last().data.getLong(PingService.ID))
        assertEquals("synthetic native failure", replies.last().data.getString(PingService.ERROR))
        assertNull(replies.last().data.getString(PingService.RESPONSE))
        start(service, 73)
        pumpUntil { service.tasks[73]?.started == true }
        service.tasks.getValue(73).finish("recovered")
        pumpUntil { replies.size == 3 }
        assertEquals(73L, replies.last().data.getLong(PingService.ID))
        assertEquals("recovered", replies.last().data.getString(PingService.RESPONSE))
    }

    @Test
    fun deadRequesterDoesNotLeaveFinishedNativeWorkOccupyingTheService() {
        val service = create()
        var failedReplies = 0
        val deadReply = Messenger(object : IBinder by reply.binder {
            override fun queryLocalInterface(descriptor: String): IInterface? = null
            override fun transact(code: Int, data: Parcel, response: Parcel?, flags: Int): Boolean {
                failedReplies++
                throw DeadObjectException()
            }
        })
        Messenger(service.onBind(Intent())).send(Message.obtain(null, PingService.START).apply {
            replyTo = deadReply
            data = Bundle().apply { putLong(PingService.ID, 74) }
        })
        pumpUntil { service.tasks[74]?.started == true }
        service.tasks.getValue(74).finish("requester is gone")
        pumpUntil { failedReplies == 1 }
        assertTrue(replies.isEmpty())
        start(service, 75)
        pumpUntil { service.tasks[75]?.started == true }
        service.tasks.getValue(75).finish("new requester")
        pumpUntil { replies.size == 1 }
        assertEquals(listOf(74L, 75L), service.prepared)
        assertEquals(75L, replies.single().data.getLong(PingService.ID))
        assertEquals("new requester", replies.single().data.getString(PingService.RESPONSE))
    }

    @Test
    fun destroyedServiceCancelsWithoutSendingEarlyReplyAndRecreatedServiceCannotOverlapNativeCore() {
        val first = create()
        start(first, 81)
        pumpUntil { first.tasks[81]?.started == true }
        val firstController = controllers.single()
        firstController.destroy()
        destroyed.add(firstController)
        assertEquals(listOf(81L), first.cancelled)
        assertTrue(replies.isEmpty())

        val second = create()
        start(second, 82)
        pumpUntil { second.tasks.containsKey(82) }
        assertFalse(second.tasks.getValue(82).started)
        assertFalse(first.tasks.getValue(81).finished)
        assertTrue(replies.isEmpty())
        first.tasks.getValue(81).finish("obsolete destroyed result")
        pumpUntil { second.tasks.getValue(82).started }
        second.tasks.getValue(82).finish("new service result")
        pumpUntil { replies.size == 1 }
        assertEquals(82L, replies.single().data.getLong(PingService.ID))
        assertEquals("new service result", replies.single().data.getString(PingService.RESPONSE))
        assertTrue(first.tasks.getValue(81).finished)
    }

    private fun create(): ControlledPingService {
        val controller = Robolectric.buildService(ControlledPingService::class.java).create()
        controllers.add(controller)
        return controller.get()
    }

    private fun start(service: ControlledPingService, id: Long, withReply: Boolean = true) {
        command(service, PingService.START, id, withReply)
    }

    private fun command(service: ControlledPingService, what: Int, id: Long, withReply: Boolean = false) {
        Messenger(service.onBind(Intent())).send(Message.obtain(null, what).apply {
            if (withReply) replyTo = reply
            data = Bundle().apply {
                putLong(PingService.ID, id)
                putString(PingService.CONFIG, "/synthetic/profile-$id.json")
                putInt(PingService.TIMEOUT, 3)
                putString(PingService.URL, "http://127.0.0.1:18881/probe")
                putString(PingService.PROXY, "socks5://127.0.0.1:19080")
                putString(PingService.TYPE, "get")
            }
        })
    }

    private fun idleMain() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun pumpUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition()) {
            idleMain()
            check(System.nanoTime() < deadline) { "Ping service did not reach the expected state" }
            Thread.sleep(1)
        }
        idleMain()
    }

    class ControlledPingService : PingService() {
        val prepared = CopyOnWriteArrayList<Long>()
        val cancelled = CopyOnWriteArrayList<Long>()
        val tasks = ConcurrentHashMap<Long, NativeTask>()
        var preparationFailure: Exception? = null

        override fun prepareMeasurement(id: Long) {
            prepared.add(id)
            preparationFailure?.let { throw it }
            tasks[id] = NativeTask()
        }

        override fun measurePrepared(id: Long, data: Bundle): String = tasks.getValue(id).run()

        override fun cancelMeasurement(id: Long) {
            cancelled.add(id)
        }
    }

    class NativeTask {
        private val release = CountDownLatch(1)
        @Volatile var started = false
            private set
        @Volatile var finished = false
            private set
        private var result = ""
        private var failure: Exception? = null

        fun finish(value: String) {
            result = value
            release.countDown()
        }

        fun fail(error: Exception) {
            failure = error
            release.countDown()
        }

        fun run(): String {
            started = true
            try {
                check(release.await(10, TimeUnit.SECONDS)) { "Synthetic native task was not released" }
                failure?.let { throw it }
                return result
            } finally {
                finished = true
            }
        }
    }
}
