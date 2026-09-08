package com.folderspan.service.webrtc.signaling

import com.folderspan.service.webrtc.models.WebRtcConfig
import com.folderspan.service.webrtc.models.buildWebRtcIceServers

enum class WebRtcSignalingTransport {
    WebSocket,
    Http
}

fun resolveWebRtcSignalingTransport(url: String): WebRtcSignalingTransport {
    val scheme = url.trim().substringBefore("://", "").lowercase()
    return when (scheme) {
        "http" -> WebRtcSignalingTransport.Http
        else -> WebRtcSignalingTransport.WebSocket
    }
}

fun isWebRtcConfigRoomIdValidForTransport(roomId: String): Boolean {
    val normalizedRoomId = roomId.trim()
    return normalizedRoomId.isNotBlank() && isValidRoomId(normalizedRoomId)
}

fun buildBrowserWebRtcConfig(baseUrl: String): WebRtcConfig {
    return WebRtcConfig(
        wssUrl = baseUrl.trim(),
        roomId = BROWSER_WEBRTC_ROOM_ID,
        iceServers = buildWebRtcIceServers(),
        unreliableMode = false
    )
}

fun buildBrowserWebRtcDeviceBaseUrl(host: String, port: Int): String {
    val normalizedHost = host.trim()
    if (normalizedHost.isBlank() || port <= 0) return ""
    return "http://${normalizedHost.hostForBrowserWebRtcUrl()}:$port"
}

fun isAllowedBrowserWebRtcSignalingBaseUrl(baseUrl: String): Boolean {
    val normalized = baseUrl.trim()
    if (normalized.isBlank()) return false
    val schemeSeparator = normalized.indexOf("://")
    return schemeSeparator > 0 && normalized.substring(0, schemeSeparator).equals("http", ignoreCase = true) &&
            normalized.length > schemeSeparator + 3
}

fun canEnableBrowserWebRtcSignaling(baseUrl: String, browserSecureContext: Boolean): Boolean =
    browserSecureContext && isAllowedBrowserWebRtcSignalingBaseUrl(baseUrl)

private fun String.hostForBrowserWebRtcUrl(): String {
    val normalized = trim().removePrefix("[").removeSuffix("]")
    return if (normalized.contains(":")) "[$normalized]" else normalized
}

const val BROWSER_WEBRTC_ROOM_ID = "browser-http"
