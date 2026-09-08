package com.folderspan.pro.di

import com.folderspan.notification.LocalNotifier
import com.folderspan.notification.notificationPlainTextPreview
import com.folderspan.pro.core.common.ApiResult
import com.folderspan.pro.core.datastore.SessionManager
import com.folderspan.pro.core.network.directHttpClient
import com.folderspan.pro.core.network.defaultJson
import com.folderspan.pro.core.network.httpClient
import com.folderspan.pro.data.remote.api.UserApiService
import com.folderspan.pro.data.remote.api.UserNotificationApiService
import com.folderspan.pro.data.repository.DefaultUserNotificationRepository
import com.folderspan.pro.data.repository.DefaultUserRepository
import com.folderspan.pro.domain.model.AccountNotification
import com.folderspan.pro.domain.model.AccountNotificationPayloadKeys
import com.folderspan.pro.domain.model.NotificationActionKind
import com.folderspan.pro.domain.model.NotificationActionStyle
import com.folderspan.pro.domain.model.UserNotificationQuery
import com.folderspan.pro.domain.model.UserNotificationSnapshot
import com.folderspan.pro.domain.model.UserNotificationStatus
import com.folderspan.pro.domain.usecase.UserNotificationSessionService
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlin.math.absoluteValue
import kotlin.random.Random

internal class LazyUserNotificationSessionService(
    client: HttpClient? = null,
    streamClient: HttpClient? = null,
    private val providedSessionService: UserNotificationSessionService? = null,
    private val clientFactory: () -> HttpClient = ::httpClient,
    private val streamClientFactory: () -> HttpClient = ::directHttpClient,
    private val sessionServiceFactory: (HttpClient, HttpClient) -> UserNotificationSessionService =
        ::defaultSessionService,
) {
    private var resolvedClient: HttpClient? = client
    private var resolvedStreamClient: HttpClient? = streamClient
    val hasProvidedSessionService: Boolean
        get() = providedSessionService != null

    val value: UserNotificationSessionService by lazy {
        providedSessionService ?: sessionServiceFactory(
            resolvedClient ?: clientFactory().also { resolvedClient = it },
            resolvedStreamClient ?: streamClientFactory().also { resolvedStreamClient = it },
        )
    }

    fun close() {
        val client = resolvedClient
        val streamClient = resolvedStreamClient
        client?.close()
        if (streamClient !== client) streamClient?.close()
    }
}

