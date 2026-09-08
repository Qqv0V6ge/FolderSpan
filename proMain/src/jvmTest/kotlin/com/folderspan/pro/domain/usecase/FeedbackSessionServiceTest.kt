package com.folderspan.pro.domain.usecase

import com.folderspan.pro.core.common.ApiResult
import com.folderspan.pro.core.datastore.AuthSession
import com.folderspan.pro.domain.model.FeedbackDraft
import com.folderspan.pro.domain.model.FeedbackListQuery
import com.folderspan.pro.testing.FakeFeedbackRepository
import com.folderspan.pro.testing.FakeUserRepository
import com.folderspan.pro.testing.feedbackSummary
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FeedbackSessionServiceTest {
    private val draft = FeedbackDraft(content = "details", appVersion = "1.0.0", platform = "linux")

    @Test
    fun signedOutSubmissionIsAnonymous() = runTest {
        val repository = FakeFeedbackRepository()
        val service = FeedbackSessionService(repository, FakeUserRepository(), sessionProvider = { null })

        assertIs<ApiResult.Success<String>>(service.submit(draft, null) {}).data

        assertEquals(listOf<String?>(null), repository.submittedTokens)
    }

    @Test
    fun signedInSubmissionRefreshesAndNeverFallsBackToAnonymous() = runTest {
        val repository = FakeFeedbackRepository()
        val userRepository = FakeUserRepository {
            ApiResult.Success(buildJsonObject { put("token", "fresh-token") })
        }
        val service = FeedbackSessionService(repository, userRepository, sessionProvider = { null })
        val expired = AuthSession(
            accessToken = "expired-token",
            refreshToken = "refresh-value",
            expiresAtEpochSeconds = 0,
        )

        service.submit(draft, expired) {}

        assertEquals(listOf<String?>("fresh-token"), repository.submittedTokens)
    }

    @Test
    fun failedRefreshStopsSignedInSubmissionWithoutAnonymousRetryOrSecretLeak() = runTest {
        val repository = FakeFeedbackRepository()
        val service = FeedbackSessionService(
            repository,
            FakeUserRepository { ApiResult.Failure("Please sign in again") },
            sessionProvider = { null },
        )
        var unauthorized = false
        val secret = "secret-refresh-value"

        val result = service.submit(
            draft,
            AuthSession("expired", refreshToken = secret, expiresAtEpochSeconds = 0),
        ) { unauthorized = true }

        val failure = assertIs<ApiResult.Failure>(result)
        assertTrue(unauthorized)
        assertTrue(repository.submittedTokens.isEmpty())
        assertTrue(secret !in failure.message)
    }

    @Test
    fun protectedRequestRetriesOnceAfterUnauthorized() = runTest {
        val repository = FakeFeedbackRepository()
        val tokens = mutableListOf<String>()
        repository.listHandler = { _, token ->
            tokens += token
            if (token == "old-token") ApiResult.Failure("expired", statusCode = 401)
            else ApiResult.Success(com.folderspan.pro.domain.model.FeedbackPage(listOf(feedbackSummary("one")), 1))
        }
        val service = FeedbackSessionService(
            repository,
            FakeUserRepository { ApiResult.Success(buildJsonObject { put("token", "new-token") }) },
            sessionProvider = { null },
        )

        val result = service.list(
            FeedbackListQuery(),
            AuthSession("old-token", refreshToken = "refresh", expiresAtEpochSeconds = Long.MAX_VALUE),
        ) {}

        assertIs<ApiResult.Success<*>>(result)
        assertEquals(listOf("old-token", "new-token"), tokens)
    }
}
