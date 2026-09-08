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
    val updatedAt: Long
) {
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
    val roomConfig = toWebRtcConfig()
    return config != null &&
        roomConfig.wssUrl == config.wssUrl &&
        roomConfig.roomId == config.roomId &&
        roomConfig.iceServers == config.iceServers &&
        roomConfig.unreliableMode == config.unreliableMode
}

fun WebRtcConnectionStatus.isBusy(): Boolean {
    return this == WebRtcConnectionStatus.Connecting || this == WebRtcConnectionStatus.Connected
}
