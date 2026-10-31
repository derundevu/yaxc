package io.github.derundevu.yaxc.testing

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.io.InputStream
import java.net.SocketTimeoutException

/** One absolute deadline per response, including partial headers and bodies. */
internal class DeadlineInputStream(pipe: ParcelFileDescriptor) : InputStream() {
    private val input = ParcelFileDescriptor.AutoCloseInputStream(pipe)
    private val poll = arrayOf(StructPollfd().apply {
        fd = pipe.fileDescriptor
        events = OsConstants.POLLIN.toShort()
    })
    private var deadline = 0L

    fun beginResponse(timeoutMillis: Int) {
        deadline = SystemClock.elapsedRealtime() + timeoutMillis
    }

    override fun read(): Int {
        awaitReadable()
        return input.read()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        awaitReadable()
        return input.read(buffer, offset, length)
    }

    private fun awaitReadable() {
        val remaining = deadline - SystemClock.elapsedRealtime()
        if (remaining <= 0 || Os.poll(poll, remaining.toInt()) == 0) {
            throw SocketTimeoutException("Shell response deadline expired")
        }
    }

    override fun close() = input.close()
}
