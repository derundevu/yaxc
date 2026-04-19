package io.github.derundevu.yaxc.activity

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import io.github.derundevu.yaxc.BuildConfig
import io.github.derundevu.yaxc.R
import io.github.derundevu.yaxc.Settings
import io.github.derundevu.yaxc.presentation.designsystem.YaxcAppTheme
import io.github.derundevu.yaxc.presentation.logs.LogsScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

class LogsActivity : AppCompatActivity() {

    companion object {
        private const val MAX_BUFFERED_LINES = (1 shl 14) - 1
    }

    private val settings by lazy { Settings(applicationContext) }
    private var logsText by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            YaxcAppTheme {
                LogsScreen(
                    logsText = logsText,
                    onBack = ::finish,
                    onDeleteLogs = ::flush,
                    onCopyLogs = { copyToClipboard(logsText) },
                )
            }
        }

        lifecycleScope.launch(Dispatchers.IO) { streamingLog() }
    }

    private fun flush() {
        lifecycleScope.launch(Dispatchers.IO) {
            val command = if (settings.transparentProxy) {
                listOf("sh", "-c", ": > ${settings.xrayCoreLogs().absolutePath}")
            } else {
                listOf("logcat", "-c")
            }
            val process = ProcessBuilder(command).start()
            process.waitFor()
            withContext(Dispatchers.Main) {
                logsText = ""
            }
        }
    }

    private fun copyToClipboard(text: String) {
        if (text.isBlank()) return
        try {
            val clipData = ClipData.newPlainText(null, text)
            val clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboardManager.setPrimaryClip(clipData)
            Toast.makeText(applicationContext, getString(R.string.logsCopied), Toast.LENGTH_SHORT).show()
        } catch (error: Exception) {
            error.printStackTrace()
        }
    }

    private suspend fun streamingLog() = withContext(Dispatchers.IO) {
        val cmd = if (settings.transparentProxy) {
            listOf("tail", "-f", settings.xrayCoreLogs().absolutePath)
        } else {
            listOf("logcat", "-v", "time", "-s", "GoLog,${BuildConfig.APPLICATION_ID}")
        }
        val builder = ProcessBuilder(cmd)
        builder.environment()["LC_ALL"] = "C"
        var process: Process? = null
        try {
            process = try {
                builder.start()
            } catch (e: IOException) {
                Log.e(packageName, Log.getStackTraceString(e))
                return@withContext
            }

            val stdout = BufferedReader(
                InputStreamReader(process.inputStream, StandardCharsets.UTF_8)
            )
            val bufferedLogLines = arrayListOf<String>()

            var timeLastNotify = System.nanoTime()
            var timeout = 1000000000L / 2

            while (true) {
                val line = stdout.readLine() ?: break
                bufferedLogLines.add(line)
                val timeNow = System.nanoTime()

                if (
                    bufferedLogLines.size < MAX_BUFFERED_LINES &&
                    (timeNow - timeLastNotify) < timeout &&
                    stdout.ready()
                ) continue

                timeout = 1000000000L * 5 / 2
                timeLastNotify = timeNow
                val chunk = bufferedLogLines.joinToString(separator = "\n", postfix = "\n")
                bufferedLogLines.clear()

                withContext(Dispatchers.Main) {
                    logsText += chunk
                }
            }
        } finally {
            process?.destroy()
        }
    }
}
