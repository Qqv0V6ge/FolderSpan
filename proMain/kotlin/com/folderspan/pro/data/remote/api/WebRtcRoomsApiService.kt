package com.folderspan.pro.data.remote.api

import com.folderspan.pro.core.common.JsonResult
import com.folderspan.pro.core.network.GatewayConfig
import com.folderspan.pro.core.network.cache.ApiResponseCacheStore
import com.folderspan.pro.core.network.cache.FileApiResponseCacheStore
import com.folderspan.pro.data.remote.dto.WebRtcRoomWriteRequest
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode

class WebRtcRoomsApiService(
    client: HttpClient,
    config: GatewayConfig = GatewayConfig(),
    cacheStore: ApiResponseCacheStore = FileApiResponseCacheStore(),
) : BaseApiService(client, config, cacheStore) {

    suspend fun listRooms(
        page: Int,
        pageSize: Int,
        token: String,
    ): JsonResult {
        val url = routes.webrtc("/rooms")
        return jsonCall {
            client.get(url) {
                auth(token)
                parameter("page", page.coerceAtLeast(1))
                parameter("pageSize", pageSize.coerceIn(1, 100))
            }
        }
    }

    suspend fun getRoom(
        roomId: String,
        token: String,
    ): JsonResult = jsonCall {
        client.get(routes.webrtc("/rooms/$roomId")) {
            auth(token)
        }
    }

    suspend fun createRoom(
        request: WebRtcRoomWriteRequest,
        token: String,
    ): JsonResult = jsonCall {
        client.post(routes.webrtc("/rooms")) {
            auth(token)
            setBody(request)
        }
    }

    suspend fun updateRoom(
        roomId: String,
        request: WebRtcRoomWriteRequest,
        token: String,
    ): JsonResult = jsonCall {
        client.patch(routes.webrtc("/rooms/$roomId")) {
            auth(token)
            setBody(request)
        }
    }

    suspend fun deleteRoom(
        roomId: String,
        token: String,
    ): JsonResult = jsonCall {
        client.delete(routes.webrtc("/rooms/$roomId")) {
            auth(token)
        }
    }

    companion object {
        val EmptyListStatus: HttpStatusCode = HttpStatusCode.NotFound
    }
}
