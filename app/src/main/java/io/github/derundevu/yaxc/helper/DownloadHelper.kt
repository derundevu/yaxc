package io.github.derundevu.yaxc.helper

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class DownloadHelper(
    private val scope: CoroutineScope,
    private val url: String,
    private val file: File,
    private val callback: DownloadListener,
) {

    fun start() {
        scope.launch(Dispatchers.IO) {
            var input: InputStream? = null
            var output: OutputStream? = null
            var connection: HttpURLConnection? = null
            val tempFile = File(file.parentFile, "${file.name}.download")

            try {
                connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("User-Agent", USER_AGENT)
                connection.connect()

                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    throw Exception("Expected HTTP ${HttpURLConnection.HTTP_OK} but received HTTP ${connection.responseCode}")
                }

                input = connection.inputStream
                file.parentFile?.mkdirs()
                tempFile.delete()
                output = FileOutputStream(tempFile)

                val fileLength = connection.contentLengthLong
                val data = ByteArray(4096)
                var total: Long = 0
                var count: Int
                var lastProgress = -1
                while (input.read(data).also { count = it } != -1) {
                    total += count.toLong()
                    if (fileLength > 0) {
                        val progress = (total * 100 / fileLength).toInt().coerceIn(0, 100)
                        if (progress != lastProgress) {
                            lastProgress = progress
                            withContext(Dispatchers.Main) {
                                callback.onProgress(progress)
                            }
                        }
                    }
                    output.write(data, 0, count)
                }
                output.close()
                output = null
                Files.move(tempFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                withContext(Dispatchers.Main) {
                    callback.onComplete()
                }
            } catch (exception: Exception) {
                tempFile.delete()
                Log.w("DownloadHelper", "Download failed: $url", exception)
                withContext(Dispatchers.Main) {
                    callback.onError(exception)
                }
            } finally {
                try {
                    output?.close()
                    input?.close()
                } catch (_: IOException) {
                }

                connection?.disconnect()
            }
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val USER_AGENT = "yaxc"
    }

    interface DownloadListener {
        fun onProgress(progress: Int)
        fun onError(exception: Exception)
        fun onComplete()
    }
}
