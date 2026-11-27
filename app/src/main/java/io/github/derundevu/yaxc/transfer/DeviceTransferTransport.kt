package io.github.derundevu.yaxc.transfer

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.RouteInfo
import android.os.Build
import android.os.SystemClock
import android.system.Os
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal sealed interface TransferEndpoint

internal enum class TransferApplyFailure(internal val wireCode: Int) {
    ROUTING_DEPENDENCY(1),
    IMPORT_FAILED(2),
}

internal class TransferApplyException(val reason: TransferApplyFailure) : IOException("Peer could not import transfer")
internal class TransferConfirmationException : IOException("Peer did not confirm transfer")
internal class TransferTimeoutException : IOException("Transfer expired")
internal class TransferNetworkUnavailableException : IOException("No usable Wi-Fi or Ethernet route for transfer")

internal object DeviceTransferTransport {
    const val QR_PREFIX = "yaxc-transfer:"
    const val LEASE_MILLIS = 120_000L
    const val MAX_PAYLOAD_BYTES = 4 * 1024 * 1024

    fun isTransferCode(raw: String): Boolean = raw.trimStart().startsWith(QR_PREFIX, ignoreCase = true)

    fun parse(raw: String): TransferEndpoint {
        require(raw.length <= 128 && isTransferCode(raw)) { "Invalid transfer QR" }
        val parts = raw.trim().substring(QR_PREFIX.length).split(':', limit = 4)
        require(parts.size == 4 && parts[0] == "1") { "Invalid transfer QR" }
        val addressBytes = decodeQrBytes(parts[1], 4, 16)
        val address = InetAddress.getByAddress(addressBytes)
        require(address.address.size == addressBytes.size && address.isLanAddress()) { "Invalid transfer QR" }
        val port = parts[2].toIntOrNull()
        require(port != null && port in 1..65535 && port.toString() == parts[2]) { "Invalid transfer QR" }
        return Endpoint(address, port, decodeQrBytes(parts[3], SECRET_BYTES))
    }

    suspend fun open(context: Context): TransferReceiver = suspendCancellableCoroutine { continuation ->
        Dispatchers.IO.dispatch(continuation.context, Runnable {
            if (!continuation.isActive) return@Runnable
            try {
                val receiver = openReceiver(context)
                continuation.resume(receiver) { _, undelivered, _ -> undelivered.close() }
            } catch (error: Throwable) {
                continuation.resumeWith(Result.failure(error))
            }
        })
    }

    private fun openReceiver(context: Context): TransferReceiver {
        val links = lanLinks(context)
        // Prefer private IPv4, then ULA, then link-local. Avoid advertising IPv6-only when
        // the same device offers an IPv4 address that phones and TVs are likelier to share.
        val addresses = links.flatMap { link ->
            link.properties.linkAddresses.mapNotNull { link.scope(it.address.takeIf(InetAddress::isLanAddress)) }
        }.sortedBy { it.preference() }
        for (address in addresses) {
            val listener = ServerSocket()
            try {
                listener.bind(InetSocketAddress(address, 0), LISTEN_BACKLOG)
                val secret = randomBytes(SECRET_BYTES)
                val code = "${QR_PREFIX}1:${qrEncoder.encodeToString(address.address)}:${listener.localPort}:${qrEncoder.encodeToString(secret)}"
                return TransferReceiver(listener, code, secret, SystemClock.elapsedRealtime() + LEASE_MILLIS)
            } catch (_: IOException) {
                listener.closeQuietly()
            } catch (error: Throwable) {
                listener.closeQuietly()
                throw error
            }
        }
        throw TransferNetworkUnavailableException()
    }

    suspend fun send(context: Context, endpoint: TransferEndpoint, payload: String): String {
        check(endpoint is Endpoint)
        val expiresAt = SystemClock.elapsedRealtime() + LEASE_MILLIS
        return Socket().use { socket ->
            val session = withTimeoutOrNull(HANDSHAKE_MILLIS) {
                socketIo(socket) {
                    require(payload.length in 1..MAX_PAYLOAD_BYTES) { "Invalid transfer payload size" }
                    val (link, source, destination) = routeTo(context, endpoint.address)
                    link.network.bindSocket(socket)
                    socket.bind(InetSocketAddress(source, 0))
                    try {
                        socket.connect(InetSocketAddress(destination, endpoint.port), HANDSHAKE_MILLIS.toInt())
                    } catch (_: SocketTimeoutException) {
                        throw TransferTimeoutException()
                    }
                    Session.connect(socket, endpoint.secret)
                }
            } ?: throw TransferTimeoutException()
            beforeExpiry(expiresAt) {
                socketIo(socket) {
                    val bytes = payload.encodeToByteArray(throwOnInvalidSequence = true)
                    require(bytes.size in 1..MAX_PAYLOAD_BYTES) { "Invalid transfer payload size" }
                    checkNotExpired(expiresAt)
                    session.sendPayload(bytes)
                }
            }
            try {
                beforeExpiry(expiresAt) {
                    socketIo(socket) { session.readResult().also { checkNotExpired(expiresAt) } }
                }
            } catch (error: TransferApplyException) {
                throw error
            } catch (_: IOException) {
                throw TransferConfirmationException()
            }
        }
    }
}

