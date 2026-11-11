package io.github.derundevu.yaxc.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import io.github.derundevu.yaxc.Yaxc
import io.github.derundevu.yaxc.presentation.connectionanalytics.ConnectionAnalyticsPresenter
import io.github.derundevu.yaxc.presentation.connectionanalytics.ConnectionAnalyticsQuery
import io.github.derundevu.yaxc.presentation.connectionanalytics.ConnectionAnalyticsState
import io.github.derundevu.yaxc.presentation.connectionanalytics.ConnectionFilter
import io.github.derundevu.yaxc.presentation.connectionanalytics.ConnectionGrouping
import io.github.derundevu.yaxc.presentation.connectionanalytics.ConnectionIdentity
import io.github.derundevu.yaxc.presentation.connectionanalytics.ConnectionIdentityKind
import io.github.derundevu.yaxc.presentation.connectionanalytics.ConnectionPeriod
import io.github.derundevu.yaxc.presentation.connectionanalytics.ConnectionProtocol
import io.github.derundevu.yaxc.presentation.connectionanalytics.ConnectionRoute
import io.github.derundevu.yaxc.presentation.connectionanalytics.ConnectionSort
import io.github.derundevu.yaxc.presentation.connectionanalytics.ConnectionTerminal
import io.github.derundevu.yaxc.presentation.connectionanalytics.nextConnectionRefresh
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionAnalyticsViewModel(application: Application, private val savedState: SavedStateHandle) : AndroidViewModel(application) {
    private val repository = getApplication<Yaxc>().connectionRepository
    private inline fun <reified T : Enum<T>> restored(key: String, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == savedState.get<String>(key) } ?: fallback

    private val query = MutableStateFlow(ConnectionAnalyticsQuery(
        grouping = restored("connectionGrouping", ConnectionGrouping.App),
        rootFilter = restored("connectionRootFilter", ConnectionFilter.All),
        selection = savedState.get<String>("connectionSelection")?.let { key ->
            ConnectionIdentity(key, restored("connectionSelectionKind", ConnectionIdentityKind.App),
                savedState["connectionSelectionTitle"], savedState["connectionSelectionDetail"] ?: "",
                savedState["connectionSelectionShared"] ?: false, savedState.get<ArrayList<String>>("connectionSelectionPackages").orEmpty())
        },
        filter = restored("connectionFilter", ConnectionFilter.All),
        search = savedState["connectionSearch"] ?: "",
        oppositeKey = savedState["connectionOpposite"],
        protocol = restored("connectionProtocol", ConnectionProtocol.All),
        route = restored("connectionRoute", ConnectionRoute.All),
        terminal = restored("connectionTerminal", ConnectionTerminal.All),
        period = restored("connectionPeriod", ConnectionPeriod.All),
        sort = restored("connectionSort", ConnectionSort.Newest),
    ))
    private val visible = MutableStateFlow(false)

    // The repository owns capture; the entire presenter and clock cease when the screen is hidden.
    val uiState = visible.flatMapLatest { screenVisible ->
        if (!screenVisible) emptyFlow() else {
            val presenter = ConnectionAnalyticsPresenter()
            combine(repository.state, query) { history, options -> history to options }
                .distinctUntilChanged()
                .flatMapLatest { (history, options) ->
                    flow {
                        while (currentCoroutineContext().isActive) {
                            val now = System.currentTimeMillis()
                            val state = presenter.present(history, options, application.resources.configuration.locales[0], now)
                            emit(state)
                            val next = nextConnectionRefresh(state, now) ?: break
                            // Coalesce dense expiry boundaries; no full-rate clock or work when hidden.
                            delay(next.coerceAtLeast(1_000))
                        }
                    }
                }.flowOn(Dispatchers.Default)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 0), ConnectionAnalyticsState(query = query.value))

    fun setVisible(value: Boolean) { visible.value = value }

    fun setQuery(value: ConnectionAnalyticsQuery) {
        savedState["connectionGrouping"] = value.grouping.name
        savedState["connectionRootFilter"] = value.rootFilter.name
        savedState["connectionSelection"] = value.selection?.key
        savedState["connectionSelectionKind"] = value.selection?.kind?.name
        savedState["connectionSelectionTitle"] = value.selection?.title
        savedState["connectionSelectionDetail"] = value.selection?.detail
        savedState["connectionSelectionShared"] = value.selection?.sharedUid
        savedState["connectionSelectionPackages"] = value.selection?.packages?.let { ArrayList(it) }
        savedState["connectionFilter"] = value.filter.name
        savedState["connectionSearch"] = value.search
        savedState["connectionOpposite"] = value.oppositeKey
        savedState["connectionProtocol"] = value.protocol.name
        savedState["connectionRoute"] = value.route.name
        savedState["connectionTerminal"] = value.terminal.name
        savedState["connectionPeriod"] = value.period.name
        savedState["connectionSort"] = value.sort.name
        query.value = value
    }

    fun setCaptureEnabled(enabled: Boolean) = repository.setCaptureEnabled(enabled)
    fun setPersistence(enabled: Boolean) = repository.setPersistence(enabled)
    fun clearHistory() = repository.clearHistory()
}
