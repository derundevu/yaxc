package io.github.derundevu.yaxc

import android.app.ActivityManager
import android.app.Application
import android.os.Build
import android.os.Process
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import io.github.derundevu.yaxc.database.YaxcDatabase
import io.github.derundevu.yaxc.helper.SubscriptionRefreshScheduler
import io.github.derundevu.yaxc.repository.ConfigRepository
import io.github.derundevu.yaxc.repository.LinkRepository
import io.github.derundevu.yaxc.repository.ProfileRepository

class Yaxc : Application() {

    private val yaxcDatabase by lazy { YaxcDatabase.ref(this) }
    val configRepository by lazy { ConfigRepository(yaxcDatabase.configDao(), Settings(this)) }
    val linkRepository by lazy { LinkRepository(yaxcDatabase.linkDao()) }
    val profileRepository by lazy { ProfileRepository(yaxcDatabase.profileDao()) }

    override fun onCreate() {
        super.onCreate()
        val processName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            getSystemService(ActivityManager::class.java).runningAppProcesses
                ?.firstOrNull { it.pid == Process.myPid() }?.processName
        }
        if (processName == "$packageName:validation" ||
            processName?.startsWith("$packageName:ping_") == true
        ) return
        val settings = Settings(this)
        AppCompatDelegate.setApplicationLocales(settings.appLocales())
        SubscriptionRefreshScheduler.sync(this, settings)
    }
}
