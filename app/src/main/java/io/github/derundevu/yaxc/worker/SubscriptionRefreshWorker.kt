package io.github.derundevu.yaxc.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.derundevu.yaxc.Settings
import io.github.derundevu.yaxc.helper.SubscriptionRefreshHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class SubscriptionRefreshWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val settings = Settings(applicationContext)
        if (!settings.refreshLinksOnOpen) return Result.success()

        return try {
            SubscriptionRefreshHelper(applicationContext, settings).refreshDueLinks()
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            Result.retry()
        }
    }
}
