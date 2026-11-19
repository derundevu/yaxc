package io.github.derundevu.yaxc.helper

import android.content.Context
import io.github.derundevu.yaxc.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object InstalledChangelog {
    suspend fun load(context: Context): String = withContext(Dispatchers.IO) {
        val language = if (context.resources.configuration.locales[0]?.language == "ru") "ru" else "en"
        context.assets.open("changelog/${BuildConfig.VERSION_CODE}/$language.md")
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
    }
}
