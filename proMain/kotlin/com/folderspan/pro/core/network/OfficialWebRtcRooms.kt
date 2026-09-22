package com.folderspan.pro.core.network

import com.folderspan.data.main.webrtc.OfficialWebRtcRoomPage
import com.folderspan.data.main.webrtc.WebRtcOfficialRooms
import com.folderspan.data.main.webrtc.WebRtcOfficialRoomsClient
import com.folderspan.data.main.webrtc.WebRtcRoomInput
import com.folderspan.data.main.webrtc.WebRtcRoomProfile
import com.folderspan.pro.core.common.ApiResult
import com.folderspan.pro.core.common.JsonResult
import com.folderspan.pro.core.common.normalizeAccessToken
import com.folderspan.pro.core.datastore.SessionManager
import com.folderspan.pro.data.mapper.toOfficialWebRtcRoomPage
import com.folderspan.pro.data.mapper.toOfficialWebRtcRoomProfile
import com.folderspan.pro.data.mapper.toWriteRequest
import com.folderspan.pro.data.remote.api.WebRtcRoomsApiService
import strings.AppStrings

fun installProWebRtcOfficialRoomsClient() {
    WebRtcOfficialRooms.client = ProWebRtcOfficialRoomsClient(
        api = WebRtcRoomsApiService(httpClient()),
        tokenProvider = { SessionManager.currentToken() },
    )
}

internal class ProWebRtcOfficialRoomsClient(
    private val api: WebRtcRoomsApiService,
    private val tokenProvider: () -> String?,
) : WebRtcOfficialRoomsClient {
    override suspend fun listRooms(page: Int, pageSize: Int): Result<OfficialWebRtcRoomPage> {
        val token = requireToken().getOrElse { return Result.failure(it) }
        return when (val result = api.listRooms(page = page, pageSize = pageSize, token = token)) {
            is ApiResult.Success -> Result.success(result.data.toOfficialWebRtcRoomPage(page, pageSize))
            is ApiResult.Failure -> if (result.statusCode == WebRtcRoomsApiService.EmptyListStatus.value) {
                Result.success(
                    OfficialWebRtcRoomPage(
                        rooms = emptyList(),
                        total = 0,
                        page = page,
                        pageSize = pageSize,
                    ),
                )
            } else {
                result.toFailure()
            }
        }
    }

    override suspend fun getRoom(roomId: String): Result<WebRtcRoomProfile> {
        val token = requireToken().getOrElse { return Result.failure(it) }
        return api.getRoom(roomId, token).mapProfile()
    }

    override suspend fun createRoom(input: WebRtcRoomInput): Result<WebRtcRoomProfile> {
        val token = requireToken().getOrElse { return Result.failure(it) }
        return api.createRoom(input.toWriteRequest(), token).mapProfile()
    }

    override suspend fun updateRoom(roomId: String, input: WebRtcRoomInput): Result<WebRtcRoomProfile> {
        val token = requireToken().getOrElse { return Result.failure(it) }
        return api.updateRoom(roomId, input.toWriteRequest(), token).mapProfile()
    }

    override suspend fun deleteRoom(roomId: String): Result<Unit> {
        val token = requireToken().getOrElse { return Result.failure(it) }
        return when (val result = api.deleteRoom(roomId, token)) {
            is ApiResult.Success -> Result.success(Unit)
            is ApiResult.Failure -> result.toFailure()
        }
    }

    private fun requireToken(): Result<String> {
        val token = normalizeAccessToken(tokenProvider()) ?: tokenProvider()?.trim().orEmpty()
        return if (token.isBlank()) {
            Result.failure(IllegalStateException(AppStrings.ui_log_in_before_connecting_official_webrtc_room))
        } else {
            Result.success(token)
        }
    }

    private fun JsonResult.mapProfile(): Result<WebRtcRoomProfile> = when (this) {
        is ApiResult.Success -> {
            val profile = data.toOfficialWebRtcRoomProfile()
            if (profile == null) {
                Result.failure(IllegalStateException(AppStrings.ui_operation_failed_please_try_again_later))
            } else {
                Result.success(profile)
            }
        }
        is ApiResult.Failure -> toFailure()
    }

    private fun <T> ApiResult.Failure.toFailure(): Result<T> =
        Result.failure(cause ?: IllegalStateException(message))
}
