package io.github.derundevu.yaxc.helper

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class DownloadHelper(
    private val scope: CoroutineScope,
    private val url: String,
    private val file: File,
    private val callback: DownloadListener,
) {

    fun start(): Job = scope.launch(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.connect()
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("Expected HTTP ${HttpURLConnection.HTTP_OK} but received HTTP ${connection.responseCode}")
            }

            val fileLength = connection.contentLengthLong
            replaceFileAtomically(file) { staged ->
                connection.inputStream.use { input ->
                    staged.outputStream().use { output ->
                        val data = ByteArray(4096)
                        var total = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(data)
                            if (count == -1) break
                            output.write(data, 0, count)
                            total += count
                            if (fileLength > 0) {
                                val progress = (total * 100 / fileLength).coerceIn(0, 100).toInt()
                                withContext(Dispatchers.Main) {
                                    callback.onProgress(progress)
                                }
                            }
                        }
                        if (fileLength >= 0 && total != fileLength) {
                            throw IOException("Incomplete download: expected $fileLength bytes, received $total")
                        }
                    }
                }
                currentCoroutineContext().ensureActive()
            }
            withContext(Dispatchers.Main) {
                callback.onComplete()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (exception: Exception) {
            withContext(Dispatchers.Main) {
                callback.onError(exception)
            }
        } finally {
            connection?.disconnect()
        }
    }

    interface DownloadListener {
        fun onProgress(progress: Int)
        fun onError(exception: Exception)
        fun onComplete()
    }
}
