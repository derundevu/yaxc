package io.github.derundevu.yaxc.helper

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import io.github.derundevu.yaxc.Settings
import io.github.derundevu.yaxc.worker.SubscriptionRefreshWorker
import java.util.concurrent.TimeUnit

object SubscriptionRefreshScheduler {
    private const val UNIQUE_WORK_NAME = "subscription_refresh"

    fun sync(context: Context, settings: Settings = Settings(context.applicationContext)) {
        if (!settings.refreshLinksOnOpen) {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(UNIQUE_WORK_NAME)
            return
        }

        val intervalMinutes = Settings.MIN_REFRESH_LINKS_INTERVAL_MINUTES.toLong()
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<SubscriptionRefreshWorker>(
            repeatInterval = intervalMinutes,
            repeatIntervalTimeUnit = TimeUnit.MINUTES,
        )
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }
}
