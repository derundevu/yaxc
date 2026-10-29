package io.github.derundevu.yaxc.testing

import java.io.Closeable
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Minimal real HTTP endpoint: fixed/chunkless bodies and deliberately incomplete responses. */
internal class LoopbackHttpServer : Closeable {
    private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "regression-loopback").apply { isDaemon = true }
    }
    private val responses = LinkedBlockingQueue<(OutputStream) -> Unit>()
    private val sockets = java.util.concurrent.ConcurrentLinkedQueue<Socket>()
    val requests = LinkedBlockingQueue<List<String>>()
    val url = "http://127.0.0.1:${server.localPort}/resource"

    init {
        executor.execute {
            try {
                while (!server.isClosed) {
                    val socket = server.accept()
                    sockets.add(socket)
                    executor.execute {
                        socket.use {
                            socket.soTimeout = 5000
                            val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                            val headers = buildList {
                                while (true) {
                                    val line = reader.readLine() ?: break
                                    if (line.isEmpty()) break
                                    add(line)
                                }
                            }
                            requests.put(headers)
                            val response = responses.poll(5, TimeUnit.SECONDS)
                                ?: error("No loopback response queued")
                            response(socket.getOutputStream())
                        }
                        sockets.remove(socket)
                    }
                }
            } catch (closed: SocketException) {
                if (!server.isClosed) throw closed
            }
        }
    }

    fun enqueue(body: ByteArray, status: Int = 200, declaredLength: Long? = body.size.toLong()) {
        enqueue { output ->
            writeHeaders(output, status, declaredLength)
            output.write(body)
            output.flush()
        }
    }

    fun enqueue(response: (OutputStream) -> Unit) {
        responses.put(response)
    }

    override fun close() {
        server.close()
        sockets.forEach { it.close() }
        executor.shutdownNow()
        check(executor.awaitTermination(5, TimeUnit.SECONDS)) { "Loopback workers did not stop" }
    }

    companion object {
        fun writeHeaders(output: OutputStream, status: Int = 200, length: Long? = null) {
            output.write(buildString {
                append("HTTP/1.1 $status Result\r\nConnection: close\r\n")
                if (length != null) append("Content-Length: $length\r\n")
                append("\r\n")
            }.toByteArray(Charsets.US_ASCII))
            output.flush()
        }
    }
}
