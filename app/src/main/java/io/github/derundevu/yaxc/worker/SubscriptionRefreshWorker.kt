package io.github.derundevu.yaxc.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.derundevu.yaxc.Settings
import io.github.derundevu.yaxc.helper.SubscriptionRefreshHelper

class SubscriptionRefreshWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val settings = Settings(applicationContext)
        if (!settings.refreshLinksOnOpen) return Result.success()

        return runCatching {
            SubscriptionRefreshHelper(applicationContext, settings).refreshDueLinks()
        }.fold(
            onSuccess = { Result.success() },
            onFailure = { Result.retry() },
        )
    }
}
