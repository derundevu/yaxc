package io.github.derundevu.yaxc.testing

import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real loopback peer; held responses finish only when the client closes its connection. */
internal class LoopbackTarget : Closeable {
    data class Request(val method: String, val path: String, val body: ByteArray)

    private val listener = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    private val executor = Executors.newFixedThreadPool(8)
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val held = AtomicInteger()
    val maximumHeld = AtomicInteger()
    val requests = LinkedBlockingQueue<Request>()
    val closedHolds = LinkedBlockingQueue<Unit>()
    val failures = LinkedBlockingQueue<Throwable>()
    val port: Int get() = listener.localPort
    val url: String get() = "http://127.0.0.1:$port"
    val payload = byteArrayOf(0, 1, 2, 0x7f, 0xff.toByte()) + " native 東京\n".toByteArray()

    private val acceptor = Thread({
        try {
            while (!listener.isClosed) {
                val socket = listener.accept()
                sockets += socket
                executor.execute { serve(socket) }
            }
        } catch (error: Exception) {
            if (!listener.isClosed) failures.offer(error)
        }
    }, "regression-loopback-accept").apply { start() }

    private fun serve(socket: Socket) {
        try {
            socket.use {
                socket.soTimeout = 10_000
                val input = socket.getInputStream()
                val firstLine = input.readHttpLine() ?: return
                val parts = firstLine.split(' ', limit = 3)
                check(parts.size == 3) { "Malformed request line" }
                var contentLength = 0
                while (true) {
                    val line = input.readHttpLine() ?: error("EOF in request headers")
                    if (line.isEmpty()) break
                    if (line.startsWith("Content-Length:", ignoreCase = true)) {
                        contentLength = line.substringAfter(':').trim().toInt()
                    }
                }
                val body = ByteArray(contentLength)
                var offset = 0
                while (offset < body.size) {
                    val count = input.read(body, offset, body.size - offset)
                    check(count > 0) { "EOF in request body" }
                    offset += count
                }
                val request = Request(parts[0], parts[1], body)
                if (parts[1].startsWith("/hold")) {
                    val active = held.incrementAndGet()
                    maximumHeld.accumulateAndGet(active, ::maxOf)
                    requests.put(request)
                    try {
                        try {
                            check(input.read() == -1) { "Unexpected payload after held request" }
                        } catch (_: SocketException) {
                            // A reset, like EOF, proves the cancelled client closed its real socket.
                        }
                        closedHolds.put(Unit)
                    } finally {
                        held.decrementAndGet()
                    }
                } else {
                    requests.put(request)
                    val output = socket.getOutputStream()
                    output.write("HTTP/1.1 200 OK\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                    if (parts[0] != "HEAD") output.write(payload)
                    output.flush()
                }
            }
        } catch (error: Exception) {
            if (!listener.isClosed) failures.offer(error)
        } finally {
            sockets -= socket
        }
    }

    override fun close() {
        listener.close()
        sockets.forEach { it.close() }
        acceptor.join(5_000)
        executor.shutdownNow()
        check(executor.awaitTermination(5, TimeUnit.SECONDS)) { "Loopback workers did not stop" }
        check(!acceptor.isAlive) { "Loopback acceptor did not stop" }
    }
}

private fun InputStream.readHttpLine(): String? {
    val result = StringBuilder()
    while (true) {
        val next = read()
        if (next < 0) return if (result.isEmpty()) null else error("Truncated HTTP line")
        if (next == '\n'.code) return result.toString().removeSuffix("\r")
        result.append(next.toChar())
        check(result.length <= 16_384) { "HTTP fixture header is too large" }
    }
}
