package io.github.derundevu.yaxc.viewmodel

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import io.github.derundevu.yaxc.R
import io.github.derundevu.yaxc.Settings
import io.github.derundevu.yaxc.Yaxc
import io.github.derundevu.yaxc.database.Link
import io.github.derundevu.yaxc.database.YaxcDatabase
import io.github.derundevu.yaxc.helper.SubscriptionRefreshHelper
import io.github.derundevu.yaxc.presentation.designsystem.components.FeedbackKind
import io.github.derundevu.yaxc.presentation.designsystem.components.FeedbackMessage
import io.github.derundevu.yaxc.service.TProxyService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LinksManagerViewModel(application: Application) : AndroidViewModel(application) {
    class Outcome(val feedback: FeedbackMessage? = null)

    private val app = getApplication<Yaxc>()
    private val settings = Settings(app)
    private val helper = SubscriptionRefreshHelper(app, settings)
    private var operation: Job? = null

    var initialized = false
        private set
    var busy by mutableStateOf(false)
        private set
    var canCancel by mutableStateOf(false)
        private set
    var formError by mutableStateOf<Int?>(null)
        private set
    private val _outcome = MutableStateFlow<Outcome?>(null)
    val outcome: StateFlow<Outcome?> = _outcome.asStateFlow()

    fun initialize() {
        initialized = true
    }

    fun cancel() {
        if (busy && !canCancel) return
        operation?.cancel()
        busy = false
        canCancel = false
        _outcome.value = Outcome()
    }

    fun save(link: Link) {
        runOperation(cancelable = link.address.isNotBlank(), form = true) {
            if (link.address.isBlank()) {
                canCancel = false
                withContext(NonCancellable + Dispatchers.IO) {
                    link.subscriptionMetadata = null
                    if (link.id == 0L) {
                        link.id = app.linkRepository.insertAndGetId(link)
                    } else {
                        app.linkRepository.update(link)
                    }
                }
                return@runOperation feedback(R.string.feedbackSourceSaved, FeedbackKind.Success)
            }

            val parsed = try {
                helper.resolveProfiles(link)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (parsed == null && link.id == 0L) {
                formError = R.string.subscriptionSourceLoadFailed
                return@runOperation null
            }

            // Resolving can be canceled; once persistence starts, finish it and report its result.
            canCancel = false
            withContext(NonCancellable) {
                if (link.id != 0L) {
                    withContext(Dispatchers.IO) { app.linkRepository.update(link) }
                    if (parsed == null) {
                        return@withContext feedback(R.string.linkSavedWithoutRefresh, FeedbackKind.Warning)
                    }
                    try {
                        helper.saveResolvedLink(link, parsed)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        return@withContext feedback(R.string.linkSavedWithoutRefresh, FeedbackKind.Warning)
                    }
                } else {
                    helper.saveResolvedLink(link, checkNotNull(parsed))
                }
                feedback(R.string.feedbackSourceSaved, FeedbackKind.Success)
            }
        }
    }

    fun refresh(linkId: Long?) {
        runOperation(cancelable = true) {
            val result = helper.refreshLinks(linkId)
            when {
                result.attempted == 0 -> feedback(R.string.feedbackRefreshEmpty, FeedbackKind.Info)
                result.refreshed == 0 -> FeedbackMessage(
                    app.resources.getQuantityString(R.plurals.subscriptionRefreshAllFailed, result.failed, result.failed),
                    FeedbackKind.Error,
                )
                result.failed > 0 -> FeedbackMessage(
                    app.resources.getQuantityString(
                        R.plurals.feedbackRefreshPartial, result.attempted,
                        result.refreshed, result.attempted, result.failed,
                    ),
                    FeedbackKind.Warning,
                )
                else -> FeedbackMessage(
                    app.resources.getQuantityString(
                        R.plurals.feedbackRefreshSummary, result.attempted, result.refreshed, result.attempted,
                    ),
                    FeedbackKind.Success,
                )
            }
        }
    }

    fun delete(link: Link) {
        runOperation(cancelable = false) {
            withContext(NonCancellable + Dispatchers.IO) {
                val database = YaxcDatabase.ref(app)
                val removedIds = database.withTransaction {
                    val profiles = database.profileDao().linkProfiles(link.id)
                    profiles.forEach { database.profileDao().remove(it) }
                    database.linkDao().delete(link)
                    profiles.map { it.id }
                }
                withContext(Dispatchers.Main) {
                    if (settings.selectedProfile in removedIds) settings.selectedProfile = 0L
                    if (TProxyService.isActive()) TProxyService.newConfig(app)
                }
            }
            feedback(R.string.feedbackSourceDeleted, FeedbackKind.Success)
        }
    }

    private fun runOperation(
        cancelable: Boolean,
        form: Boolean = false,
        block: suspend () -> FeedbackMessage?,
    ) {
        if (busy || outcome.value != null) return
        busy = true
        canCancel = cancelable
        formError = null
        operation = viewModelScope.launch {
            try {
                block()?.let { _outcome.value = Outcome(it) }
            } catch (cancelled: CancellationException) {
                _outcome.value = Outcome()
                throw cancelled
            } catch (_: Exception) {
                if (form) {
                    formError = R.string.subscriptionSourceSaveFailed
                } else {
                    _outcome.value = Outcome(feedback(R.string.feedbackOperationFailed, FeedbackKind.Error))
                }
            } finally {
                busy = false
                canCancel = false
                operation = null
            }
        }
    }

    private fun feedback(message: Int, kind: FeedbackKind) = FeedbackMessage(app.getString(message), kind)
}
