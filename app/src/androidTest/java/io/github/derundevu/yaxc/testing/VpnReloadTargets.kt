package io.github.derundevu.yaxc.testing

import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Exactly one accepted TCP connection, with a bounded, cumulative binary response per frame. */
internal class ChainedStreamTarget(private val frames: Int) : Closeable {
    private val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 15_000 }
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var client: Socket? = null
    val port: Int get() = listener.localPort
    val received = LinkedBlockingQueue<ByteArray>()
    private val served = executor.submit {
        listener.accept().use { socket ->
            client = socket
            check(!listener.isClosed)
            socket.soTimeout = 15_000
            val input = DataInputStream(socket.getInputStream())
            val output = DataOutputStream(socket.getOutputStream())
            var chain = byteArrayOf()
            repeat(frames) {
                val size = input.readInt()
                check(size in 1..4096) { "Stream fixture frame exceeds its bound: $size" }
                val bytes = ByteArray(size).also(input::readFully)
                received.put(bytes)
                chain += bytes
                output.writeInt(chain.size)
                output.write(chain)
                output.flush()
            }
        }
    }

    fun awaitFinished() { served.get(15, TimeUnit.SECONDS) }

    override fun close() {
        listener.close()
        client?.close()
        executor.shutdownNow()
        check(executor.awaitTermination(5, TimeUnit.SECONDS)) { "Chained stream peer did not stop" }
    }
}

/** One real HTTP subscription response, including refreshed source metadata. */
internal class ReloadSubscriptionTarget(body: String) : Closeable {
    private val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 15_000 }
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var client: Socket? = null
    val url = "http://127.0.0.1:${listener.localPort}/reload-subscription"
    private val served = executor.submit {
        listener.accept().use { socket ->
            client = socket
            check(!listener.isClosed)
            socket.soTimeout = 15_000
            val input = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            check(input.readLine().startsWith("GET /reload-subscription "))
            var headers = 0
            while (true) {
                check(headers++ < 100) { "Subscription request has too many headers" }
                if (input.readLine().isEmpty()) break
            }
            val bytes = body.toByteArray(Charsets.UTF_8)
            socket.getOutputStream().apply {
                write(("HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\n" +
                    "profile-title: Refreshed source metadata\r\n" +
                    "subscription-userinfo: upload=7; download=11; total=1024\r\n" +
                    "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.ISO_8859_1))
                write(bytes)
                flush()
            }
        }
    }

    fun awaitServed() { served.get(15, TimeUnit.SECONDS) }

    override fun close() {
        listener.close()
        client?.close()
        executor.shutdownNow()
        check(executor.awaitTermination(5, TimeUnit.SECONDS)) { "Subscription peer did not stop" }
    }
}