class UserNotificationRuntime(
    client: HttpClient? = null,
    streamClient: HttpClient? = null,
    sessionService: UserNotificationSessionService? = null,
    private val onForegroundChanged: (Boolean) -> Unit = {},
) {
    private val lazySessionService = LazyUserNotificationSessionService(
        client = client,
        streamClient = streamClient,
        providedSessionService = sessionService,
    )
    private val mutableState = MutableStateFlow(UserNotificationSnapshot())
    val state: StateFlow<UserNotificationSnapshot> = mutableState.asStateFlow()

    private val foreground = MutableStateFlow(false)
    private val loadMutex = Mutex()
    private val recentEventIds = LinkedHashSet<String>()
    private var controlJob: Job? = null
    private var runtimeScope: CoroutineScope? = null
    private var currentPage = 0

    fun start(scope: CoroutineScope) {
        if (controlJob != null) return
        runtimeScope = scope
        controlJob = scope.launch {
            // Token refresh and identity enrichment mutate AuthSession after startup.
            // Only login/logout transitions may replace the long-lived stream.
            combine(
                SessionManager.session
                    .map { session -> session != null }
                    .distinctUntilChanged(),
                foreground,
            ) { isAuthenticated, isForeground -> isAuthenticated to isForeground }
                .distinctUntilChanged()
                .collectLatest { (isAuthenticated, isForeground) ->
                    if (!isAuthenticated) {
                        currentPage = 0
                        recentEventIds.clear()
                        mutableState.value = UserNotificationSnapshot()
                        return@collectLatest
                    }

                    if (!isForeground) {
                        mutableState.update { it.copy(isStreaming = false) }
                        return@collectLatest
                    }

                    refresh(reset = currentPage == 0)
                    streamUntilCancelled()
                }
        }
    }

    fun enterForeground() {
        foreground.value = true
        onForegroundChanged(true)
    }

    fun enterBackground() {
        foreground.value = false
        onForegroundChanged(false)
    }

    fun setStatusFilter(status: UserNotificationStatus?) {
        if (mutableState.value.statusFilter == status) return
        currentPage = 0
        mutableState.update { it.copy(statusFilter = status, items = emptyList(), hasMore = false) }
        runtimeScope?.launch { refresh(reset = true) }
    }

    suspend fun refresh(reset: Boolean = true) {
        if (SessionManager.currentSession() == null) return
        loadMutex.withLock {
            val sessionService = resolveSessionService()
            mutableState.update { it.copy(isRefreshing = true, errorMessage = null) }
            val status = mutableState.value.statusFilter
            val (pageResult, unreadResult) = coroutineScope {
                val page = async { sessionService.list(UserNotificationQuery(status = status)) }
                val unread = async {
                    sessionService.list(
                        UserNotificationQuery(status = UserNotificationStatus.Unread, pageSize = 1),
                    )
                }
                page.await() to unread.await()
            }
            when (pageResult) {
                is ApiResult.Success -> {
                    val page = pageResult.data
                    currentPage = page.page
                    val items = if (reset) {
                        page.items
                    } else {
                        (page.items + mutableState.value.items).distinctBy(AccountNotification::id)
                    }
                    mutableState.update { current ->
                        current.copy(
                            items = items.sortedByDescending(AccountNotification::createdAtEpochMillis),
                            isRefreshing = false,
                            hasMore = items.size < page.total,
                            errorMessage = null,
                        )
                    }
                }

                is ApiResult.Failure -> mutableState.update {
                    it.copy(isRefreshing = false, errorMessage = pageResult.message)
                }
            }
            if (unreadResult is ApiResult.Success) {
                mutableState.update { it.copy(unreadTotal = unreadResult.data.total) }
            }
        }
    }

    suspend fun loadMore() {
        if (SessionManager.currentSession() == null) return
        val snapshot = mutableState.value
        if (!snapshot.hasMore || snapshot.isLoadingMore || snapshot.isRefreshing) return
        loadMutex.withLock {
            val sessionService = resolveSessionService()
            mutableState.update { it.copy(isLoadingMore = true, errorMessage = null) }
            val nextPage = currentPage + 1
            when (
                val result = sessionService.list(
                    UserNotificationQuery(
                        status = mutableState.value.statusFilter,
                        page = nextPage,
                    ),
                )
            ) {
                is ApiResult.Success -> {
                    currentPage = result.data.page
                    val merged = (mutableState.value.items + result.data.items)
                        .distinctBy(AccountNotification::id)
                        .sortedByDescending(AccountNotification::createdAtEpochMillis)
                    mutableState.update {
                        it.copy(
                            items = merged,
                            isLoadingMore = false,
                            hasMore = merged.size < result.data.total,
                        )
                    }
                }

                is ApiResult.Failure -> mutableState.update {
                    it.copy(isLoadingMore = false, errorMessage = result.message)
                }
            }
        }
    }

    suspend fun markRead(ids: Collection<String>): Boolean {
        val normalized = ids.map(String::trim).filter(String::isNotEmpty).distinct()
        if (normalized.isEmpty()) return true
        val idSet = normalized.toSet()
        mutableState.update { current ->
            current.copy(
                items = current.items.map { item ->
                    if (item.id in idSet) item.copy(status = UserNotificationStatus.Read) else item
                },
                unreadTotal = (current.unreadTotal - current.items.count {
                    it.id in idSet && it.status == UserNotificationStatus.Unread
                }).coerceAtLeast(0),
            )
        }
        val sessionService = resolveSessionService()
        val result = if (normalized.size == 1) {
            sessionService.markRead(normalized.first())
        } else {
            sessionService.batchMarkRead(normalized)
        }
        if (result is ApiResult.Failure) {
            mutableState.update { it.copy(errorMessage = result.message) }
            refresh(reset = true)
            return false
        }
        if (mutableState.value.statusFilter == UserNotificationStatus.Unread) {
            mutableState.update { it.copy(items = it.items.filterNot { item -> item.id in idSet }) }
        }
        return true
    }

    suspend fun markAllLoadedRead(): Boolean =
        markRead(state.value.items.filter { it.status == UserNotificationStatus.Unread }.map(AccountNotification::id))

    suspend fun markUnread(ids: Collection<String>): Boolean {
        val normalized = ids.normalizedNotificationIds()
        if (normalized.isEmpty()) return true
        val idSet = normalized.toSet()
        mutableState.update { current ->
            val newlyUnread = current.items.count {
                it.id in idSet && it.status == UserNotificationStatus.Read
            }
            current.copy(
                items = current.items.map { item ->
                    if (item.id in idSet) {
                        item.copy(status = UserNotificationStatus.Unread, readAtEpochMillis = null)
                    } else {
                        item
                    }
                },
                unreadTotal = current.unreadTotal + newlyUnread,
            )
        }
        val sessionService = resolveSessionService()
        val failure = executeForEach(normalized, sessionService::markUnread)
        if (failure != null) {
            mutableState.update { it.copy(errorMessage = failure.message) }
            refresh(reset = true)
            return false
        }
        if (mutableState.value.statusFilter == UserNotificationStatus.Read) {
            mutableState.update { it.copy(items = it.items.filterNot { item -> item.id in idSet }) }
        }
        return true
    }

    suspend fun delete(ids: Collection<String>): Boolean {
        val normalized = ids.normalizedNotificationIds()
        if (normalized.isEmpty()) return true
        val idSet = normalized.toSet()
        mutableState.update { current ->
            current.copy(
                items = current.items.filterNot { it.id in idSet },
                unreadTotal = (current.unreadTotal - current.items.count {
                    it.id in idSet && it.status == UserNotificationStatus.Unread
                }).coerceAtLeast(0),
            )
        }
        val sessionService = resolveSessionService()
        val failure = executeForEach(normalized, sessionService::delete)
        if (failure != null) {
            mutableState.update { it.copy(errorMessage = failure.message) }
            refresh(reset = true)
            return false
        }
        return true
    }

    private suspend fun executeForEach(
        ids: List<String>,
        operation: suspend (String) -> ApiResult<Unit>,
    ): ApiResult.Failure? {
        ids.forEach { id ->
            val result = operation(id)
            if (result is ApiResult.Failure) return result
        }
        return null
    }

    suspend fun stop() {
        cancel()
        lazySessionService.close()
    }

    fun cancel() {
        controlJob?.cancel()
        controlJob = null
        runtimeScope = null
        foreground.value = false
        mutableState.update { it.copy(isStreaming = false) }
    }

    private suspend fun streamUntilCancelled() {
        val sessionService = resolveSessionService()
        var backoffMillis = 1_000L
        try {
            while (foreground.value && SessionManager.currentSession() != null) {
                mutableState.update { it.copy(isStreaming = true) }
                val result = sessionService.stream(::handleStreamMessage)
                mutableState.update { it.copy(isStreaming = false) }
                if (result is ApiResult.Failure) {
                    mutableState.update { it.copy(errorMessage = result.message) }
                }
                if (!foreground.value || SessionManager.currentSession() == null) {
                    return
                }
                val jitter = (backoffMillis * 0.2).toLong()
                val reconnectDelayMillis = backoffMillis + Random.nextLong(-jitter, jitter + 1)
                delay(reconnectDelayMillis)
                backoffMillis = (backoffMillis * 2).coerceAtMost(30_000L)
            }
        } finally {
            mutableState.update { it.copy(isStreaming = false) }
        }
    }

    private suspend fun handleStreamMessage(notification: AccountNotification) {
        if (!rememberEvent(notification.id)) return
        mutableState.update { it.copy(errorMessage = null) }
        LocalNotifier.notify(
            id = accountSystemNotificationId(notification.id),
            title = notification.title.ifBlank { "FolderSpan" },
            body = accountSystemNotificationBody(notification.content),
            payloadData = buildMap {
                put(AccountNotificationPayloadKeys.Source, AccountNotificationPayloadKeys.SourceAccount)
                put(AccountNotificationPayloadKeys.Id, notification.id)
                val primaryAction = notification.actions
                    .firstOrNull { action -> action.style == NotificationActionStyle.Primary }
                    ?: notification.actions.firstOrNull()
                primaryAction?.let { action ->
                    put(AccountNotificationPayloadKeys.PrimaryActionKind, action.kind.wireValue)
                    val target = when (action.kind) {
                        NotificationActionKind.Url -> action.url
                        NotificationActionKind.Route -> action.route
                    }
                    target?.let { value -> put(AccountNotificationPayloadKeys.PrimaryActionTarget, value) }
                    put(AccountNotificationPayloadKeys.PrimaryActionParams, defaultJson.encodeToString(action.params))
                }
            },
        )
        refresh(reset = false)
    }

    private fun rememberEvent(id: String): Boolean {
        if (!recentEventIds.add(id)) return false
        while (recentEventIds.size > 256) {
            recentEventIds.remove(recentEventIds.first())
        }
        return true
    }

    private suspend fun resolveSessionService(): UserNotificationSessionService =
        if (lazySessionService.hasProvidedSessionService) {
            lazySessionService.value
        } else {
            withContext(Dispatchers.Default) { lazySessionService.value }
        }
}

private fun defaultSessionService(
    client: HttpClient,
    streamClient: HttpClient,
): UserNotificationSessionService {
    val repository = DefaultUserNotificationRepository(
        UserNotificationApiService(client = client, streamClient = streamClient),
    )
    val userRepository = DefaultUserRepository(UserApiService(client))
    return UserNotificationSessionService(repository, userRepository)
}

private fun accountSystemNotificationId(id: String): Int =
    0x40000000 or (id.hashCode().absoluteValue and 0x3fffffff)

internal fun accountSystemNotificationBody(content: String): String =
    notificationPlainTextPreview(content)

private fun Collection<String>.normalizedNotificationIds(): List<String> =
    map(String::trim).filter(String::isNotEmpty).distinct()