internal class TransferReceiver internal constructor(
    private val listener: ServerSocket,
    val code: String,
    private val secret: ByteArray,
    private val expiresAt: Long,
) : Closeable {
    private val started = AtomicBoolean(false)
    private val active = AtomicReference<Socket?>(null)
    private val expiration = leaseScope.launch {
        delay(remaining(expiresAt))
        closeSockets()
    }

    suspend fun receive(apply: suspend (String) -> String): String = coroutineScope {
        check(started.compareAndSet(false, true)) { "Receiver can only be used once" }
        val cleanup = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                close()
            }
        }
        try {
            val request = try {
                beforeExpiry(expiresAt) { awaitRequest() }
            } catch (error: IOException) {
                checkNotExpired(expiresAt)
                throw error
            }
            val summary = try {
                // The lease bounds pairing and network I/O, not an already accepted database write.
                apply(request.payload)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                val reason = if (error is TransferRoutingDependencyException) {
                    TransferApplyFailure.ROUTING_DEPENDENCY
                } else {
                    TransferApplyFailure.IMPORT_FAILED
                }
                reply(request, byteArrayOf(reason.wireCode.toByte()))
                throw error
            }
            // Once committed, a disappearing peer does not undo the local import.
            successResult(summary)?.let { reply(request, it) }
            summary
        } finally {
            cleanup.cancel()
        }
    }

    private suspend fun awaitRequest(): Request {
        while (true) {
            currentCoroutineContext().ensureActive()
            checkNotExpired(expiresAt)
            val socket = socketIo(listener) { accept() }
            val request = if (socket.inetAddress.isLanAddress()) readRequest(socket) else null
            if (request == null) {
                socket.closeQuietly()
                active.compareAndSet(socket, null)
                continue
            }
            currentCoroutineContext().ensureActive()
            checkNotExpired(expiresAt)
            if (listener.isClosed) throw IOException("Transfer receiver closed")
            listener.close()
            return request
        }
    }

    private suspend fun readRequest(socket: Socket): Request? {
        return try {
            val (session, size) = withTimeoutOrNull(HANDSHAKE_MILLIS) {
                socketIo(socket) {
                    val session = Session.accept(socket, secret)
                    session to session.readPayloadSize()
                }
            } ?: return null
            Request(socket, session, socketIo(socket) { session.readPayload(size) })
        } catch (_: IOException) {
            // An incomplete or unauthenticated request does not claim this code.
            null
        }
    }

    private suspend fun reply(request: Request, result: ByteArray) {
        try {
            withTimeoutOrNull(remaining(expiresAt)) {
                socketIo(request.socket) {
                    checkNotExpired(expiresAt)
                    request.session.sendResult(result)
                }
            }
        } catch (_: IOException) {
            // A result that cannot be authenticated or delivered is not a confirmation.
        }
    }

    private class Request(val socket: Socket, val session: Session, val payload: String)

    private fun accept(): Socket {
        val socket = listener.accept()
        active.set(socket)
        // close() may have run between accept() returning and publishing the active socket.
        if (listener.isClosed) {
            socket.closeQuietly()
            active.compareAndSet(socket, null)
            throw IOException("Transfer receiver closed")
        }
        return socket
    }

    private fun closeSockets() {
        listener.closeQuietly()
        active.getAndSet(null)?.closeQuietly()
    }

    override fun close() {
        closeSockets()
        expiration.cancel()
    }
}

private class Endpoint(val address: InetAddress, val port: Int, val secret: ByteArray) : TransferEndpoint

private data class LanLink(val network: Network, val properties: LinkProperties) {
    fun scope(address: InetAddress?): InetAddress? {
        if (address !is Inet6Address || !address.isLinkLocalAddress) return address
        val index = properties.interfaceName?.let(Os::if_nametoindex) ?: return null
        return if (index > 0) Inet6Address.getByAddress(null, address.address, index) else null
    }
}

private data class LanRoute(val link: LanLink, val source: InetAddress, val destination: InetAddress)

@Suppress("DEPRECATION") // The active network alone may be the app VPN, not the physical LAN.
private fun lanLinks(context: Context): List<LanLink> {
    val manager = context.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
    val active = manager.activeNetwork
    return manager.allNetworks.sortedBy { it != active }.mapNotNull { network ->
        val capabilities = manager.getNetworkCapabilities(network) ?: return@mapNotNull null
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
            !(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        ) return@mapNotNull null
        manager.getLinkProperties(network)?.let { LanLink(network, it) }
    }
}

