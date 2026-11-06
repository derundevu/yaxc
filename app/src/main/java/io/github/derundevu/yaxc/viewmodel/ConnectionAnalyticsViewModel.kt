package io.github.derundevu.yaxc.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import io.github.derundevu.yaxc.Yaxc
import io.github.derundevu.yaxc.presentation.connectionanalytics.ConnectionAnalyticsState
import io.github.derundevu.yaxc.presentation.connectionanalytics.ConnectionFilter
import io.github.derundevu.yaxc.presentation.connectionanalytics.ConnectionGrouping
import io.github.derundevu.yaxc.presentation.connectionanalytics.connectionAnalyticsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionAnalyticsViewModel(application: Application, private val savedState: SavedStateHandle) : AndroidViewModel(application) {
    private val repository = getApplication<Yaxc>().connectionRepository
    private val grouping = savedState.getStateFlow("connectionGrouping", ConnectionGrouping.App.name)
    private val filter = savedState.getStateFlow("connectionFilter", ConnectionFilter.All.name)
    private val visible = MutableStateFlow(false)

    // Visibility gates presentation work only; the application repository owns continuous capture.
    val uiState = visible.flatMapLatest { screenVisible ->
        if (!screenVisible) emptyFlow() else {
            combine(repository.state, grouping, filter) { history, group, selectedFilter ->
                Triple(history, ConnectionGrouping.valueOf(group), ConnectionFilter.valueOf(selectedFilter))
            }.mapLatest { (history, group, selectedFilter) ->
                withContext(Dispatchers.Default) {
                    connectionAnalyticsState(history, group, selectedFilter, application.resources.configuration.locales[0])
                }
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConnectionAnalyticsState())

    fun setVisible(value: Boolean) { visible.value = value }
    fun setGrouping(value: ConnectionGrouping) { savedState["connectionGrouping"] = value.name }
    fun setFilter(value: ConnectionFilter) { savedState["connectionFilter"] = value.name }
    fun setPersistence(enabled: Boolean) = repository.setPersistence(enabled)
    fun clearHistory() = repository.clearHistory()
}
