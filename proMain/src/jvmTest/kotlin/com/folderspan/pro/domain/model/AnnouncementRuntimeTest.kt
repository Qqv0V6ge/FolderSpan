package com.folderspan.pro.domain.model

import com.folderspan.data.main.device.DeviceType
import com.folderspan.pro.core.common.ApiResult
import com.folderspan.pro.di.AnnouncementRuntime
import com.folderspan.pro.di.InMemoryAnnouncementCutoffStore
import com.folderspan.pro.domain.repository.AnnouncementRepository
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnnouncementRuntimeTest {
    @Test
    fun platformTokenMapsDeviceTypesAndDesktopOs() {
        assertEquals("android", announcementPlatformToken(DeviceType.Android, osName = "ignored"))
        assertEquals("ios", announcementPlatformToken(DeviceType.IOS, osName = "ignored"))
        assertEquals("js", announcementPlatformToken(DeviceType.JS, osName = "ignored"))
        assertEquals("macos", announcementPlatformToken(DeviceType.JVM, osName = "Mac OS X"))
        assertEquals("macos", announcementPlatformToken(DeviceType.JVM, osName = "darwin"))
        assertEquals("windows", announcementPlatformToken(DeviceType.JVM, osName = "Windows 11"))
        assertEquals("linux", announcementPlatformToken(DeviceType.JVM, osName = "Linux"))
    }

    @Test
    fun firstRunWritesNowAsCutoffAndLaterPublicationsAreUnread() {
        val store = InMemoryAnnouncementCutoffStore()
        val runtime = AnnouncementRuntime(
            repository = StaticAnnouncementRepository(),
            cutoffStore = store,
            platformToken = { "linux" },
            nowMillis = { 1_000L },
        )

        assertEquals(1_000L, runtime.ensureCutoff())
        assertEquals(1_000L, store.read())
        assertEquals(1_000L, runtime.ensureCutoff())
        assertTrue(sample(publishedAt = 1_000L).isRead(1_000L))
        assertFalse(sample(publishedAt = 1_001L).isRead(1_000L))
    }

    @Test
    fun existingCutoffIsNotRewrittenOnLaterEnsure() {
        val store = InMemoryAnnouncementCutoffStore(initial = 50L)
        val runtime = AnnouncementRuntime(
            repository = StaticAnnouncementRepository(),
            cutoffStore = store,
            platformToken = { "linux" },
            nowMillis = { 9_000L },
        )

        assertEquals(50L, runtime.ensureCutoff())
        assertEquals(50L, store.read())
    }

    @Test
    fun markAllLoadedReadCoversLoadedItems() = runTest {
        val store = InMemoryAnnouncementCutoffStore(initial = 10L)
        val runtime = AnnouncementRuntime(
            repository = StaticAnnouncementRepository(
                AnnouncementPage(
                    items = listOf(sample(id = 2, publishedAt = 40L), sample(id = 1, publishedAt = 20L)),
                    total = 2,
                    page = 1,
                    pageSize = 20,
                ),
            ),
            cutoffStore = store,
            platformToken = { "linux" },
            nowMillis = { 30L },
        )

        runtime.refresh()
        runtime.markAllLoadedRead()

        assertEquals(40L, store.read())
        assertTrue(runtime.state.value.items.all { it.isRead(runtime.state.value.cutoffEpochMillis) })
    }

    @Test
    fun refreshMergesPagesAndTreatsEmptyAsSuccess() = runTest {
        val repository = PagingAnnouncementRepository()
        val runtime = AnnouncementRuntime(
            repository = repository,
            cutoffStore = InMemoryAnnouncementCutoffStore(initial = 1L),
            platformToken = { "android" },
            nowMillis = { 1L },
        )

        runtime.refresh()
        assertEquals(listOf(2L, 1L), runtime.state.value.items.map(Announcement::id))
        assertTrue(runtime.state.value.hasMore)
        runtime.loadMore()
        assertEquals(listOf(3L, 2L, 1L), runtime.state.value.items.map(Announcement::id))
        assertFalse(runtime.state.value.hasMore)
        assertNull(runtime.state.value.errorMessage)
    }

    @Test
    fun emptyPageClearsErrorAndOtherFailuresStayRetryable() = runTest {
        val repository = ScriptedAnnouncementRepository(
            ApiResult.Success(AnnouncementPage(emptyList(), total = 0, page = 1, pageSize = 20)),
            ApiResult.Failure("unavailable"),
        )
        val runtime = AnnouncementRuntime(
            repository = repository,
            cutoffStore = InMemoryAnnouncementCutoffStore(initial = 1L),
            platformToken = { "ios" },
            nowMillis = { 1L },
        )

        runtime.refresh()
        assertEquals(emptyList(), runtime.state.value.items)
        assertNull(runtime.state.value.errorMessage)

        runtime.refresh()
        assertEquals("unavailable", runtime.state.value.errorMessage)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun startDoesNotRewriteExistingCutoffAndDetailHasNoCutoffApi() = runTest {
        val store = InMemoryAnnouncementCutoffStore(initial = 77L)
        val runtime = AnnouncementRuntime(
            repository = StaticAnnouncementRepository(),
            cutoffStore = store,
            platformToken = { "js" },
            nowMillis = { 500L },
        )

        runtime.start(backgroundScope)
        runCurrent()

        assertEquals(77L, store.read())
        assertEquals(77L, runtime.state.value.cutoffEpochMillis)
    }

    private fun sample(
        id: Long = 1L,
        publishedAt: Long = 1L,
        content: String = "body",
    ) = Announcement(
        id = id,
        type = "announcement",
        title = "Title $id",
        content = content,
        version = "",
        platform = "linux",
        link = "",
        locale = "en-US",
        publishedAtEpochMillis = publishedAt,
        actions = emptyList(),
    )

    private class StaticAnnouncementRepository(
        private val page: AnnouncementPage = AnnouncementPage(emptyList(), 0, 1, 20),
    ) : AnnouncementRepository {
        override suspend fun list(query: AnnouncementQuery): ApiResult<AnnouncementPage> = ApiResult.Success(page)
    }

    private class PagingAnnouncementRepository : AnnouncementRepository {
        override suspend fun list(query: AnnouncementQuery): ApiResult<AnnouncementPage> = when (query.page) {
            1 -> ApiResult.Success(
                AnnouncementPage(
                    items = listOf(sample(id = 1, publishedAt = 10), sample(id = 2, publishedAt = 20)),
                    total = 3,
                    page = 1,
                    pageSize = 2,
                ),
            )
            else -> ApiResult.Success(
                AnnouncementPage(
                    items = listOf(sample(id = 3, publishedAt = 30)),
                    total = 3,
                    page = 2,
                    pageSize = 2,
                ),
            )
        }

        private fun sample(id: Long, publishedAt: Long) = Announcement(
            id = id,
            type = "announcement",
            title = "Title $id",
            content = "body",
            version = "",
            platform = "android",
            link = "",
            locale = null,
            publishedAtEpochMillis = publishedAt,
            actions = emptyList(),
        )
    }

    private class ScriptedAnnouncementRepository(
        private vararg val results: ApiResult<AnnouncementPage>,
    ) : AnnouncementRepository {
        private var index = 0
        override suspend fun list(query: AnnouncementQuery): ApiResult<AnnouncementPage> {
            val result = results[index.coerceAtMost(results.lastIndex)]
            index += 1
            return result
        }
    }
}
