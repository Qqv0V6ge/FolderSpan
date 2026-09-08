package com.folderspan.pro.data.remote.api

import strings.AppStrings

import com.folderspan.pro.core.common.*
import com.folderspan.pro.core.network.GatewayConfig
import com.folderspan.pro.core.network.RouteBuilder
import com.folderspan.pro.core.network.cache.ApiCacheKey
import com.folderspan.pro.core.network.cache.ApiCachePolicy
import com.folderspan.pro.core.network.cache.ApiResponseCacheStore
import com.folderspan.pro.core.network.cache.FileApiResponseCacheStore
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.io.IOException
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonElement.Companion.serializer

abstract class BaseApiService(
    protected val client: HttpClient,
    protected val config: GatewayConfig,
    private val cacheStore: ApiResponseCacheStore = FileApiResponseCacheStore(),
) {

    protected val routes = RouteBuilder(config)

    protected suspend fun <T> safeCall(block: suspend () -> T): ApiResult<T> =
        try {
            ApiResult.Success(block())
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            ApiResult.Failure(
                message = resolveFailureMessage(t),
                cause = t,
                statusCode = t.responseStatusCode(),
                apiCode = (t as? ApiBusinessException)?.apiCode,
            )
        }

    protected suspend fun <T> responseCall(
        request: suspend () -> HttpResponse,
        transform: suspend (HttpResponse) -> T,
    ): ApiResult<T> =
        try {
            val response = request()
            if (response.status.value !in 200..299) {
                throw ApiHttpStatusException(
                    statusCode = response.status.value,
                    message = parseApiMessage(response.bodyAsText())
                        ?: AppStrings.ui_operation_failed_please_try_again_later,
                )
            }
            ApiResult.Success(transform(response))
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            ApiResult.Failure(
                message = resolveFailureMessage(t),
                cause = t,
                statusCode = t.responseStatusCode(),
                apiCode = (t as? ApiBusinessException)?.apiCode,
            )
        }

    protected suspend fun jsonCall(
        cachePolicy: ApiCachePolicy? = null,
        cacheKey: ApiCacheKey? = null,
        block: suspend () -> HttpResponse,
    ): JsonResult {
        if (cachePolicy == null || cacheKey == null) {
            return safeCall { parseJsonResponse(block()) }
        }

        return try {
            val payload = parseJsonResponse(block())
            runCatching { cacheStore.write(cacheKey, payload, cachePolicy) }
            ApiResult.Success(payload)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            val cachedPayload = if (cachePolicy.allowFallback && t.allowsCacheFallback()) {
                runCatching { cacheStore.read(cacheKey, cachePolicy.namespace) }
                    .getOrNull()
                    ?.takeIf { it.isSuccessfulApiPayload() }
            } else {
                null
            }
            if (cachedPayload != null) {
                ApiResult.Success(cachedPayload)
            } else {
                ApiResult.Failure(
                    message = resolveFailureMessage(t),
                    cause = t,
                    statusCode = t.responseStatusCode(),
                    apiCode = (t as? ApiBusinessException)?.apiCode,
                )
            }
        }
    }

    protected suspend fun cachedJsonPayload(
        cachePolicy: ApiCachePolicy,
        cacheKey: ApiCacheKey,
    ): JsonElement? =
        runCatching { cacheStore.read(cacheKey, cachePolicy.namespace) }
            .getOrNull()
            ?.takeIf { it.isSuccessfulApiPayload() }

    protected fun HttpRequestBuilder.auth(token: String) {
        val normalizedToken = normalizeAccessToken(token) ?: token.trim()
        header(HttpHeaders.Authorization, "Bearer $normalizedToken")
    }

    protected fun cacheKey(
        method: String,
        url: String,
        queryParameters: List<Pair<String, String>> = emptyList(),
        body: ByteArray? = null,
        userScope: String? = null,
    ): ApiCacheKey =
        ApiCacheKey.from(
            method = method,
            url = url,
            queryParameters = queryParameters,
            body = body,
            userScope = userScope,
        )

    protected fun <T> cacheBody(
        serializer: SerializationStrategy<T>,
        value: T,
        json: Json = com.folderspan.pro.core.network.defaultJson,
    ): ByteArray =
        json.encodeToString(serializer, value).encodeToByteArray()

    protected fun cacheBody(value: JsonElement): ByteArray =
        com.folderspan.pro.core.network.defaultJson
            .encodeToString(serializer(), value)
            .encodeToByteArray()
}

private suspend fun parseJsonResponse(response: HttpResponse): JsonElement {
    if (response.status.value !in 200..299) {
        throw ApiHttpStatusException(
            statusCode = response.status.value,
            message = parseApiMessage(response.bodyAsText())
                ?: AppStrings.ui_operation_failed_please_try_again_later,
        )
    }
    val payload = response.body<JsonElement>()
    val code = payload.apiCode()
    if (code != null && code != 0) {
        throw ApiBusinessException(
            apiCode = code,
            message = payload.apiMessage() ?: AppStrings.ui_operation_failed_please_try_again_later,
        )
    }
    return payload
}

private suspend fun resolveFailureMessage(throwable: Throwable): String =
    when (throwable) {
        is ApiBusinessException -> throwable.message ?: AppStrings.ui_operation_failed_please_try_again_later
        is ApiHttpStatusException -> throwable.message ?: AppStrings.ui_operation_failed_please_try_again_later
        is ResponseException -> {
            parseApiMessage(throwable.response.bodyAsText())
                ?: throwable.message
                ?: AppStrings.ui_operation_failed_please_try_again_later
        }

        is IOException -> AppStrings.ui_network_connection_failed_please_check_network_try_again
        else -> throwable.message ?: AppStrings.ui_operation_failed_please_try_again_later
    }

internal class ApiBusinessException(
    val apiCode: Int,
    message: String,
) : IllegalStateException(message)

internal class ApiHttpStatusException(
    val statusCode: Int,
    message: String,
) : IllegalStateException(message)

private fun Throwable.allowsCacheFallback(): Boolean {
    if (this is ApiBusinessException) return false
    val status = when (this) {
        is ApiHttpStatusException -> HttpStatusCode.fromValue(statusCode)
        is ResponseException -> response.status
        else -> null
    }
    return status != HttpStatusCode.Unauthorized && status != HttpStatusCode.Forbidden
}

private fun Throwable.responseStatusCode(): Int? =
    when (this) {
        is ApiHttpStatusException -> statusCode
        is ResponseException -> response.status.value
        else -> null
    }

private fun JsonElement.isSuccessfulApiPayload(): Boolean {
    val code = apiCode()
    return code == null || code == 0
}
