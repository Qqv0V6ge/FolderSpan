package com.folderspan.data.main.webrtc

import com.folderspan.service.webrtc.models.WebRtcConfig
import com.folderspan.service.webrtc.models.WebRtcConnectionStatus
import com.folderspan.service.webrtc.models.buildWebRtcIceServers
import com.folderspan.service.webrtc.signaling.isValidRoomId
import strings.AppStrings

enum class WebRtcRoomSource {
    Other,
    Official;

    val label: String
        get() = when (this) {
            Other -> AppStrings.ui_others
            Official -> AppStrings.ui_official
        }
}

interface WebRtcOfficialGatewayProvider {
    fun websocketUrl(): String

    fun websocketHeaders(): Result<Map<String, String>>
}

object WebRtcOfficialGateway {
    var provider: WebRtcOfficialGatewayProvider = object : WebRtcOfficialGatewayProvider {
        override fun websocketUrl(): String = ""

        override fun websocketHeaders(): Result<Map<String, String>> =
            Result.failure(IllegalStateException(AppStrings.ui_the_webrtc_gateway_is_not_initialized))
    }

    fun websocketUrl(): String = provider.websocketUrl().trim()

    fun websocketHeaders(): Result<Map<String, String>> = provider.websocketHeaders()
}

data class WebRtcRoomInput(
    val name: String,
    val wssUrl: String,
    val roomId: String,
    val stunUrl: String,
    val turnUrl: String,
    val turnUsername: String,
    val turnPassword: String,
    val source: WebRtcRoomSource
) {
    fun normalized(): WebRtcRoomInput {
        return copy(
            name = name.trim(),
            wssUrl = wssUrl.trim(),
            roomId = roomId.trim(),
            stunUrl = stunUrl.trim(),
            turnUrl = turnUrl.trim(),
            turnUsername = turnUsername.trim(),
            turnPassword = turnPassword
        )
    }

    fun canSave(): Boolean {
        val normalized = normalized()
        if (normalized.source == WebRtcRoomSource.Official) {
            return normalized.name.isNotBlank() && normalized.name.length <= OfficialRoomNameMaxLength
        }
        return normalized.name.isNotBlank() &&
            normalized.wssUrl.isNotBlank() &&
            normalized.roomId.isNotBlank() &&
            isValidRoomId(normalized.roomId)
    }
}

data class WebRtcRoomProfile(
    val id: Long,
    val name: String,
    val wssUrl: String,
    val roomId: String,
    val stunUrl: String,
    val turnUrl: String,
    val turnUsername: String,
    val turnPassword: String,
    val source: WebRtcRoomSource,
    val pinned: Boolean,
    val sortOrder: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val maxDevices: Int = 0,
) {
    val catalogKey: String
        get() = when (source) {
            WebRtcRoomSource.Official -> "official:$roomId"
            WebRtcRoomSource.Other -> "other:$id"
        }

    val roomIdValid: Boolean
        get() = isValidRoomId(roomId)

    fun toInput(): WebRtcRoomInput {
        return WebRtcRoomInput(
            name = name,
            wssUrl = wssUrl,
            roomId = roomId,
            stunUrl = stunUrl,
            turnUrl = turnUrl,
            turnUsername = turnUsername,
            turnPassword = turnPassword,
            source = source
        )
    }
}

fun WebRtcRoomInput.toWebRtcConfig(): WebRtcConfig {
    val normalized = normalized()
    return WebRtcConfig(
        wssUrl = normalized.wssUrl,
        roomId = normalized.roomId,
        iceServers = buildWebRtcIceServers(
            stunUrl = normalized.stunUrl,
            turnUrl = normalized.turnUrl,
            turnUsername = normalized.turnUsername,
            turnPassword = normalized.turnPassword
        ),
        unreliableMode = false
    )
}

