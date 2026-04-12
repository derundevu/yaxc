package io.github.derundevu.yaxc.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.derundevu.yaxc.Yaxc
import io.github.derundevu.yaxc.database.Config
import kotlinx.coroutines.launch

class ConfigViewModel(application: Application) : AndroidViewModel(application) {

    private val configRepository by lazy { getApplication<Yaxc>().configRepository }

    suspend fun get() = configRepository.get()

    fun update(config: Config) = viewModelScope.launch {
        configRepository.update(config)
    }
}
