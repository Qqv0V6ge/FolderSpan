package com.folderspan.pro.data.repository

import com.folderspan.pro.core.common.ApiResult
import com.folderspan.pro.core.network.GatewayConfig
import com.folderspan.pro.core.network.cache.ApiCacheKey
import com.folderspan.pro.core.network.cache.ApiCachePolicy
import com.folderspan.pro.core.network.cache.ApiResponseCacheStore
import com.folderspan.pro.data.remote.api.UserApiService
import com.folderspan.pro.domain.model.UpdateProfileCommand
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.days

class AuthRepositoryRequestTest {
    @Test
    fun blankNicknameIsOmittedFromRegistrationRequest() = runTest {
        var requestBody = ""
        val client = HttpClient(
            MockEngine { request ->
                requestBody = request.body.registrationBodyText()
                respond(
                    content = """{"code":0,"data":{}}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            },
        ) {
            install(ContentNegotiation) {
                json(com.folderspan.pro.core.network.defaultJson)
            }
            defaultRequest {
                headers.append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            }
        }
        val repository = DefaultAuthRepository(
            UserApiService(
                client = client,
                config = GatewayConfig(baseUrl = "https://example.test"),
            ),
        )

        repository.register(
            name = "   ",
            email = "user@example.com",
            password = "password",
        )

        val body = com.folderspan.pro.core.network.defaultJson.parseToJsonElement(requestBody).jsonObject
        assertFalse("name" in body)
        assertEquals("user@example.com", body["email"]?.jsonPrimitive?.content)
    }

    @Test
    fun profileUpdateJsonOmitsUnchangedOptionalFields() = runTest {
        var requestBody = ""
        val client = HttpClient(
            MockEngine { request ->
                requestBody = request.body.registrationBodyText()
                respond(
                    content = """{"code":0,"data":{"user":{"uuid":"user","name":"Alice","email":"user@example.test","profileEditable":false}}}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            },
        ) {
            install(ContentNegotiation) {
                json(com.folderspan.pro.core.network.defaultJson)
            }
            defaultRequest {
                headers.append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            }
        }
        val repository = DefaultUserRepository(
            UserApiService(
                client = client,
                config = GatewayConfig(baseUrl = "https://example.test"),
            ),
        )

        repository.updateProfile(
            command = UpdateProfileCommand(name = "Alice New"),
            token = "profile-token",
        )

        val body = com.folderspan.pro.core.network.defaultJson.parseToJsonElement(requestBody).jsonObject
        assertEquals("Alice New", body["name"]?.jsonPrimitive?.content)
        assertFalse("signature" in body)
        assertFalse("locale" in body)
    }
}

class UserRepositoryCachePolicyTest {
    @Test
    fun meCachesProfileForThirtyDays() = runTest {
        val cacheStore = RecordingCacheStore()
        val repository = DefaultUserRepository(
            UserApiService(
                client = clientResponding("""{"code":0,"data":{"name":"Alice"}}"""),
                config = GatewayConfig(baseUrl = "https://example.test"),
                cacheStore = cacheStore,
            ),
        )

        val result = repository.me("token-value")

        assertIs<ApiResult.Success<JsonElement>>(result)
        val policy = assertNotNull(cacheStore.lastPolicy)
        assertEquals(30.days, policy.ttl)
    }

    @Test
    fun listDevicesCachesDevicesForThirtyDays() = runTest {
        val cacheStore = RecordingCacheStore()
        val repository = DefaultUserRepository(
            UserApiService(
                client = clientResponding("""{"code":0,"data":{"devices":[],"total":0}}"""),
                config = GatewayConfig(baseUrl = "https://example.test"),
                cacheStore = cacheStore,
            ),
        )

        val result = repository.listDevices("token-value")

        assertIs<ApiResult.Success<JsonElement>>(result)
        val policy = assertNotNull(cacheStore.lastPolicy)
        assertEquals(30.days, policy.ttl)
    }

    private class RecordingCacheStore : ApiResponseCacheStore {
        var lastPolicy: ApiCachePolicy? = null

        override suspend fun read(key: ApiCacheKey, namespace: String): JsonElement? = null

        override suspend fun write(
            key: ApiCacheKey,
            payload: JsonElement,
            policy: ApiCachePolicy,
        ) {
            lastPolicy = policy
        }

        override suspend fun remove(key: ApiCacheKey, namespace: String) = Unit
    }

    private fun clientResponding(body: String): HttpClient =
        HttpClient(
            MockEngine {
                respond(
                    content = body,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            },
        ) {
            install(ContentNegotiation) {
                json(com.folderspan.pro.core.network.defaultJson)
            }
        }
}

private fun OutgoingContent.registrationBodyText(): String =
    when (this) {
        is TextContent -> text
        is OutgoingContent.ByteArrayContent -> bytes().decodeToString()
        else -> toString()
    }
