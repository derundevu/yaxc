package io.github.derundevu.yaxc

import android.app.Application
import io.github.derundevu.yaxc.database.YaxcDatabase
import io.github.derundevu.yaxc.repository.ConfigRepository
import io.github.derundevu.yaxc.repository.LinkRepository
import io.github.derundevu.yaxc.repository.ProfileRepository

class Yaxc : Application() {

    private val yaxcDatabase by lazy { YaxcDatabase.ref(this) }
    val configRepository by lazy { ConfigRepository(yaxcDatabase.configDao()) }
    val linkRepository by lazy { LinkRepository(yaxcDatabase.linkDao()) }
    val profileRepository by lazy { ProfileRepository(yaxcDatabase.profileDao()) }
}
