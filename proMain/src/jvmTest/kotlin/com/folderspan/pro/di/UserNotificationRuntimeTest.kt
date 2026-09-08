package com.folderspan.pro.di

import com.folderspan.pro.core.common.ApiResult
import com.folderspan.pro.core.datastore.AuthSession
import com.folderspan.pro.core.datastore.InMemoryAuthSessionStore
import com.folderspan.pro.core.datastore.SessionManager
import com.folderspan.pro.domain.model.AccountNotification
import com.folderspan.pro.domain.model.UserNotificationPage
import com.folderspan.pro.domain.model.UserNotificationQuery
import com.folderspan.pro.domain.repository.UserNotificationRepository
import com.folderspan.pro.domain.usecase.UserNotificationSessionService
import com.folderspan.pro.testing.FakeUserRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

@OptIn(ExperimentalCoroutinesApi::class)
class UserNotificationRuntimeTest {
    @AfterTest
    fun tearDown() {
        SessionManager.clear()
    }

    @Test
    fun foregroundChangesAreForwardedToNotificationDeliveryPolicy() {
        val observedForegroundStates = mutableListOf<Boolean>()
        val runtime = UserNotificationRuntime(
            onForegroundChanged = observedForegroundStates::add,
        )

        runtime.enterForeground()
        runtime.enterBackground()

        assertEquals(listOf(true, false), observedForegroundStates)
    }

    @Test
    fun networkDependenciesAreCreatedOnlyWhenTheSessionServiceIsUsed() {
        val client = HttpClient(MockEngine { error("HTTP client is not used in this test") })
        val sessionService = UserNotificationSessionService(
            RecordingNotificationRepository(),
            FakeUserRepository(),
        )
        var clientCreationCount = 0
        var streamClientCreationCount = 0
        val dependencies = LazyUserNotificationSessionService(
            clientFactory = {
                clientCreationCount += 1
                client
            },
            streamClientFactory = {
                streamClientCreationCount += 1
                client
            },
            sessionServiceFactory = { _, _ -> sessionService },
        )

        assertEquals(0, clientCreationCount)
        assertEquals(0, streamClientCreationCount)
        assertSame(sessionService, dependencies.value)
        assertSame(sessionService, dependencies.value)
        assertEquals(1, clientCreationCount)
        assertEquals(1, streamClientCreationCount)
        client.close()
    }

    @Test
    fun streamStartsOnceAndReconnectsOnlyAfterDisconnect() = runTest {
        SessionManager.initialize(
            InMemoryAuthSessionStore(AuthSession(accessToken = "test-token")),
        )
        val repository = RecordingNotificationRepository()
        val client = HttpClient(MockEngine { error("HTTP client is not used in this test") })
        val runtime = UserNotificationRuntime(
            client = client,
            streamClient = client,
            sessionService = UserNotificationSessionService(repository, FakeUserRepository()),
        )

        runtime.enterForeground()
        runtime.start(backgroundScope)
        runCurrent()

        assertEquals(1, repository.streamCallCount)

        runtime.enterForeground()
        runCurrent()

        assertEquals(1, repository.streamCallCount)

        advanceTimeBy(1_201)
        runCurrent()

        assertEquals(2, repository.streamCallCount)
        runtime.stop()
    }

    @Test
    fun sameUserSessionRefreshKeepsTheExistingStream() = runTest {
        SessionManager.initialize(
            InMemoryAuthSessionStore(
                AuthSession(
                    accessToken = "token-before-refresh",
                    refreshToken = "refresh-before",
                    userUuid = "user-1",
                ),
            ),
        )
        val repository = BlockingNotificationRepository()
        val client = HttpClient(MockEngine { error("HTTP client is not used in this test") })
        val runtime = UserNotificationRuntime(
            client = client,
            streamClient = client,
            sessionService = UserNotificationSessionService(repository, FakeUserRepository()),
        )

        runtime.enterForeground()
        runtime.start(backgroundScope)
        runCurrent()
        assertEquals(1, repository.streamCallCount)

        runtime.start(backgroundScope)
        runCurrent()
        assertEquals(1, repository.streamCallCount)

        SessionManager.update(
            AuthSession(
                accessToken = "token-after-refresh",
                refreshToken = "refresh-after",
                expiresAtEpochSeconds = 9_999_999_999,
                userUuid = "user-1",
            ),
        )
        runCurrent()

        assertEquals(1, repository.streamCallCount)
        assertEquals(0, repository.streamCancellationCount)

        runtime.stop()
        runCurrent()
        assertEquals(1, repository.streamCancellationCount)
    }

