package io.github.derundevu.yaxc.testing

import android.os.SystemClock
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** One real member endpoint: probe health never closes or changes its established framed TCP. */
internal class LiveAvailabilityPeer(name: String, initialProbeStatus: Int = 204) : Closeable {
    data class Request(val method: String, val path: String, val host: String)
    data class ProbeRequest(val generation: Long, val startedAt: Long, val elapsedAt: Long)
    data class ProbeResponse(val generation: Long, val status: Int, val sentAt: Long, val elapsedAt: Long)
    private data class ProbeControl(val generation: Long, val status: Int, val gate: CountDownLatch?, val delayMillis: Long = 0)

    val payload = byteArrayOf(0, 1, 0x7f, 0xff.toByte()) + " availability:$name 東京\n".toByteArray()
    val requests = LinkedBlockingQueue<Request>()
    val probeRequests = LinkedBlockingQueue<ProbeRequest>()
    val probeResponses = LinkedBlockingQueue<ProbeResponse>()
    val frames = LinkedBlockingQueue<ByteArray>()
    val streamConnections = AtomicInteger()
    val failures = LinkedBlockingQueue<Throwable>()
    private val listener = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = listener.localPort
    private val executor = Executors.newFixedThreadPool(8)
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val closed = AtomicBoolean()
    private var control = ProbeControl(0, initialProbeStatus, null)
    private val acceptor = Thread({
        try {
            while (!closed.get()) {
                val socket = listener.accept()
                sockets += socket
                executor.execute { serve(socket) }
            }
        } catch (error: Exception) {
            if (!closed.get()) failures.offer(error)
        }
    }, "availability-$name-accept").apply { start() }

    /** The returned generation identifies only requests that start after this change. */
    @Synchronized
    fun configureProbes(status: Int, hold: Boolean = false, delayMillis: Long = 0): Long {
        require(status == 204 || status == 503)
        require(delayMillis in 0..4_000)
        control.gate?.countDown()
        control = ProbeControl(control.generation + 1, status, if (hold) CountDownLatch(1) else null, delayMillis)
        return control.generation
    }

    @Synchronized
    private fun probeControl(): ProbeControl = control

    private fun serve(socket: Socket) {
        var probing = false
        var streaming = false
        try {
            socket.use {
                socket.soTimeout = 10_000
                val input = socket.getInputStream()
                val first = input.readHttpLine() ?: return
                val parts = first.split(' ', limit = 3)
                check(parts.size == 3 && parts[0] == "GET") { "Unexpected member request: $first" }
                var host = ""
                var headers = 0
                while (true) {
                    check(headers++ < 100) { "Too many member request headers" }
                    val line = input.readHttpLine() ?: error("EOF in member headers")
                    if (line.isEmpty()) break
                    if (line.startsWith("Host:", ignoreCase = true)) host = line.substringAfter(':').trim()
                }
                when (parts[1]) {
                    "/availability-probe" -> {
                        probing = true
                        val snapshot = probeControl()
                        val elapsedAt = SystemClock.elapsedRealtime()
                        probeRequests.put(ProbeRequest(snapshot.generation, System.currentTimeMillis(), elapsedAt))
                        // Native production timeout is five seconds. A held reply lets the test
                        // observe a replacement's real checking state and one genuine timeout.
                        if (snapshot.gate != null && !snapshot.gate.await(10, TimeUnit.SECONDS)) return
                        if (snapshot.delayMillis > 0) Thread.sleep(snapshot.delayMillis)
                        val sentAt = System.currentTimeMillis()
                        val reason = if (snapshot.status == 204) "No Content" else "Service Unavailable"
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 ${snapshot.status} $reason\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                            flush()
                        }
                        probeResponses.put(ProbeResponse(snapshot.generation, snapshot.status, sentAt, elapsedAt))
                    }
                    "/framed" -> {
                        streaming = true
                        streamConnections.incrementAndGet()
                        socket.soTimeout = 240_000
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: keep-alive\r\n\r\n".toByteArray(Charsets.US_ASCII))
                            flush()
                        }
                        val framedInput = DataInputStream(input)
                        val output = DataOutputStream(socket.getOutputStream())
                        var chain = byteArrayOf()
                        while (true) {
                            val size = try { framedInput.readInt() } catch (_: EOFException) { break }
                            check(size in 1..4096) { "Invalid held frame length: $size" }
                            val bytes = ByteArray(size).also(framedInput::readFully)
                            frames.put(bytes)
                            chain += bytes
                            val response = payload + chain
                            check(response.size <= 16_384)
                            output.writeInt(response.size)
                            output.write(response)
                            output.flush()
                        }
                    }
                    else -> {
                        requests.put(Request(parts[0], parts[1], host))
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                            write(payload)
                            flush()
                        }
                    }
                }
            }
        } catch (error: Exception) {
            // Native cancellation/timeout closes probes, and the shell closes its held stream
            // at fixture teardown. Neither is a synthetic HTTP/application failure.
            if (!closed.get() && !(error is IOException && (probing || streaming))) failures.offer(error)
        } finally {
            sockets -= socket
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(this) { control.gate?.countDown() }
        listener.close()
        sockets.forEach { it.close() }
        acceptor.join(5_000)
        executor.shutdownNow()
        check(executor.awaitTermination(5, TimeUnit.SECONDS)) { "Availability peer workers did not stop" }
        check(!acceptor.isAlive) { "Availability peer acceptor did not stop" }
    }
}

private fun InputStream.readHttpLine(): String? {
    val line = StringBuilder()
    while (true) {
        val next = read()
        if (next < 0) return if (line.isEmpty()) null else error("Truncated member HTTP line")
        if (next == '\n'.code) return line.toString().removeSuffix("\r")
        line.append(next.toChar())
        check(line.length <= 16_384) { "Member HTTP header exceeds its bound" }
    }
}
