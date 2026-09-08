package com.folderspan.service.webrtc.models

private const val DEFAULT_WEBRTC_ICE_HOST = "119.91.209.238"

const val DEFAULT_WEBRTC_STUN_URL = "stun:$DEFAULT_WEBRTC_ICE_HOST:3478"
const val DEFAULT_WEBRTC_TURN_URL = "turn:$DEFAULT_WEBRTC_ICE_HOST:3478"
const val DEFAULT_WEBRTC_TURN_USERNAME = "folderspan"

data class WebRtcIceServerConfig(
    val urls: List<String>,
    val username: String = "",
    val password: String = ""
)

internal data class WebRtcIceServerAttempt(
    val label: String,
    val iceServers: List<WebRtcIceServerConfig>
)

fun buildWebRtcIceServers(
    stunUrl: String = DEFAULT_WEBRTC_STUN_URL,
    turnUrl: String = "",
    turnUsername: String = "",
    turnPassword: String = "",
): List<WebRtcIceServerConfig> {
    val normalizedStunUrl = stunUrl.trim()
    val normalizedTurnUrl = turnUrl.trim()
    val normalizedTurnUsername = turnUsername.trim()
    val normalizedTurnPassword = turnPassword
    return buildList {
        if (normalizedStunUrl.isNotEmpty()) {
            add(WebRtcIceServerConfig(urls = listOf(normalizedStunUrl)))
        }
        if (normalizedTurnUrl.isNotEmpty()) {
            add(
                WebRtcIceServerConfig(
                    urls = listOf(normalizedTurnUrl),
                    username = normalizedTurnUsername,
                    password = normalizedTurnPassword
                )
            )
        }
    }
}

internal fun buildWebRtcIceServerAttempts(
    iceServers: List<WebRtcIceServerConfig>
): List<WebRtcIceServerAttempt> {
    val attempts = mutableListOf<WebRtcIceServerAttempt>()

    fun addAttempt(label: String, servers: List<WebRtcIceServerConfig>) {
        if (attempts.none { it.iceServers == servers }) {
            attempts += WebRtcIceServerAttempt(label = label, iceServers = servers)
        }
    }

    addAttempt(label = "configured", servers = iceServers)

    val withoutCredentiallessTurn = iceServers.filterNot { server ->
        server.hasTurnUrl() && server.username.isBlank() && server.password.isBlank()
    }
    if (withoutCredentiallessTurn != iceServers) {
        addAttempt(
            label = "without-credentialless-turn",
            servers = withoutCredentiallessTurn
        )
    }

    val stunOnly = iceServers.filterNot { it.hasTurnUrl() }
    if (stunOnly != iceServers) {
        addAttempt(label = "stun-only", servers = stunOnly)
    }

    if (iceServers.isNotEmpty()) {
        addAttempt(label = "no-ice-servers", servers = emptyList())
    }

    return attempts
}

internal fun List<WebRtcIceServerConfig>.toDebugSummary(): String {
    if (isEmpty()) return "[]"
    return joinToString(prefix = "[", postfix = "]") { server ->
        val maskedUser = if (server.username.isBlank()) "<empty>" else "<set>"
        "urls=${server.urls.joinToString()} username=$maskedUser"
    }
}

private fun WebRtcIceServerConfig.hasTurnUrl(): Boolean {
    return urls.any { url ->
        val normalized = url.trim().lowercase()
        normalized.startsWith("turn:") || normalized.startsWith("turns:")
    }
}
