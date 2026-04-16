package io.github.derundevu.yaxc.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.derundevu.yaxc.Yaxc
import io.github.derundevu.yaxc.database.Link
import kotlinx.coroutines.launch

class LinkViewModel(application: Application) : AndroidViewModel(application) {

    private val linkRepository by lazy { getApplication<Yaxc>().linkRepository }

    val tabs = linkRepository.tabs
    val links = linkRepository.all

    suspend fun activeLinks(): List<Link> {
        return linkRepository.activeLinks()
    }

    suspend fun insertAndGetId(link: Link): Long {
        return linkRepository.insertAndGetId(link)
    }

    suspend fun updateNow(link: Link) {
        linkRepository.update(link)
    }

    fun insert(link: Link) = viewModelScope.launch {
        linkRepository.insert(link)
    }

    fun update(link: Link) = viewModelScope.launch {
        linkRepository.update(link)
    }

    fun delete(link: Link) = viewModelScope.launch {
        linkRepository.delete(link)
    }
}
