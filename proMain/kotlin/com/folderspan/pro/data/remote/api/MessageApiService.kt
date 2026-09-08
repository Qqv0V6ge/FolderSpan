package com.folderspan.pro.data.remote.api

import com.folderspan.pro.core.common.ApiResult
import com.folderspan.pro.core.network.GatewayConfig
import com.folderspan.pro.core.network.defaultJson
import com.folderspan.pro.data.remote.dto.MessageEnvelopeDto
import com.folderspan.pro.data.remote.dto.MessageItemDto
import com.folderspan.pro.data.remote.dto.MessageListDataDto
import com.folderspan.pro.domain.model.ANNOUNCEMENT_LIST_TYPE
import com.folderspan.pro.domain.model.AnnouncementQuery
import com.folderspan.pro.domain.model.AppUpdateChannel
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode

class MessageApiService(
    client: HttpClient,
    config: GatewayConfig = GatewayConfig(),
) : BaseApiService(client, config) {
    suspend fun listMessages(
        query: AnnouncementQuery,
    ): ApiResult<MessageEnvelopeDto<MessageListDataDto>> = safeCall {
        val page = query.page.coerceAtLeast(1)
        val pageSize = query.pageSize.coerceIn(1, 100)
        val response = client.get(routes.message()) {
            parameter("type", query.type.ifBlank { ANNOUNCEMENT_LIST_TYPE })
            parameter("platform", query.platform)
            parameter("page", page)
            parameter("pageSize", pageSize)
        }
        if (response.status == HttpStatusCode.NotFound) {
            return@safeCall MessageEnvelopeDto(
                data = MessageListDataDto(page = page, pageSize = pageSize),
            )
        }
        if (response.status.value !in 200..299) {
            throw ApiHttpStatusException(response.status.value, response.bodyAsText())
        }
        defaultJson.decodeFromString(response.bodyAsText())
    }

    suspend fun latestAppUpdate(
        platform: String,
        channel: String = AppUpdateChannel.Release.token,
    ): ApiResult<MessageEnvelopeDto<MessageItemDto>> = safeCall {
        val response = client.get(routes.updates("/latest")) {
            parameter("platform", platform)
            parameter("channel", AppUpdateChannel.fromToken(channel).token)
        }
        if (response.status == HttpStatusCode.NotFound) {
            return@safeCall MessageEnvelopeDto()
        }
        if (response.status.value !in 200..299) {
            throw ApiHttpStatusException(response.status.value, response.bodyAsText())
        }
        defaultJson.decodeFromString(response.bodyAsText())
    }

    suspend fun listAppUpdates(
        platform: String,
        channel: String = AppUpdateChannel.Release.token,
        page: Int = 1,
        pageSize: Int = 20,
    ): ApiResult<MessageEnvelopeDto<MessageListDataDto>> = safeCall {
        val normalizedPage = page.coerceAtLeast(1)
        val normalizedPageSize = pageSize.coerceIn(1, 100)
        val response = client.get(routes.updates()) {
            parameter("platform", platform)
            parameter("channel", AppUpdateChannel.fromToken(channel).token)
            parameter("page", normalizedPage)
            parameter("pageSize", normalizedPageSize)
        }
        if (response.status == HttpStatusCode.NotFound) {
            return@safeCall MessageEnvelopeDto(
                data = MessageListDataDto(page = normalizedPage, pageSize = normalizedPageSize),
            )
        }
        if (response.status.value !in 200..299) {
            throw ApiHttpStatusException(response.status.value, response.bodyAsText())
        }
        defaultJson.decodeFromString(response.bodyAsText())
    }
}
