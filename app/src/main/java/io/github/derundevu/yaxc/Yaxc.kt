package io.github.derundevu.yaxc

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import io.github.derundevu.yaxc.database.YaxcDatabase
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
        val settings = Settings(this)
        AppCompatDelegate.setApplicationLocales(
            LocaleListCompat.forLanguageTags(settings.languageTag)
        )
    }
}
