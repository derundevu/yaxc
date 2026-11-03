package io.github.derundevu.yaxc.viewmodel

import XrayCore.XrayCore
import android.app.Application
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.derundevu.yaxc.R
import io.github.derundevu.yaxc.Settings
import io.github.derundevu.yaxc.Yaxc
import io.github.derundevu.yaxc.database.YaxcDatabase
import io.github.derundevu.yaxc.helper.AppDomainRule
import io.github.derundevu.yaxc.helper.LiveRoutingHelper
import io.github.derundevu.yaxc.helper.LiveRoutingPlan
import io.github.derundevu.yaxc.helper.LiveRoutingRuntimeStatus
import io.github.derundevu.yaxc.helper.ProxyGroup
import io.github.derundevu.yaxc.presentation.designsystem.components.FeedbackKind
import io.github.derundevu.yaxc.service.TProxyService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Deliberately contain display metadata only, never profile JSON or subscription addresses.
data class LiveRoutingProfileOption(val id: Long, val name: String, val source: String, val fingerprint: String = "")
data class LiveRoutingAppOption(val packageName: String, val name: String, val excluded: Boolean)
class LiveRoutingNotice(val message: Int, val kind: FeedbackKind)

class LiveRoutingViewModel internal constructor(
    application: Application,
    private val ioDispatcher: CoroutineDispatcher,
    private val computationDispatcher: CoroutineDispatcher,
    private val runtimeStatusReader: () -> String = { XrayCore.liveRoutingStatus() },
    private val runtimeActive: () -> Boolean = TProxyService::isActive,
) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, Dispatchers.IO, Dispatchers.Default)

    private val app = getApplication<Yaxc>()
    private val settings = Settings(application)
    var plan by mutableStateOf(LiveRoutingPlan())
        private set
    var savedPlan by mutableStateOf<LiveRoutingPlan?>(null)
        private set
    var runtimeStatus by mutableStateOf(LiveRoutingRuntimeStatus())
        private set
    var profiles by mutableStateOf<List<LiveRoutingProfileOption>>(emptyList())
        private set
    var apps by mutableStateOf<List<LiveRoutingAppOption>>(emptyList())
        private set
    var isLoading by mutableStateOf(true)
        private set
    var appsLoading by mutableStateOf(true)
        private set
    var isBusy by mutableStateOf(false)
        private set
    var rootMode by mutableStateOf(false)
        private set
    var appMatchingAvailable by mutableStateOf(false)
        private set
    var error by mutableStateOf<Int?>(null)
        private set
    var canReload by mutableStateOf(false)
        private set
    var notice by mutableStateOf<LiveRoutingNotice?>(null)
        private set
    var hasChanges by mutableStateOf(false)
        private set
    private var baseline: String? = null
    private var domainDrafts by mutableStateOf<Map<String, String>>(emptyMap())
    private var loadJob: Job? = null
    private var environmentJob: Job? = null
    private var environmentRevision = 0

    init {
        reload()
        viewModelScope.launch {
            combine(app.profileRepository.all, app.linkRepository.all) { profiles, links ->
                val sources = links.associateBy { it.id }
                val visible = profiles.map { profile ->
                    LiveRoutingProfileOption(
                        profile.id,
                        profile.name.ifBlank { app.getString(R.string.liveRoutingUnnamedProfile, profile.id) },
                        sources[profile.link]?.name?.ifBlank { app.getString(R.string.liveRoutingUnnamedSource) }
                            ?: app.getString(R.string.liveRoutingLocalProfiles),
                        LiveRoutingHelper.profileFingerprint(profile.config),
                    )
                }
                val inactive = links.filterNot { it.isActive }.flatMap { source ->
                    app.profileRepository.linkProfiles(source.id).map { profile ->
                        LiveRoutingProfileOption(
                            profile.id,
                            profile.name.ifBlank { app.getString(R.string.liveRoutingUnnamedProfile, profile.id) },
                            app.getString(
                                R.string.liveRoutingInactiveSource,
                                source.name.ifBlank { app.getString(R.string.liveRoutingUnnamedSource) },
                            ),
                            LiveRoutingHelper.profileFingerprint(profile.config),
                        )
                    }
                }
                (inactive + visible).distinctBy { it.id }
            }.flowOn(ioDispatcher).collect { profiles = it }
        }
        viewModelScope.launch {
            while (currentCoroutineContext().isActive) {
                try {
                    runtimeStatus = withContext(ioDispatcher) {
                        if (runtimeActive()) LiveRoutingHelper.decodeRuntimeStatus(runtimeStatusReader())
                        else LiveRoutingRuntimeStatus()
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    markRuntimeStatusUnknown()
                } catch (_: LinkageError) {
                    markRuntimeStatusUnknown()
                }
                delay(1_000)
            }
        }
    }

    fun reload() {
        if (isBusy || loadJob?.isActive == true) return
        isLoading = true
        error = null
        canReload = false
        loadJob = viewModelScope.launch {
            try {
                val loaded = withContext(ioDispatcher) {
                    val raw = settings.liveRoutingPlan
                    raw to LiveRoutingHelper.decode(raw)
                }
                baseline = loaded.first
                plan = loaded.second
                savedPlan = loaded.second
                domainDrafts = emptyMap()
                hasChanges = false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                baseline = null
                savedPlan = null
                error = R.string.liveRoutingLoadFailed
                canReload = true
            } finally {
                isLoading = false
            }
        }
    }

    fun refreshEnvironment() {
        val revision = ++environmentRevision
        environmentJob?.cancel()
        appsLoading = true
        environmentJob = viewModelScope.launch {
            try {
                val environment = withContext(ioDispatcher) {
                    val root = settings.transparentProxy
                    val canMatch = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && settings.tun2socks && !root
                    val mode = settings.appsRoutingMode
                    val selected = settings.appsRouting.lineSequence().map(String::trim).filter(String::isNotEmpty).toSet()
                    val apps = app.packageManager.getInstalledApplications(0)
                        .filterNot { it.packageName == app.packageName }
                        .map { info ->
                            val excluded = when (mode) {
                                Settings.AppsRoutingMode.Disabled -> false
                                Settings.AppsRoutingMode.Exclude -> info.packageName in selected
                                Settings.AppsRoutingMode.Include -> info.packageName !in selected
                            }
                            LiveRoutingAppOption(info.packageName, info.loadLabel(app.packageManager).toString(), excluded)
                        }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
                    Triple(root, canMatch, apps)
                }
                rootMode = environment.first
                appMatchingAvailable = environment.second
                apps = environment.third
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error = R.string.liveRoutingAppsLoadFailed
            } finally {
                if (revision == environmentRevision) appsLoading = false
            }
        }
    }

    private fun markRuntimeStatusUnknown() {
        runtimeStatus = LiveRoutingRuntimeStatus(
            running = true,
            groups = runtimeStatus.groups.map { group ->
                group.copy(
                    state = "error", activeProfileId = null, eligibleProfileIds = emptyList(),
                    members = group.members.map { it.copy(state = "checking") },
                )
            },
        )
    }

    private fun change(updated: LiveRoutingPlan) {
        if (isLoading || isBusy || baseline == null || updated == plan) return
        plan = updated
        hasChanges = true
        if (!canReload) error = null
    }

    fun addGroup() = change(plan.copy(groups = plan.groups + ProxyGroup(
        name = app.getString(R.string.liveRoutingGroupDefaultName, plan.groups.size + 1),
    )))

    fun updateGroup(group: ProxyGroup) = change(plan.copy(groups = plan.groups.map {
        if (it.id == group.id) group else it
    }))

    fun selectProfiles(groupId: String, ids: List<Long>, selected: Long?) {
        val current = plan.groups.firstOrNull { it.id == groupId } ?: return
        updateGroup(current.copy(profileIds = ids.distinct(), selectedProfileId = selected?.takeIf { it in ids } ?: ids.firstOrNull()))
    }

    fun selectDefaultGroup(id: String?) {
        if (id != null && plan.groups.none { it.id == id }) return
        change(plan.copy(defaultGroupId = id))
    }

    fun deleteGroup(id: String) {
        if (plan.defaultGroupId == id) {
            error = R.string.liveRoutingDefaultGroupInUse
            return
        }
        if (plan.rules.any { it.target == id }) {
            error = R.string.liveRoutingGroupInUse
            return
        }
        change(plan.copy(groups = plan.groups.filterNot { it.id == id }))
    }

    fun addRule() = change(plan.copy(rules = plan.rules + AppDomainRule(
        name = app.getString(R.string.routingRuleDefaultName, plan.rules.size + 1),
    )))

    fun updateRule(rule: AppDomainRule) = change(plan.copy(rules = plan.rules.map {
        if (it.id == rule.id) rule else it
    }))

    fun domainText(rule: AppDomainRule): String = domainDrafts[rule.id] ?: rule.domains.joinToString("\n")

    fun updateDomains(id: String, text: String) {
        if (isLoading || isBusy || baseline == null) return
        val rule = plan.rules.firstOrNull { it.id == id } ?: return
        domainDrafts = domainDrafts + (id to text)
        updateRule(rule.copy(domains = text.lineSequence().map(String::trim).filter(String::isNotEmpty).distinct().toList()))
    }

    fun selectPackages(id: String, packages: List<String>) {
        val current = plan.rules.firstOrNull { it.id == id } ?: return
        updateRule(current.copy(packages = packages.distinct()))
    }

    fun selectTarget(id: String, target: String) {
        val current = plan.rules.firstOrNull { it.id == id } ?: return
        updateRule(current.copy(target = target))
    }

    fun deleteRule(id: String) {
        change(plan.copy(rules = plan.rules.filterNot { it.id == id }))
        if (!isBusy) domainDrafts = domainDrafts - id
    }

    fun moveRule(from: Int, to: Int) {
        if (from !in plan.rules.indices || to !in plan.rules.indices) return
        change(plan.copy(rules = plan.rules.toMutableList().apply { add(to, removeAt(from)) }))
    }

    fun consumeNotice(expected: LiveRoutingNotice) {
        if (notice === expected) notice = null
    }

    fun save() {
        if (isLoading || isBusy || baseline == null) return
        isBusy = true
        error = null
        val snapshot = plan
        val expected = baseline
        viewModelScope.launch {
            try {
                val (encoded, validationError) = withContext(computationDispatcher) {
                    when {
                        snapshot.groups.any { it.name.isBlank() || it.profileIds.isEmpty() } ->
                            null to R.string.liveRoutingInvalidGroup
                        snapshot.rules.any { it.enabled && it.packages.isEmpty() && it.domains.isEmpty() } ->
                            null to R.string.liveRoutingEmptyRule
                        else -> try {
                            // encode validates the same immutable snapshot before serialization.
                            LiveRoutingHelper.encode(snapshot) to null
                        } catch (_: IllegalArgumentException) {
                            null to R.string.liveRoutingInvalidPlan
                        }
                    }
                }
                if (encoded == null) {
                    error = validationError
                    return@launch
                }
                val result = withContext(ioDispatcher) {
                    when {
                        settings.transparentProxy -> SaveOutcome(R.string.liveRoutingRootUnavailable)
                        snapshot.rules.any { it.enabled && it.packages.isNotEmpty() } &&
                            (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || !settings.tun2socks) ->
                            SaveOutcome(R.string.liveRoutingAppsUnavailable)
                        snapshot.groups.asSequence().flatMap { it.profileIds.asSequence() }.distinct().any {
                            YaxcDatabase.ref(app).profileDao().indexOf(it) == null
                        } -> SaveOutcome(R.string.liveRoutingMissingProfiles)
                        else -> {
                            val packages = if (snapshot.rules.any { it.enabled && it.packages.isNotEmpty() }) {
                                app.packageManager.getInstalledApplications(0).mapTo(mutableSetOf()) { it.packageName }
                            } else emptySet()
                            if (snapshot.rules.any { rule -> rule.enabled && rule.packages.any { it !in packages } }) {
                                SaveOutcome(R.string.liveRoutingMissingApps)
                            } else withContext(NonCancellable) {
                                val stored = synchronized(LiveRoutingViewModel::class.java) {
                                    if (settings.liveRoutingPlan != expected) false
                                    else {
                                        settings.liveRoutingPlan = encoded
                                        true
                                    }
                                }
                                if (!stored) SaveOutcome(R.string.liveRoutingStaleDraft)
                                else {
                                    val dispatched = try {
                                        if (TProxyService.isActive()) TProxyService.applyRouting(app)
                                        true
                                    } catch (_: Exception) {
                                        false
                                    }
                                    SaveOutcome(saved = true, dispatchFailed = !dispatched)
                                }
                            }
                        }
                    }
                }
                if (result.saved) {
                    baseline = encoded
                    savedPlan = snapshot
                    hasChanges = false
                    canReload = false
                    notice = LiveRoutingNotice(
                        if (result.dispatchFailed) R.string.liveRoutingSavedDispatchFailed else R.string.liveRoutingSaved,
                        if (result.dispatchFailed) FeedbackKind.Warning else FeedbackKind.Success,
                    )
                } else {
                    error = result.error
                    canReload = result.error == R.string.liveRoutingStaleDraft
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error = R.string.liveRoutingSaveFailed
            } finally {
                isBusy = false
            }
        }
    }

    private data class SaveOutcome(val error: Int? = null, val saved: Boolean = false, val dispatchFailed: Boolean = false)
}
