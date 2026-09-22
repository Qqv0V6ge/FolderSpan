package com.folderspan.pro.core.network

import com.folderspan.AppBuildConfig
import com.folderspan.data.main.webrtc.WebRtcOfficialGateway
import com.folderspan.data.main.webrtc.WebRtcOfficialGatewayProvider
import com.folderspan.pro.core.common.normalizeAccessToken
import com.folderspan.pro.core.datastore.SessionManager
import io.ktor.http.*
import strings.AppStrings

private const val OFFICIAL_WEBRTC_WEBSOCKET_PATH = "/api/v1/webrtc/ws"

fun installProWebRtcOfficialGatewayProvider() {
    WebRtcOfficialGateway.provider = ProWebRtcOfficialGatewayProvider
}

internal fun officialWebRtcWebSocketUrl(
    gatewayBaseUrl: String = AppBuildConfig.GATEWAY_BASE_URL
): String {
    val trimmed = gatewayBaseUrl.trim().trimEnd('/')
    val websocketBase = when {
        trimmed.startsWith("https://", ignoreCase = true) -> "wss://" + trimmed.substringAfter("://")
        trimmed.startsWith("http://", ignoreCase = true) -> "ws://" + trimmed.substringAfter("://")
        else -> trimmed
    }
    return websocketBase + OFFICIAL_WEBRTC_WEBSOCKET_PATH
}

internal fun officialWebRtcWebSocketHeaders(
    token: String?,
    deviceKey: String = runtimeDeviceIdentity().key,
    requestSigningConfig: RequestSigningConfig = RequestSigningConfig(),
): Result<Map<String, String>> {
    val normalizedToken = normalizeAccessToken(token) ?: token.orEmpty().trim()
    if (normalizedToken.isBlank()) {
        return Result.failure(IllegalStateException(AppStrings.ui_log_in_before_connecting_official_webrtc_room))
    }
    val normalizedDeviceKey = deviceKey.trim()
    if (normalizedDeviceKey.isBlank()) {
        return Result.failure(IllegalStateException(AppStrings.ui_device_id_required_for_official_webrtc_room))
    }

    val timestamp = requestSigningConfig.timestampProvider()
    val nonce = requestSigningConfig.nonceProvider()
    val signed = RequestSigner.sign(
        method = "GET",
        path = OFFICIAL_WEBRTC_WEBSOCKET_PATH,
        query = emptyList(),
        body = ByteArray(0),
        timestamp = timestamp,
        nonce = nonce,
        secret = normalizedToken,
    )

    return Result.success(
        linkedMapOf(
            HttpHeaders.Host to PRO_API_HOST_HEADER_VALUE,
            HttpHeaders.Origin to "${Url(AppBuildConfig.GATEWAY_BASE_URL).protocol.name}://$PRO_API_HOST_HEADER_VALUE",
            HttpHeaders.Authorization to "Bearer $normalizedToken",
            PRO_API_DEVICE_KEY_HEADER to normalizedDeviceKey,
            PRO_API_APP_KEY_HEADER to requestSigningConfig.appKey,
            PRO_API_TIMESTAMP_HEADER to timestamp,
            PRO_API_NONCE_HEADER to nonce,
            PRO_API_SIGNATURE_HEADER to signed.signature,
        )
    )
}

private object ProWebRtcOfficialGatewayProvider : WebRtcOfficialGatewayProvider {
    override fun websocketUrl(): String = officialWebRtcWebSocketUrl()

    override fun websocketHeaders(): Result<Map<String, String>> =
        officialWebRtcWebSocketHeaders(
            token = SessionManager.currentToken(),
            deviceKey = runtimeDeviceIdentity().key,
        )
}