private fun routeTo(context: Context, address: InetAddress): LanRoute {
    val destinationBytes = address.address
    var bestScore = -1
    var best: LanRoute? = null
    for (link in lanLinks(context)) {
        val destination = link.scope(address) ?: continue
        val route = link.properties.routes.asSequence()
            .filter { it.destination.contains(address) }
            .maxByOrNull { it.destination.prefixLength }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            route != null && route.type != RouteInfo.RTN_UNICAST
        ) continue
        val routedPrefix = route?.destination?.prefixLength ?: -1
        for (local in link.properties.linkAddresses) {
            val source = local.address
            if ((source is Inet4Address) != (address is Inet4Address) || !source.isLanAddress() ||
                source.isLinkLocalAddress != address.isLinkLocalAddress
            ) continue
            val directPrefix = if (source.address.matchesPrefix(destinationBytes, local.prefixLength)) {
                local.prefixLength
            } else -1
            val prefix = when {
                address.isLinkLocalAddress -> directPrefix
                address.isSharedV4Address() && routedPrefix == 0 -> directPrefix
                else -> maxOf(directPrefix, routedPrefix)
            }
            if (prefix < 0) continue
            val score = prefix * 2 + if (directPrefix == prefix) 1 else 0
            if (score <= bestScore) continue
            best = LanRoute(link, link.scope(source) ?: continue, destination)
            bestScore = score
        }
    }
    return best ?: throw TransferNetworkUnavailableException()
}

private fun ByteArray.matchesPrefix(other: ByteArray, prefixLength: Int): Boolean {
    val wholeBytes = prefixLength / Byte.SIZE_BITS
    for (i in 0 until wholeBytes) if (this[i] != other[i]) return false
    val bits = prefixLength % Byte.SIZE_BITS
    return bits == 0 || ((this[wholeBytes].toInt() xor other[wholeBytes].toInt()) shr (Byte.SIZE_BITS - bits)) == 0
}

private fun InetAddress.isLanAddress(): Boolean = when (this) {
    is Inet4Address -> isSiteLocalAddress || isLinkLocalAddress || isSharedV4Address()
    is Inet6Address -> isLinkLocalAddress || (address[0].toInt() and 0xfe) == 0xfc // RFC 4193 ULA
    else -> false
}

private fun InetAddress.isSharedV4Address(): Boolean {
    if (this !is Inet4Address) return false
    val bytes = address
    return bytes[0].toInt() == 100 && (bytes[1].toInt() and 0xc0) == 0x40
}

private fun InetAddress.preference(): Int = when {
    this is Inet4Address && !isLinkLocalAddress -> 0
    this is Inet6Address && !isLinkLocalAddress -> 1
    this is Inet4Address -> 2
    else -> 3
}

private fun decodeQrBytes(value: String, vararg lengths: Int): ByteArray {
    val bytes = try {
        Base64.getUrlDecoder().decode(value)
    } catch (_: IllegalArgumentException) {
        throw IllegalArgumentException("Invalid transfer QR")
    }
    require(bytes.size in lengths && qrEncoder.encodeToString(bytes) == value) { "Invalid transfer QR" }
    return bytes
}

