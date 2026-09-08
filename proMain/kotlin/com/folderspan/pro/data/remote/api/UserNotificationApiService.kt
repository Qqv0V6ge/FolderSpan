package com.folderspan.pro.data.remote.api

import com.folderspan.pro.core.common.ApiResult
import com.folderspan.pro.core.network.GatewayConfig
import com.folderspan.pro.core.network.defaultJson
import com.folderspan.pro.data.remote.dto.UserNotificationBatchReadRequest
import com.folderspan.pro.data.remote.dto.UserNotificationDto
import com.folderspan.pro.data.remote.dto.UserNotificationEnvelopeDto
import com.folderspan.pro.data.remote.dto.UserNotificationListDataDto
import com.folderspan.pro.domain.model.UserNotificationQuery
import io.ktor.client.HttpClient
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode

class UserNotificationApiService(
    client: HttpClient,
    config: GatewayConfig = GatewayConfig(),
    private val streamClient: HttpClient = client,
) : BaseApiService(client, config) {
    suspend fun list(
        query: UserNotificationQuery,
        token: String,
    ): ApiResult<UserNotificationEnvelopeDto<UserNotificationListDataDto>> = safeCall {
        val response = client.get(routes.message("/user/notifications")) {
            auth(token)
            query.status?.let { parameter("status", it.wireValue) }
            parameter("page", query.page.coerceAtLeast(1))
            parameter("pageSize", query.pageSize.coerceIn(1, 100))
        }
        if (response.status == HttpStatusCode.NotFound) {
            return@safeCall UserNotificationEnvelopeDto(
                data = UserNotificationListDataDto(
                    page = query.page.coerceAtLeast(1),
                    pageSize = query.pageSize.coerceIn(1, 100),
                ),
            )
        }
        response.requireSuccess()
        defaultJson.decodeFromString(response.bodyAsText())
    }

    suspend fun markRead(id: String, token: String): ApiResult<Unit> = operationCall {
        client.patch(routes.message("/user/notifications/${id.safePathSegment()}/read")) {
            auth(token)
        }
    }

    suspend fun markUnread(id: String, token: String): ApiResult<Unit> = operationCall {
        client.patch(routes.message("/user/notifications/${id.safePathSegment()}/unread")) {
            auth(token)
        }
    }

    suspend fun delete(id: String, token: String): ApiResult<Unit> = operationCall {
        client.delete(routes.message("/user/notifications/${id.safePathSegment()}")) {
            auth(token)
        }
    }

    suspend fun batchMarkRead(ids: List<String>, token: String): ApiResult<Unit> = operationCall {
        client.post(routes.message("/user/notifications/batch-read")) {
            auth(token)
            setBody(UserNotificationBatchReadRequest(ids))
        }
    }

    suspend fun stream(
        token: String,
        onMessage: suspend (UserNotificationDto) -> Unit,
    ): ApiResult<Unit> = safeCall {
        streamClient.sse(
            urlString = routes.message("/user/notifications/stream"),
            request = {
                auth(token)
                headers.useEventStreamAccept()
            },
        ) {
            incoming.collect { event ->
                decodeUserNotificationSseEvent(event.event, event.data)?.let { onMessage(it) }
            }
        }
    }

    private suspend fun operationCall(request: suspend () -> io.ktor.client.statement.HttpResponse): ApiResult<Unit> =
        when (val result = jsonCall(block = request)) {
            is ApiResult.Success -> ApiResult.Success(Unit)
            is ApiResult.Failure -> result
        }
}

internal fun decodeUserNotificationSseEvent(event: String?, data: String?): UserNotificationDto? {
    if (event != "message") return null
    val payload = data?.takeIf(String::isNotBlank) ?: return null
    return runCatching { defaultJson.decodeFromString<UserNotificationDto>(payload) }.getOrNull()
}

private suspend fun io.ktor.client.statement.HttpResponse.requireSuccess() {
    if (status.value !in 200..299) {
        throw ApiHttpStatusException(status.value, bodyAsText())
    }
}

private fun String.safePathSegment(): String =
    trim().also { require(it.isNotEmpty()) { "Empty notification identifier" } }
        .replace("/", "")
        .replace("\\", "")

internal fun io.ktor.http.HeadersBuilder.useEventStreamAccept() {
    remove(HttpHeaders.Accept)
    append(HttpHeaders.Accept, ContentType.Text.EventStream.toString())
}