fun WebRtcRoomInput.toWebRtcConfigResult(): Result<WebRtcConfig> {
    val normalized = normalized()
    if (normalized.source != WebRtcRoomSource.Official) {
        return Result.success(normalized.toWebRtcConfig())
    }

    val officialUrl = WebRtcOfficialGateway.websocketUrl()
    if (officialUrl.isBlank()) {
        return Result.failure(IllegalStateException(AppStrings.ui_official_webrtc_gateway_address_unavailable))
    }

    return WebRtcOfficialGateway.websocketHeaders().map { headers ->
        normalized.copy(wssUrl = officialUrl).toWebRtcConfig().copy(headers = headers)
    }
}

fun WebRtcRoomProfile.toWebRtcConfig(): WebRtcConfig {
    return toInput().toWebRtcConfig()
}

fun WebRtcRoomProfile.toWebRtcConfigResult(): Result<WebRtcConfig> {
    return toInput().toWebRtcConfigResult()
}

fun WebRtcRoomProfile.matchesActiveConfig(config: WebRtcConfig?): Boolean {
    if (config == null) return false
    if (source == WebRtcRoomSource.Official) {
        return config.roomId == roomId
    }
    val roomConfig = toWebRtcConfig()
    return roomConfig.wssUrl == config.wssUrl &&
        roomConfig.roomId == config.roomId &&
        roomConfig.iceServers == config.iceServers &&
        roomConfig.unreliableMode == config.unreliableMode
}

fun officialWebRtcRoomProfile(
    name: String,
    roomId: String,
    stunUrl: String = "",
    turnUrl: String = "",
    turnUsername: String = "",
    turnPassword: String = "",
    createdAt: Long = 0L,
    updatedAt: Long = 0L,
    maxDevices: Int = 0,
): WebRtcRoomProfile = WebRtcRoomProfile(
    id = 0L,
    name = name,
    wssUrl = "",
    roomId = roomId,
    stunUrl = stunUrl,
    turnUrl = turnUrl,
    turnUsername = turnUsername,
    turnPassword = turnPassword,
    source = WebRtcRoomSource.Official,
    pinned = false,
    sortOrder = 0L,
    createdAt = createdAt,
    updatedAt = updatedAt,
    maxDevices = maxDevices,
)

fun WebRtcRoomProfile.withoutCachedTurnPassword(): WebRtcRoomProfile =
    if (turnPassword.isEmpty()) this else copy(turnPassword = "")

data class OfficialWebRtcRoomPage(
    val rooms: List<WebRtcRoomProfile>,
    val total: Int,
    val page: Int,
    val pageSize: Int,
)

interface WebRtcOfficialRoomsClient {
    suspend fun listRooms(page: Int, pageSize: Int): Result<OfficialWebRtcRoomPage>

    suspend fun getRoom(roomId: String): Result<WebRtcRoomProfile>

    suspend fun createRoom(input: WebRtcRoomInput): Result<WebRtcRoomProfile>

    suspend fun updateRoom(roomId: String, input: WebRtcRoomInput): Result<WebRtcRoomProfile>

    suspend fun deleteRoom(roomId: String): Result<Unit>
}

object WebRtcOfficialRooms {
    var client: WebRtcOfficialRoomsClient = UnavailableWebRtcOfficialRoomsClient

    const val DefaultPageSize: Int = 20
}

const val OfficialRoomNameMaxLength: Int = 100

private object UnavailableWebRtcOfficialRoomsClient : WebRtcOfficialRoomsClient {
    override suspend fun listRooms(page: Int, pageSize: Int): Result<OfficialWebRtcRoomPage> =
        unavailable()

    override suspend fun getRoom(roomId: String): Result<WebRtcRoomProfile> =
        unavailable()

    override suspend fun createRoom(input: WebRtcRoomInput): Result<WebRtcRoomProfile> =
        unavailable()

    override suspend fun updateRoom(roomId: String, input: WebRtcRoomInput): Result<WebRtcRoomProfile> =
        unavailable()

    override suspend fun deleteRoom(roomId: String): Result<Unit> =
        unavailable()

    private fun <T> unavailable(): Result<T> =
        Result.failure(IllegalStateException(AppStrings.ui_log_in_before_connecting_official_webrtc_room))
}

fun WebRtcConnectionStatus.isBusy(): Boolean {
    return this == WebRtcConnectionStatus.Connecting || this == WebRtcConnectionStatus.Connected
}
