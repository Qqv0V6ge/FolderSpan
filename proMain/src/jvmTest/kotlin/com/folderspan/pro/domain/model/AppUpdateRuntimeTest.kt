package com.folderspan.pro.domain.model

import com.folderspan.data.main.device.DeviceType
import com.folderspan.pro.core.common.ApiResult
import com.folderspan.pro.di.AppUpdateRuntime
import com.folderspan.pro.domain.repository.AppUpdateRepository
import com.folderspan.ui.state.main.NotificationState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

@OptIn(ExperimentalCoroutinesApi::class)
class AppUpdateRuntimeTest {
    @Test
    fun startOnJsDoesNotCheck() = runTest {
        val repository = RecordingAppUpdateRepository()
        val runtime = AppUpdateRuntime(
            repository = repository,
            platformType = { DeviceType.JS },
            currentVersion = { "1.0.0" },
        )

        runtime.start(this)
        runCurrent()

        assertEquals(0, repository.calls)
        assertEquals(AppUpdateStatus.Idle, runtime.state.value.status)
        runtime.cancel()
    }

    @Test
    fun silentCheckDoesNotPostNotification() = runTest {
        val posts = mutableListOf<AppUpdate>()
        val repository = RecordingAppUpdateRepository(newerUpdate())
        val runtime = AppUpdateRuntime(
            repository = repository,
            notificationState = NotificationState(),
            platformType = { DeviceType.JVM },
            currentVersion = { "1.0.0" },
            postUpdate = { _, update -> posts += update },
        )

        runtime.check(notify = false)

        assertEquals(AppUpdateStatus.Newer, runtime.state.value.status)
        assertEquals("1.2.0", runtime.state.value.update?.version)
        assertEquals(emptyList(), posts)
    }

    @Test
    fun automaticCheckPostsOnlyWhenNewer() = runTest {
        val posts = mutableListOf<AppUpdate>()
        val repository = RecordingAppUpdateRepository(newerUpdate())
        val runtime = AppUpdateRuntime(
            repository = repository,
            notificationState = NotificationState(),
            platformType = { DeviceType.Android },
            currentVersion = { "1.0.0" },
            postUpdate = { _, update -> posts += update },
        )

        runtime.check(notify = true)
        assertEquals(listOf("1.2.0"), posts.map(AppUpdate::version))

        posts.clear()
        repository.result = newerUpdate(version = "1.0.0")
        runtime.check(notify = true)
        assertEquals(AppUpdateStatus.UpToDate, runtime.state.value.status)
        assertEquals(emptyList(), posts)

        repository.result = null
        runtime.check(notify = true)
        assertEquals(emptyList(), posts)
    }

    @Test
    fun emptyCatalogAndMalformedVersionStaySilent() = runTest {
        val posts = mutableListOf<AppUpdate>()
        val repository = RecordingAppUpdateRepository(null)
        val runtime = AppUpdateRuntime(
            repository = repository,
            notificationState = NotificationState(),
            platformType = { DeviceType.IOS },
            currentVersion = { "1.0.0" },
            postUpdate = { _, update -> posts += update },
        )

        runtime.check(notify = true)
        assertEquals(AppUpdateStatus.UpToDate, runtime.state.value.status)
        assertEquals(emptyList(), posts)

        repository.result = newerUpdate(version = "2.0.0-beta")
        runtime.check(notify = true)
        assertEquals(AppUpdateStatus.UpToDate, runtime.state.value.status)
        assertEquals(emptyList(), posts)
    }

    @Test
    fun startChecksImmediatelyThenAfterFiveHours() = runTest {
        val repository = RecordingAppUpdateRepository(newerUpdate())
        val runtime = AppUpdateRuntime(
            repository = repository,
            notificationState = NotificationState(),
            platformType = { DeviceType.JVM },
            currentVersion = { "1.0.0" },
            postUpdate = { _, _ -> },
        )

        runtime.start(this)
        runCurrent()
        assertEquals(1, repository.calls)

        advanceTimeBy(5.hours.inWholeMilliseconds)
        runCurrent()
        assertEquals(2, repository.calls)
        runtime.cancel()
    }

    @Test
    fun unparseableCurrentVersionIsAnErrorWithoutPosting() = runTest {
        val posts = mutableListOf<AppUpdate>()
        val repository = RecordingAppUpdateRepository(newerUpdate())
        val runtime = AppUpdateRuntime(
            repository = repository,
            notificationState = NotificationState(),
            currentVersion = { "v1.0.0" },
            postUpdate = { _, update -> posts += update },
        )

        runtime.check(notify = true)

        assertEquals(AppUpdateStatus.Error, runtime.state.value.status)
        assertTrue(runtime.state.value.errorMessage.orEmpty().isNotBlank())
        assertEquals(0, repository.calls)
        assertEquals(emptyList(), posts)
    }

    @Test
    fun requestFailureIsRetryableAndDoesNotLookUpToDate() = runTest {
        val repository = RecordingAppUpdateRepository(failure = true)
        val runtime = AppUpdateRuntime(
            repository = repository,
            notificationState = NotificationState(),
            currentVersion = { "1.0.0" },
        )

        runtime.check(notify = false)

        assertEquals(AppUpdateStatus.Error, runtime.state.value.status)
        assertNull(runtime.state.value.update)
        assertTrue(runtime.state.value.errorMessage.orEmpty().isNotBlank())
    }

