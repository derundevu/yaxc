package io.github.derundevu.yaxc.helper

import android.content.Context
import android.widget.Toast
import androidx.annotation.MainThread

/** Plain text keeps rendering in SystemUI, including when no Activity is visible. */
object ToastHelper {
    private var currentToast: Toast? = null

    @MainThread
    fun show(context: Context, message: String, duration: Int = Toast.LENGTH_SHORT) {
        currentToast?.cancel()
        currentToast = Toast.makeText(context.applicationContext, message, duration).also { it.show() }
    }
}
