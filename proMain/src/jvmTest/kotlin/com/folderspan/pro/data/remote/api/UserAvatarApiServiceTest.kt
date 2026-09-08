package com.folderspan.pro.data.remote.api

import com.folderspan.pro.core.common.ApiResult
import com.folderspan.pro.core.network.GatewayConfig
import com.folderspan.pro.core.network.ReplayableRequestContent
import com.folderspan.pro.core.network.installCommonConfig
import com.folderspan.pro.data.mapper.toProfileUpdateUserViewData
import com.folderspan.pro.data.mapper.toUserProfileViewData
import com.folderspan.pro.data.repository.DefaultUserRepository
import com.folderspan.pro.domain.model.ProfileAvatarUpload
import com.folderspan.pro.domain.model.UpdateProfileCommand
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class UserAvatarApiServiceTest {
    @Test
    fun uploadUsesAuthenticatedSingleFileReplayableMultipartAndMapsSuccess() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val repository = DefaultUserRepository(
            UserApiService(successClient(requests), testConfig()),
        )
        val bytes = byteArrayOf(1, 2, 3, 4)

        val result = repository.uploadAvatar(
            upload = ProfileAvatarUpload(bytes, "image/jpeg"),
            token = "avatar-token",
        )

        assertIs<ApiResult.Success<*>>(result)
        val request = requests.single()
        assertEquals(HttpMethod.Put, request.method)
        assertEquals("/api/v1/user/profile/avatar", request.url.encodedPath)
        assertEquals("Bearer avatar-token", request.headers[HttpHeaders.Authorization])
        assertTrue(request.body.contentType?.match(ContentType.MultiPart.FormData) == true)
        val replayable = assertIs<ReplayableRequestContent>(request.body)
        val text = replayable.replayableBodyBytes().decodeToString()
        assertContains(text, "name=\"file\"; filename=\"avatar.jpg\"")
        assertContains(text, "Content-Type: image/jpeg")
        assertTrue(replayable.replayableBodyBytes().containsSubsequence(bytes))
    }

    @Test
    fun profileUpdateWithAvatarUsesSingleAuthenticatedMultipartRequest() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val repository = DefaultUserRepository(
            UserApiService(successClient(requests), testConfig()),
        )
        val avatarBytes = byteArrayOf(1, 2, 3, 4)

        val result = repository.updateProfile(
            command = UpdateProfileCommand(
                name = "Alice New",
                signature = "New signature",
                avatar = ProfileAvatarUpload(avatarBytes, "image/png"),
            ),
            token = "profile-token",
        )

        assertIs<ApiResult.Success<*>>(result)
        val request = requests.single()
        assertEquals(HttpMethod.Put, request.method)
        assertEquals("/api/v1/user/profile", request.url.encodedPath)
        assertEquals("Bearer profile-token", request.headers[HttpHeaders.Authorization])
        assertTrue(request.body.contentType?.match(ContentType.MultiPart.FormData) == true)
        val body = assertIs<ReplayableRequestContent>(request.body).replayableBodyBytes()
        val text = body.decodeToString()
        assertContains(text, "name=\"name\"")
        assertContains(text, "Alice New")
        assertContains(text, "name=\"signature\"")
        assertContains(text, "New signature")
        assertContains(text, "name=\"file\"; filename=\"avatar.png\"")
        assertContains(text, "Content-Type: image/png")
        assertTrue(body.containsSubsequence(avatarBytes))
    }

    @Test
    fun repeatedRemovalRemainsSuccessfulAndAuthenticated() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val repository = DefaultUserRepository(
            UserApiService(successClient(requests), testConfig()),
        )

        assertIs<ApiResult.Success<*>>(repository.removeAvatar("remove-token"))
        assertIs<ApiResult.Success<*>>(repository.removeAvatar("remove-token"))

        assertEquals(2, requests.size)
        requests.forEach { request ->
            assertEquals(HttpMethod.Delete, request.method)
            assertEquals("/api/v1/user/profile/avatar", request.url.encodedPath)
            assertEquals("Bearer remove-token", request.headers[HttpHeaders.Authorization])
        }
    }

    @Test
    fun withdrawProfileReviewUsesAuthenticatedDeleteEndpoint() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val repository = DefaultUserRepository(
            UserApiService(successClient(requests), testConfig()),
        )

        assertIs<ApiResult.Success<*>>(repository.withdrawProfileReview("review-token"))

        val request = requests.single()
        assertEquals(HttpMethod.Delete, request.method)
        assertEquals("/api/v1/user/profile/review", request.url.encodedPath)
        assertEquals("Bearer review-token", request.headers[HttpHeaders.Authorization])
    }

    @Test
    fun unauthenticatedAndRejectedResponsesUseSafeMessages() = runTest {
        suspend fun failure(status: HttpStatusCode, rawMessage: String): ApiResult.Failure {
            val client = HttpClient(
                MockEngine {
                    respond(
                        content = """{"code":${status.value}00,"msg":"$rawMessage"}""",
                        status = status,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                },
            ) { installCommonConfig(useProxy = false) }
            val repository = DefaultUserRepository(UserApiService(client, testConfig()))
            return assertIs(
                repository.uploadAvatar(
                    ProfileAvatarUpload(byteArrayOf(1), "image/jpeg"),
                    "secret-token",
                ),
            )
        }

        val unauthorized = failure(HttpStatusCode.Unauthorized, "expired secret-token /profile/avatar")
        assertEquals(HttpStatusCode.Unauthorized.value, unauthorized.statusCode)
        assertSafe(unauthorized.message)

        val rejected = failure(HttpStatusCode.PayloadTooLarge, "object user-123 rejected at /profile/avatar")
        assertEquals(HttpStatusCode.PayloadTooLarge.value, rejected.statusCode)
        assertSafe(rejected.message)
    }

    private fun assertSafe(message: String) {
        assertFalse(message.contains("secret-token"))
        assertFalse(message.contains("/profile/avatar"))
        assertFalse(message.contains("user-123"))
        assertFalse(message.contains("expired"))
    }

    private fun successClient(requests: MutableList<HttpRequestData>): HttpClient = HttpClient(
        MockEngine { request ->
            requests += request
            respond(
                content = """{"code":0,"data":{"uuid":"user","name":"User","avatar":"https://cdn.test/avatar.jpg"}}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        },
    ) { installCommonConfig(useProxy = false) }

    private fun testConfig() = GatewayConfig(baseUrl = "https://example.test")
}

class ProfileJsonMappersTest {
    @Test
    fun profileResponseMapsEditableFlag() {
        val profile = assertNotNull(
            Json.parseToJsonElement(
                """
                    {
                      "uuid": "user-id",
                      "name": "Alice",
                      "email": "alice@example.test",
                      "avatar": "https://cdn.test/avatar.png",
                      "signature": "Hello",
                      "status": 1,
                      "profileEditable": false
                    }
                """.trimIndent(),
            ).toUserProfileViewData(),
        )

        assertFalse(profile.profileEditable)
    }

    @Test
    fun profileUpdateResponseMapsPublishedUserInsideWrapper() {
        val profile = assertNotNull(
            Json.parseToJsonElement(
                """
                    {
                      "reviewUuid": "review-id",
                      "status": "pending",
                      "submittedAt": 123,
                      "user": {
                        "uuid": "user-id",
                        "name": "Published name",
                        "email": "alice@example.test",
                        "avatar": "https://cdn.test/published.png",
                        "signature": "Published signature",
                        "status": 1,
                        "profileEditable": false
                      }
                    }
                """.trimIndent(),
            ).toProfileUpdateUserViewData(),
        )

        assertEquals("Published name", profile.name)
        assertEquals("https://cdn.test/published.png", profile.avatar)
        assertFalse(profile.profileEditable)
    }

    @Test
    fun legacyCachedProfileWithoutEditableFlagRemainsEditable() {
        val profile = assertNotNull(
            Json.parseToJsonElement(
                """
                    {
                      "uuid": "user-id",
                      "name": "Alice",
                      "email": "alice@example.test"
                    }
                """.trimIndent(),
            ).toUserProfileViewData(),
        )

        assertTrue(profile.profileEditable)
    }
}

private fun ByteArray.containsSubsequence(expected: ByteArray): Boolean =
    indices.any { start ->
        start + expected.size <= size && expected.indices.all { offset ->
            this[start + offset] == expected[offset]
        }
    }
