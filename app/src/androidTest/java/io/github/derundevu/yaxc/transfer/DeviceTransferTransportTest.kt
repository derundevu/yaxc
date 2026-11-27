package io.github.derundevu.yaxc.transfer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.Base64

/** Run with an active Wi-Fi or Ethernet interface; these use real Android LAN sockets. */
@RunWith(AndroidJUnit4::class)
class DeviceTransferTransportTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun wrongSecretDoesNotClaimCodeAndAcknowledgementWaitsForApply() = runBlocking {
        withTimeout(15_000) {
            DeviceTransferTransport.open(context).use { receiver ->
                val summary = "Imported 1 configuration"
                val payload = "test profile: ☃"
                val applying = CompletableDeferred<String>()
                val commit = CompletableDeferred<Unit>()
                val receive = async {
                    receiver.receive {
                        applying.complete(it)
                        commit.await()
                        summary
                    }
                }

                val secret = Base64.getUrlDecoder().decode(receiver.code.substringAfterLast(':'))
                secret[0] = (secret[0].toInt() xor 1).toByte()
                val badCode = receiver.code.replaceAfterLast(':', Base64.getUrlEncoder().withoutPadding().encodeToString(secret))
                val badEndpoint = DeviceTransferTransport.parse(badCode)
                val wrongSecret = runCatching { DeviceTransferTransport.send(context, badEndpoint, payload) }.exceptionOrNull()
                assertEquals("Transfer authentication failed", wrongSecret?.message)
                assertFalse(applying.isCompleted)

                val endpoint = DeviceTransferTransport.parse(receiver.code)
                val send = async { DeviceTransferTransport.send(context, endpoint, payload) }
                assertEquals(payload, applying.await())
                assertFalse(send.isCompleted)
                commit.complete(Unit)
                assertEquals(summary, send.await())
                assertEquals(summary, receive.await())
                assertTrue(runCatching { DeviceTransferTransport.send(context, endpoint, payload) }.exceptionOrNull() is IOException)
            }
        }
    }

    @Test
    fun authenticatedApplyFailureReturnsOnlyItsCoarseReason() = runBlocking {
        withTimeout(15_000) {
            DeviceTransferTransport.open(context).use { receiver ->
                val localError = TransferRoutingDependencyException("test profile name")
                val receive = async { runCatching { receiver.receive { throw localError } } }
                val endpoint = DeviceTransferTransport.parse(receiver.code)
                val error = runCatching { DeviceTransferTransport.send(context, endpoint, "test") }.exceptionOrNull()
                assertTrue(error is TransferApplyException)
                assertEquals(TransferApplyFailure.ROUTING_DEPENDENCY, (error as TransferApplyException).reason)
                assertFalse(error.message.orEmpty().contains("test profile name"))
                val receiverError = receive.await().exceptionOrNull()
                assertTrue(receiverError is TransferRoutingDependencyException)
                assertEquals(localError.message, receiverError?.message)
            }
        }
    }

    @Test
    fun cancellingReceiverWhileApplyingClosesAuthenticatedSocket() = runBlocking {
        withTimeout(15_000) {
            DeviceTransferTransport.open(context).use { receiver ->
                val applying = CompletableDeferred<Unit>()
                val commit = CompletableDeferred<Unit>()
                val receive = async {
                    receiver.receive {
                        applying.complete(Unit)
                        commit.await()
                        "unexpected result"
                    }
                }
                val endpoint = DeviceTransferTransport.parse(receiver.code)
                val send = async { runCatching { DeviceTransferTransport.send(context, endpoint, "test") } }
                applying.await()
                receive.cancelAndJoin()
                assertTrue(send.await().exceptionOrNull() is TransferConfirmationException)
            }
        }
    }

    @Test
    fun aCommittedImportReportsLocalResultWhenSenderDisconnects() = runBlocking {
        withTimeout(15_000) {
            DeviceTransferTransport.open(context).use { receiver ->
                val applying = CompletableDeferred<Unit>()
                val commit = CompletableDeferred<Unit>()
                val receive = async {
                    receiver.receive {
                        applying.complete(Unit)
                        commit.await()
                        "committed"
                    }
                }
                val endpoint = DeviceTransferTransport.parse(receiver.code)
                val send = async { DeviceTransferTransport.send(context, endpoint, "test") }
                applying.await()
                send.cancelAndJoin()
                commit.complete(Unit)
                assertEquals("committed", receive.await())
            }
        }
    }

    @Test
    fun transferPrefixIsRoutedButPublicAndLoopbackAddressesAreRejected() {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val secret = encoder.encodeToString(ByteArray(32) { it.toByte() })
        for (host in listOf(byteArrayOf(8, 8, 8, 8), byteArrayOf(127, 0, 0, 1))) {
            val code = "yaxc-transfer:1:${encoder.encodeToString(host)}:1234:$secret"
            assertTrue(DeviceTransferTransport.isTransferCode(code))
            assertThrows(IllegalArgumentException::class.java) { DeviceTransferTransport.parse(code) }
        }
        assertTrue(DeviceTransferTransport.isTransferCode("yaxc-transfer:malformed"))
        assertThrows(IllegalArgumentException::class.java) { DeviceTransferTransport.parse("yaxc-transfer:malformed") }
        assertFalse(DeviceTransferTransport.isTransferCode("vless://example"))
    }
}