// Both random challenges feed a new per-connection AES key. Each phase uses that key
// exactly once under its own nonce; a request from any previous connection won't verify.
private class Session(
    private val input: DataInputStream,
    private val output: DataOutputStream,
    private val key: SecretKeySpec,
) {
    fun readPayloadSize(): Int {
        val size = ByteBuffer.wrap(read(Phase.PAYLOAD_SIZE, Int.SIZE_BYTES)).int
        if (size !in 1..DeviceTransferTransport.MAX_PAYLOAD_BYTES) throw IOException("Invalid transfer frame")
        return size
    }

    fun readPayload(size: Int): String = read(Phase.PAYLOAD, size).decodeUtf8()

    fun sendPayload(bytes: ByteArray) {
        write(Phase.PAYLOAD_SIZE, ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
        write(Phase.PAYLOAD, bytes)
    }

    fun sendResult(result: ByteArray) {
        output.writeInt(result.size)
        write(Phase.RESULT, result)
    }

    fun readResult(): String {
        val length = input.readInt()
        if (length !in 1..MAX_RESULT_BYTES) throw IOException("Invalid transfer result")
        val result = read(Phase.RESULT, length)
        val status = result[0].toInt()
        if (status == 0) return result.decodeUtf8(start = 1)
        val reason = TransferApplyFailure.entries.firstOrNull { it.wireCode == status }
        if (result.size != 1 || reason == null) throw IOException("Invalid transfer result")
        throw TransferApplyException(reason)
    }

    private fun write(phase: Phase, bytes: ByteArray) {
        output.write(crypt(Cipher.ENCRYPT_MODE, phase, bytes))
    }

    private fun read(phase: Phase, size: Int): ByteArray =
        crypt(Cipher.DECRYPT_MODE, phase, input.readBytesExact(size + TAG_BYTES))

    private fun crypt(mode: Int, phase: Phase, bytes: ByteArray): ByteArray = try {
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(mode, key, GCMParameterSpec(TAG_BYTES * Byte.SIZE_BITS, phase.nonce))
            doFinal(bytes)
        }
    } catch (_: GeneralSecurityException) {
        throw IOException("Transfer authentication failed")
    }

    companion object {
        fun connect(socket: Socket, secret: ByteArray): Session {
            val input = DataInputStream(socket.getInputStream())
            val output = DataOutputStream(socket.getOutputStream())
            val clientNonce = randomBytes(CHALLENGE_BYTES)
            output.write(clientNonce)
            val session = Session(input, output, sessionKey(secret, clientNonce, input.readBytesExact(CHALLENGE_BYTES)))
            session.read(Phase.RECEIVER_PROOF, 0)
            return session
        }

        fun accept(socket: Socket, secret: ByteArray): Session {
            val input = DataInputStream(socket.getInputStream())
            val output = DataOutputStream(socket.getOutputStream())
            val clientNonce = input.readBytesExact(CHALLENGE_BYTES)
            val serverNonce = randomBytes(CHALLENGE_BYTES)
            val session = Session(input, output, sessionKey(secret, clientNonce, serverNonce))
            output.write(serverNonce)
            session.write(Phase.RECEIVER_PROOF, EMPTY)
            return session
        }

        private fun sessionKey(secret: ByteArray, clientNonce: ByteArray, serverNonce: ByteArray): SecretKeySpec =
            Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(secret, "HmacSHA256"))
                update(PROTOCOL_DOMAIN)
                update(clientNonce)
                update(serverNonce)
                SecretKeySpec(doFinal(), "AES")
            }
    }
}

private enum class Phase(id: Byte) {
    RECEIVER_PROOF(1),
    PAYLOAD_SIZE(2),
    PAYLOAD(3),
    RESULT(4);

    val nonce = ByteArray(12).also { it[0] = id }
}

private fun successResult(summary: String): ByteArray? {
    if (summary.length >= MAX_RESULT_BYTES) return null
    val bytes = try {
        summary.encodeToByteArray(throwOnInvalidSequence = true)
    } catch (_: CharacterCodingException) {
        return null
    }
    if (bytes.size >= MAX_RESULT_BYTES) return null
    return ByteArray(1 + bytes.size).also { bytes.copyInto(it, destinationOffset = 1) }
}

private fun ByteArray.decodeUtf8(start: Int = 0): String = try {
    decodeToString(startIndex = start, throwOnInvalidSequence = true)
} catch (_: CharacterCodingException) {
    throw IOException("Invalid transfer text")
}

private fun DataInputStream.readBytesExact(size: Int): ByteArray = ByteArray(size).also { readFully(it) }

private suspend fun <T> socketIo(socket: Closeable, block: () -> T): T =
    suspendCancellableCoroutine { continuation ->
        // Closing the socket, not just interrupting the worker, also breaks a blocked write.
        continuation.invokeOnCancellation { socket.closeQuietly() }
        Dispatchers.IO.dispatch(continuation.context, Runnable {
            if (continuation.isActive) continuation.resumeWith(runCatching(block))
        })
    }

private suspend fun <T : Any> beforeExpiry(expiresAt: Long, block: suspend () -> T): T =
    withTimeoutOrNull(remaining(expiresAt)) { block() } ?: throw TransferTimeoutException()

private fun remaining(expiresAt: Long): Long = expiresAt - SystemClock.elapsedRealtime()

private fun checkNotExpired(expiresAt: Long) {
    if (remaining(expiresAt) <= 0) throw TransferTimeoutException()
}

private fun Closeable.closeQuietly() {
    try {
        close()
    } catch (_: IOException) {
    }
}

private fun randomBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)

private const val SECRET_BYTES = 32
private const val CHALLENGE_BYTES = 32
private const val TAG_BYTES = 16
private const val LISTEN_BACKLOG = 4
private const val HANDSHAKE_MILLIS = 4_000L
private const val MAX_RESULT_BYTES = 64 * 1024
private val PROTOCOL_DOMAIN = "yaxc-transfer-v1".encodeToByteArray()
private val EMPTY = ByteArray(0)
private val qrEncoder = Base64.getUrlEncoder().withoutPadding()
private val random = SecureRandom()
private val leaseScope = CoroutineScope(Dispatchers.Default)