    @Test
    fun progressiveSessionIdentityEnrichmentKeepsTheExistingStream() = runTest {
        SessionManager.initialize(
            InMemoryAuthSessionStore(
                AuthSession(accessToken = "initial-token"),
            ),
        )
        val repository = BlockingNotificationRepository()
        val client = HttpClient(MockEngine { error("HTTP client is not used in this test") })
        val runtime = UserNotificationRuntime(
            client = client,
            streamClient = client,
            sessionService = UserNotificationSessionService(repository, FakeUserRepository()),
        )

        runtime.enterForeground()
        runtime.start(backgroundScope)
        runCurrent()
        assertEquals(1, repository.streamCallCount)

        SessionManager.update(
            AuthSession(
                accessToken = "email-token",
                userEmail = "same-user@example.com",
            ),
        )
        runCurrent()

        SessionManager.update(
            AuthSession(
                accessToken = "uuid-token",
                userUuid = "same-user-uuid",
                userEmail = "same-user@example.com",
            ),
        )
        runCurrent()

        assertEquals(1, repository.streamCallCount)
        assertEquals(0, repository.streamCancellationCount)

        runtime.stop()
    }

    @Test
    fun accountSwitchReplacesTheExistingStream() = runTest {
        SessionManager.initialize(
            InMemoryAuthSessionStore(
                AuthSession(
                    accessToken = "user-1-token",
                    userUuid = "user-1",
                ),
            ),
        )
        val repository = BlockingNotificationRepository()
        val client = HttpClient(MockEngine { error("HTTP client is not used in this test") })
        val runtime = UserNotificationRuntime(
            client = client,
            streamClient = client,
            sessionService = UserNotificationSessionService(repository, FakeUserRepository()),
        )

        runtime.enterForeground()
        runtime.start(backgroundScope)
        runCurrent()
        assertEquals(listOf("user-1-token"), repository.streamTokens)

        SessionManager.clear()
        runCurrent()
        assertEquals(1, repository.streamCancellationCount)

        SessionManager.update(
            AuthSession(
                accessToken = "user-2-token",
                userUuid = "user-2",
            ),
        )
        runCurrent()

        assertEquals(listOf("user-1-token", "user-2-token"), repository.streamTokens)
        assertEquals(1, repository.streamCancellationCount)

        runtime.stop()
        runCurrent()
        assertEquals(2, repository.streamCancellationCount)
    }

    private class RecordingNotificationRepository : UserNotificationRepository {
        var streamCallCount = 0

        override suspend fun list(
            query: UserNotificationQuery,
            token: String,
        ): ApiResult<UserNotificationPage> = ApiResult.Success(
            UserNotificationPage(
                items = emptyList(),
                total = 0,
                page = query.page,
                pageSize = query.pageSize,
            ),
        )

        override suspend fun markRead(id: String, token: String): ApiResult<Unit> =
            ApiResult.Success(Unit)

        override suspend fun markUnread(id: String, token: String): ApiResult<Unit> =
            ApiResult.Success(Unit)

        override suspend fun delete(id: String, token: String): ApiResult<Unit> =
            ApiResult.Success(Unit)

        override suspend fun batchMarkRead(ids: List<String>, token: String): ApiResult<Unit> =
            ApiResult.Success(Unit)

        override suspend fun stream(
            token: String,
            onMessage: suspend (AccountNotification) -> Unit,
        ): ApiResult<Unit> {
            streamCallCount += 1
            return ApiResult.Failure("disconnected")
        }
    }

    private class BlockingNotificationRepository : UserNotificationRepository {
        var streamCallCount = 0
        var streamCancellationCount = 0
        val streamTokens = mutableListOf<String>()

        override suspend fun list(
            query: UserNotificationQuery,
            token: String,
        ): ApiResult<UserNotificationPage> = ApiResult.Success(
            UserNotificationPage(
                items = emptyList(),
                total = 0,
                page = query.page,
                pageSize = query.pageSize,
            ),
        )

        override suspend fun markRead(id: String, token: String): ApiResult<Unit> =
            ApiResult.Success(Unit)

        override suspend fun markUnread(id: String, token: String): ApiResult<Unit> =
            ApiResult.Success(Unit)

        override suspend fun delete(id: String, token: String): ApiResult<Unit> =
            ApiResult.Success(Unit)

        override suspend fun batchMarkRead(ids: List<String>, token: String): ApiResult<Unit> =
            ApiResult.Success(Unit)

        override suspend fun stream(
            token: String,
            onMessage: suspend (AccountNotification) -> Unit,
        ): ApiResult<Unit> {
            streamCallCount += 1
            streamTokens += token
            try {
                awaitCancellation()
            } finally {
                streamCancellationCount += 1
            }
        }
    }
}
