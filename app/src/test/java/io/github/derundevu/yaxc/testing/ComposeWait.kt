package io.github.derundevu.yaxc.testing

import android.os.Looper
import androidx.compose.ui.test.junit4.ComposeTestRule
import org.robolectric.Shadows.shadowOf

/** Room/IO continuations post to Android's paused main looper, not Compose's frame clock. */
fun ComposeTestRule.waitForAsyncState(timeoutMillis: Long = 10_000, condition: () -> Boolean) {
    waitUntil(timeoutMillis) {
        shadowOf(Looper.getMainLooper()).idle()
        condition()
    }
    waitForIdle()
}
