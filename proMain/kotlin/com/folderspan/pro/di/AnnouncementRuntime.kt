package com.folderspan.pro.di

import com.folderspan.pro.core.common.ApiResult
import com.folderspan.pro.core.network.httpClient
import com.folderspan.pro.data.remote.api.MessageApiService
import com.folderspan.pro.data.repository.DefaultAnnouncementRepository
import com.folderspan.pro.domain.model.ANNOUNCEMENT_LIST_TYPE
import com.folderspan.pro.domain.model.Announcement
import com.folderspan.pro.domain.model.AnnouncementQuery
import com.folderspan.pro.domain.model.AnnouncementSnapshot
import com.folderspan.pro.domain.model.announcementPlatformToken
import com.folderspan.pro.domain.repository.AnnouncementRepository
import com.folderspan.utils.SettingsUtils
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

interface AnnouncementCutoffStore {
    fun read(): Long?
    fun write(value: Long)
}

class SettingsAnnouncementCutoffStore : AnnouncementCutoffStore {
    override fun read(): Long? = SettingsUtils.announcementReadCutoffOrNull()

    override fun write(value: Long) {
        SettingsUtils.persistAnnouncementReadCutoff(value)
    }
}

class InMemoryAnnouncementCutoffStore(
    initial: Long? = null,
) : AnnouncementCutoffStore {
    private var value: Long? = initial

    override fun read(): Long? = value

    override fun write(value: Long) {
        this.value = value
    }
}

class AnnouncementRuntime(
    repository: AnnouncementRepository? = null,
    client: HttpClient? = null,
    private val cutoffStore: AnnouncementCutoffStore = SettingsAnnouncementCutoffStore(),
    private val platformToken: () -> String = { announcementPlatformToken() },
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val resolvedRepository: AnnouncementRepository = repository
        ?: DefaultAnnouncementRepository(MessageApiService(client ?: httpClient()))
    private val mutableState = MutableStateFlow(AnnouncementSnapshot())
    val state: StateFlow<AnnouncementSnapshot> = mutableState.asStateFlow()

    private val loadMutex = Mutex()
    private var runtimeScope: CoroutineScope? = null
    private var startJob: Job? = null
    private var currentPage = 0

    fun start(scope: CoroutineScope) {
        if (startJob != null) return
        runtimeScope = scope
        ensureCutoff()
        startJob = scope.launch { refresh(reset = true) }
    }

    fun cancel() {
        startJob?.cancel()
        startJob = null
        runtimeScope = null
    }

    fun ensureCutoff(): Long {
        val existing = cutoffStore.read()
        if (existing != null) {
            mutableState.update { it.copy(cutoffEpochMillis = existing) }
            return existing
        }
        val cutoff = nowMillis()
        cutoffStore.write(cutoff)
        mutableState.update { it.copy(cutoffEpochMillis = cutoff) }
        return cutoff
    }

    suspend fun refresh(reset: Boolean = true) {
        ensureCutoff()
        loadMutex.withLock {
            mutableState.update { it.copy(isRefreshing = true, errorMessage = null) }
            val query = currentQuery(page = 1)
            when (val result = resolvedRepository.list(query)) {
                is ApiResult.Success -> {
                    val page = result.data
                    currentPage = page.page
                    val items = if (reset) {
                        page.items
                    } else {
                        (page.items + mutableState.value.items).distinctBy(Announcement::id)
                    }.sortedByDescending(Announcement::publishedAtEpochMillis)
                    mutableState.update { current ->
                        current.copy(
                            items = items,
                            isRefreshing = false,
                            hasMore = items.size < page.total,
                            errorMessage = null,
                        )
                    }
                }

                is ApiResult.Failure -> mutableState.update {
                    it.copy(isRefreshing = false, errorMessage = result.message)
                }
            }
        }
    }

    suspend fun loadMore() {
        val snapshot = mutableState.value
        if (!snapshot.hasMore || snapshot.isLoadingMore || snapshot.isRefreshing) return
        loadMutex.withLock {
            mutableState.update { it.copy(isLoadingMore = true, errorMessage = null) }
            val nextPage = currentPage + 1
            when (val result = resolvedRepository.list(currentQuery(page = nextPage))) {
                is ApiResult.Success -> {
                    currentPage = result.data.page
                    val merged = (mutableState.value.items + result.data.items)
                        .distinctBy(Announcement::id)
                        .sortedByDescending(Announcement::publishedAtEpochMillis)
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

    fun markAllLoadedRead() {
        val loadedMax = mutableState.value.items.maxOfOrNull(Announcement::publishedAtEpochMillis) ?: 0L
        val cutoff = maxOf(nowMillis(), loadedMax)
        cutoffStore.write(cutoff)
        mutableState.update { it.copy(cutoffEpochMillis = cutoff) }
    }

    private fun currentQuery(page: Int) = AnnouncementQuery(
        type = ANNOUNCEMENT_LIST_TYPE,
        platform = platformToken(),
        page = page,
    )
}