    @Test
    fun checkUsesSelectedChannelAndSwitchingDoesNotRequest() = runTest {
        val stored = mutableListOf(AppUpdateChannel.Release)
        val repository = RecordingAppUpdateRepository(newerUpdate())
        val runtime = AppUpdateRuntime(
            repository = repository,
            notificationState = NotificationState(),
            platformType = { DeviceType.JVM },
            currentVersion = { "1.0.0" },
            readChannel = { stored.last() },
            writeChannel = { stored += it },
            postUpdate = { _, _ -> },
        )

        runtime.check(notify = false)
        assertEquals(listOf("release"), repository.channels)
        assertEquals(AppUpdateStatus.Newer, runtime.state.value.status)

        runtime.setChannel(AppUpdateChannel.Beta)
        assertEquals(AppUpdateChannel.Beta, runtime.selectedChannel.value)
        assertEquals(listOf("release"), repository.channels)
        assertEquals(1, repository.calls)
        assertEquals(AppUpdateStatus.Idle, runtime.state.value.status)
        assertEquals(listOf(AppUpdateChannel.Release, AppUpdateChannel.Beta), stored)

        runtime.setChannel(AppUpdateChannel.Beta)
        assertEquals(1, repository.calls)

        runtime.check(notify = false)
        assertEquals(listOf("release", "beta"), repository.channels)
        assertEquals(2, repository.calls)
    }

    @Test
    fun refreshHistoryLoadsPagedItemsWithoutCheckingLatest() = runTest {
        val first = newerUpdate(version = "1.2.0")
        val second = newerUpdate(version = "1.1.0").copy(id = 7L)
        val repository = RecordingAppUpdateRepository(
            pages = listOf(
                AppUpdatePage(items = listOf(first, second), total = 3, page = 1, pageSize = 20),
                AppUpdatePage(
                    items = listOf(newerUpdate(version = "1.0.0").copy(id = 6L)),
                    total = 3,
                    page = 2,
                    pageSize = 20,
                ),
            ),
        )
        val runtime = AppUpdateRuntime(
            repository = repository,
            platformType = { DeviceType.JS },
            currentVersion = { "1.0.0" },
        )

        runtime.refreshHistory()
        assertEquals(listOf("1.2.0", "1.1.0"), runtime.history.value.items.map(AppUpdate::version))
        assertTrue(runtime.history.value.hasMore)
        assertEquals(0, repository.calls)
        assertEquals(listOf("release"), repository.listChannels)

        runtime.loadMoreHistory()
        assertEquals(
            listOf("1.2.0", "1.1.0", "1.0.0"),
            runtime.history.value.items.map(AppUpdate::version),
        )
        assertTrue(!runtime.history.value.hasMore)
    }

    @Test
    fun switchingChannelClearsHistory() = runTest {
        val repository = RecordingAppUpdateRepository(
            result = newerUpdate(),
            pages = listOf(
                AppUpdatePage(items = listOf(newerUpdate()), total = 1, page = 1, pageSize = 20),
            ),
        )
        val runtime = AppUpdateRuntime(
            repository = repository,
            currentVersion = { "1.0.0" },
            platformType = { DeviceType.JVM },
            postUpdate = { _, _ -> },
        )

        runtime.refreshHistory()
        assertEquals(1, runtime.history.value.items.size)

        runtime.setChannel(AppUpdateChannel.Beta)
        assertEquals(emptyList(), runtime.history.value.items)
        assertEquals(0, repository.calls)
        assertEquals(1, repository.listCalls)
    }

    private fun newerUpdate(version: String = "1.2.0") = AppUpdate(
        id = 8L,
        title = "FolderSpan 1.2",
        content = "Fixes",
        version = version,
        platform = "linux",
        link = "https://example.test/download",
        publishedAtEpochMillis = 1_700_000_000_000L,
    )
}

private class RecordingAppUpdateRepository(
    var result: AppUpdate? = null,
    var failure: Boolean = false,
    var pages: List<AppUpdatePage> = emptyList(),
    var listFailure: Boolean = false,
) : AppUpdateRepository {
    var calls = 0
        private set
    var listCalls = 0
        private set
    val channels = mutableListOf<String>()
    val listChannels = mutableListOf<String>()

    override suspend fun latest(platform: String, channel: String): ApiResult<AppUpdate?> {
        calls += 1
        channels += channel
        if (failure) return ApiResult.Failure("boom")
        return ApiResult.Success(result)
    }

    override suspend fun list(
        platform: String,
        channel: String,
        page: Int,
        pageSize: Int,
    ): ApiResult<AppUpdatePage> {
        listCalls += 1
        listChannels += channel
        if (listFailure) return ApiResult.Failure("boom")
        val index = (page - 1).coerceAtLeast(0)
        return ApiResult.Success(pages.getOrElse(index) { AppUpdatePage(page = page, pageSize = pageSize) })
    }
}
