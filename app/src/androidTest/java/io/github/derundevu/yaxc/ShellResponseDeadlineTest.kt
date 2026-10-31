package io.github.derundevu.yaxc

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.derundevu.yaxc.testing.DeadlineInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.io.DataInputStream
import java.net.SocketTimeoutException

@RunWith(AndroidJUnit4::class)
class ShellResponseDeadlineTest {
    @Test(timeout = 5_000)
    fun partialFrameCannotWaitForeverForAnOpenWriter() {
        val pipes = ParcelFileDescriptor.createPipe()
        DeadlineInputStream(pipes[0]).use { response ->
            val input = DataInputStream(response)
            ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).use { writer ->
                writer.write(byteArrayOf(0x12, 0x34))
                response.beginResponse(200)
                // First-byte readiness is insufficient: the rest of this frame never arrives.
                assertThrows(SocketTimeoutException::class.java) { input.readInt() }
                writer.write(byteArrayOf(0x56, 0x78))
                response.beginResponse(0)
                assertThrows(SocketTimeoutException::class.java) { input.readUnsignedShort() }
            }
            response.beginResponse(1_000)
            assertEquals(0x5678, input.readUnsignedShort())
            assertEquals(-1, input.read())
        }
    }
}
