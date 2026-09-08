package com.folderspan.pro.di

import com.folderspan.PlatformType
import com.folderspan.data.main.device.DeviceType
import com.folderspan.notification.RequestNotificationDispatcher
import com.folderspan.notification.RequestNotificationFactory
import com.folderspan.pro.core.common.ApiResult
import com.folderspan.pro.core.network.httpClient
import com.folderspan.pro.data.remote.api.MessageApiService
import com.folderspan.pro.data.repository.DefaultAppUpdateRepository
import com.folderspan.pro.domain.model.AppUpdate
import com.folderspan.pro.domain.model.AppUpdateChannel
import com.folderspan.pro.domain.model.AppUpdateHistorySnapshot
import com.folderspan.pro.domain.model.AppUpdateSnapshot
import com.folderspan.pro.domain.model.AppUpdateStatus
import com.folderspan.pro.domain.model.announcementPlatformToken
import com.folderspan.pro.domain.repository.AppUpdateRepository
import com.folderspan.ui.state.main.NotificationState
import com.folderspan.utils.currentAppVersion
import com.folderspan.utils.isNewerAppVersion
import com.folderspan.utils.parseSemanticAppVersion
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import strings.AppStrings
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

class AppUpdateRuntime(
    repository: AppUpdateRepository? = null,
    client: HttpClient? = null,
    private val notificationState: NotificationState? = null,
    private val currentVersion: () -> String = ::currentAppVersion,
    private val platformToken: () -> String = { announcementPlatformToken() },
    private val platformType: () -> DeviceType = { PlatformType },
    private val readChannel: () -> AppUpdateChannel = { AppUpdateChannel.Release },
    private val writeChannel: (AppUpdateChannel) -> Unit = {},
    private val checkInterval: Duration = DEFAULT_CHECK_INTERVAL,
    private val postUpdate: (NotificationState, AppUpdate) -> Unit = { state, update ->
        RequestNotificationDispatcher.post(
            state,
            RequestNotificationFactory.buildAppUpdateNotification(
                version = update.version,
                link = update.link,
                title = update.title,
                message = update.content,
            ),
        )
    },
) {
    private val resolvedRepository: AppUpdateRepository = repository
        ?: DefaultAppUpdateRepository(MessageApiService(client ?: httpClient()))
    private val mutableState = MutableStateFlow(AppUpdateSnapshot())
    val state: StateFlow<AppUpdateSnapshot> = mutableState.asStateFlow()
    private val mutableChannel = MutableStateFlow(readChannel())
    val selectedChannel: StateFlow<AppUpdateChannel> = mutableChannel.asStateFlow()
    private val mutableHistory = MutableStateFlow(AppUpdateHistorySnapshot())
    val history: StateFlow<AppUpdateHistorySnapshot> = mutableHistory.asStateFlow()

    private val loadMutex = Mutex()
    private val historyMutex = Mutex()
    private var startJob: Job? = null
    private var historyPage = 0

    fun start(scope: CoroutineScope) {
        if (startJob != null) return
        if (platformType() == DeviceType.JS) return
        startJob = scope.launch {
            check(notify = true)
            while (isActive) {
                delay(checkInterval)
                check(notify = true)
            }
        }
    }

    fun cancel() {
        startJob?.cancel()
        startJob = null
    }

    fun setChannel(channel: AppUpdateChannel) {
        if (channel == mutableChannel.value) return
        writeChannel(channel)
        mutableChannel.value = channel
        resetHistory()
        mutableState.value = AppUpdateSnapshot()
    }

    suspend fun refreshHistory() {
        historyMutex.withLock {
            mutableHistory.update { it.copy(isRefreshing = true, errorMessage = null) }
            when (
                val result = resolvedRepository.list(
                    platform = platformToken(),
                    channel = mutableChannel.value.token,
                    page = 1,
                )
            ) {
                is ApiResult.Success -> {
                    val page = result.data
                    historyPage = page.page
                    mutableHistory.value = AppUpdateHistorySnapshot(
                        items = page.items,
                        hasMore = page.items.size < page.total,
                    )
                }

                is ApiResult.Failure -> mutableHistory.update {
                    it.copy(
                        isRefreshing = false,
                        errorMessage = AppStrings.settings_about_software_history_failed,
                    )
                }
            }
        }
    }

    suspend fun loadMoreHistory() {
        historyMutex.withLock {
            val current = mutableHistory.value
            if (!current.hasMore || current.isRefreshing || current.isLoadingMore) return
            mutableHistory.update { it.copy(isLoadingMore = true, errorMessage = null) }
            when (
                val result = resolvedRepository.list(
                    platform = platformToken(),
                    channel = mutableChannel.value.token,
                    page = historyPage + 1,
                )
            ) {
                is ApiResult.Success -> {
                    val page = result.data
                    historyPage = page.page
                    val items = (current.items + page.items).distinctBy(AppUpdate::id)
                    mutableHistory.update {
                        it.copy(
                            items = items,
                            isLoadingMore = false,
                            hasMore = items.size < page.total,
                            errorMessage = null,
                        )
                    }
                }

                is ApiResult.Failure -> mutableHistory.update {
                    it.copy(
                        isLoadingMore = false,
                        errorMessage = AppStrings.settings_about_software_history_failed,
                    )
                }
            }
        }
    }

    suspend fun check(notify: Boolean) {
        loadMutex.withLock {
            val requestedChannel = mutableChannel.value
            mutableState.update {
                it.copy(status = AppUpdateStatus.Checking, errorMessage = null)
            }
            val runningVersion = currentVersion()
            if (parseSemanticAppVersion(runningVersion) == null) {
                if (mutableChannel.value != requestedChannel) return
                mutableState.update {
                    it.copy(
                        status = AppUpdateStatus.Error,
                        update = null,
                        errorMessage = AppStrings.settings_about_software_check_failed,
                    )
                }
                return
            }
            when (val result = resolvedRepository.latest(platformToken(), requestedChannel.token)) {
                is ApiResult.Success -> {
                    if (mutableChannel.value != requestedChannel) return
                    applyLatest(result.data, runningVersion, notify)
                }

                is ApiResult.Failure -> {
                    if (mutableChannel.value != requestedChannel) return
                    mutableState.update {
                        it.copy(
                            status = AppUpdateStatus.Error,
                            update = null,
                            errorMessage = AppStrings.settings_about_software_check_failed,
                        )
                    }
                }
            }
        }
    }

    private fun applyLatest(latest: AppUpdate?, runningVersion: String, notify: Boolean) {
        val parsedLatest = latest?.version?.let(::parseSemanticAppVersion)
        if (latest == null || parsedLatest == null || !isNewerAppVersion(latest.version, runningVersion)) {
            mutableState.update {
                it.copy(status = AppUpdateStatus.UpToDate, update = latest, errorMessage = null)
            }
            return
        }
        mutableState.update {
            it.copy(status = AppUpdateStatus.Newer, update = latest, errorMessage = null)
        }
        if (notify) {
            notificationState?.let { state -> postUpdate(state, latest) }
        }
    }

    private fun resetHistory() {
        historyPage = 0
        mutableHistory.value = AppUpdateHistorySnapshot()
    }

    companion object {
        val DEFAULT_CHECK_INTERVAL: Duration = 5.hours
    }
}
