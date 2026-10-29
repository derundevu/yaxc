package io.github.derundevu.yaxc.helper

import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** A real, bounded HTTP peer: held exchanges make request/application races deterministic. */
internal class SubscriptionHttpServer : Closeable {
    data class Request(val path: String, val headers: Map<String, String>)

    class Exchange internal constructor(
        internal val body: String,
        internal val status: Int,
        internal val headers: Map<String, String>,
        held: Boolean,
    ) {
        internal val request = CompletableFuture<Request>()
        internal val gate = CountDownLatch(if (held) 1 else 0)
        fun awaitRequest(): Request = request.get(5, TimeUnit.SECONDS)
        fun release() = gate.countDown()
    }

    private val listener = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    private val executor = Executors.newCachedThreadPool { task ->
        Thread(task, "subscription-loopback").apply { isDaemon = true }
    }
    private val responses = LinkedBlockingQueue<Exchange>()
    private val sockets = Collections.synchronizedSet(mutableSetOf<Socket>())
    val requestCount = AtomicInteger()
    val url: String get() = "http://127.0.0.1:${listener.localPort}/subscription"

    init {
        executor.execute {
            while (!listener.isClosed) {
                val socket = try { listener.accept() } catch (_: Exception) { break }
                sockets.add(socket)
                executor.execute { serve(socket) }
            }
        }
    }

    fun enqueue(
        body: String,
        status: Int = 200,
        headers: Map<String, String> = emptyMap(),
        held: Boolean = false,
    ): Exchange = Exchange(body, status, headers, held).also(responses::add)

    private fun serve(socket: Socket) {
        var exchange: Exchange? = null
        try {
            socket.soTimeout = 5_000
            val reader = socket.getInputStream().bufferedReader(StandardCharsets.ISO_8859_1)
            val start = checkNotNull(reader.readLine())
            val headers = mutableMapOf<String, String>()
            repeat(100) {
                val line = reader.readLine() ?: error("Truncated request")
                if (line.isEmpty()) {
                    requestCount.incrementAndGet()
                    exchange = checkNotNull(responses.poll(5, TimeUnit.SECONDS)) { "No response queued" }
                    exchange!!.request.complete(Request(start.split(' ')[1], headers))
                    check(exchange!!.gate.await(5, TimeUnit.SECONDS)) { "Held response was not released" }
                    val bytes = exchange!!.body.toByteArray(StandardCharsets.UTF_8)
                    val responseHeaders = buildString {
                        append("HTTP/1.1 ${exchange!!.status} Response\r\n")
                        append("Content-Length: ${bytes.size}\r\n")
                        append("Content-Type: application/json; charset=utf-8\r\n")
                        append("Connection: close\r\n")
                        exchange!!.headers.forEach { (key, value) -> append("$key: $value\r\n") }
                        append("\r\n")
                    }
                    socket.getOutputStream().apply {
                        write(responseHeaders.toByteArray(StandardCharsets.ISO_8859_1))
                        write(bytes)
                        flush()
                    }
                    return
                }
                val separator = line.indexOf(':')
                check(separator > 0)
                headers[line.substring(0, separator).lowercase()] = line.substring(separator + 1).trim()
            }
            error("Too many request headers")
        } catch (error: Exception) {
            exchange?.request?.completeExceptionally(error)
        } finally {
            sockets.remove(socket)
            socket.close()
        }
    }

    override fun close() {
        listener.close()
        synchronized(sockets) { sockets.forEach(Socket::close) }
        executor.shutdownNow()
        check(executor.awaitTermination(5, TimeUnit.SECONDS)) { "Loopback server did not stop" }
    }
}
