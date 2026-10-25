package com.folderspan.service.webrtc.models

import strings.AppStrings

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
    stunUrl: String = "",
    turnUrl: String = "",
    turnUsername: String = "",
    turnPassword: String = "",
): List<WebRtcIceServerConfig> {
    val normalizedStunUrl = stunUrl.trim()
    val normalizedTurnUrl = turnUrl.trim()
    val normalizedTurnUsername = turnUsername.trim()
    val normalizedTurnPassword = turnPassword
    val turnUrls = if (normalizedTurnUrl.isEmpty()) emptyList() else normalizedTurnUrl.split(';').map(String::trim)
    require(normalizedStunUrl.isEmpty() || isWebRtcIceUri(normalizedStunUrl, turn = false)) {
        AppStrings.webrtc_invalid_ice_url
    }
    require(turnUrls.all { isWebRtcIceUri(it, turn = true) }) { AppStrings.webrtc_invalid_ice_url }
    return buildList {
        if (normalizedStunUrl.isNotEmpty()) {
            add(WebRtcIceServerConfig(urls = listOf(normalizedStunUrl)))
        }
        if (turnUrls.isNotEmpty()) {
            add(
                WebRtcIceServerConfig(
                    urls = turnUrls,
                    username = normalizedTurnUsername,
                    password = normalizedTurnPassword
                )
            )
        }
    }
}

private val iceUriPattern = Regex("^(stun|turn|turns):([^/?#\\s]+?)(?:\\?(transport)=(udp|tcp))?$")

internal fun isWebRtcIceUri(value: String, turn: Boolean): Boolean {
    val match = iceUriPattern.matchEntire(value) ?: return false
    val (scheme, authority, transportKey, transport) = match.destructured
    if (turn != (scheme != "stun") || (!turn && transportKey.isNotEmpty())) return false
    if (scheme == "turns" && transport == "udp") return false
    val host: String
    val port: String?
    if (authority.startsWith('[')) {
        val end = authority.indexOf(']')
        if (end < 0) return false
        host = authority.substring(1, end)
        if (!isWebRtcIpv6(host)) return false
        val suffix = authority.substring(end + 1)
        if (suffix.isNotEmpty() && !suffix.startsWith(':')) return false
        port = suffix.takeIf { it.isNotEmpty() }?.drop(1)
    } else {
        if (authority.count { it == ':' } > 1) return false
        host = authority.substringBefore(':')
        port = authority.substringAfter(':', "").takeIf { ':' in authority }
        if (host.all { it.isDigit() || it == '.' }) {
            if (!isWebRtcIpv4(host)) return false
        } else if (!host.removeSuffix(".").split('.').all { label ->
                label.length in 1..63 && label.first().isLetterOrDigit() && label.last().isLetterOrDigit() &&
                    label.all { it in 'a'..'z' || it in 'A'..'Z' || it.isDigit() || it == '-' }
            }) return false
    }
    return port == null || (port.isNotEmpty() && port.all(Char::isDigit) && port.toIntOrNull() in 1..65535)
}

internal fun isWebRtcIpv4(value: String): Boolean = value.split('.').let { parts ->
    parts.size == 4 && parts.all { it.isNotEmpty() && it.all(Char::isDigit) && it.toIntOrNull() in 0..255 }
}

internal fun isWebRtcIpv6(value: String): Boolean {
    if (':' !in value || '%' in value || ":::" in value) return false
    val compressed = "::" in value
    if (compressed && value.indexOf("::") != value.lastIndexOf("::")) return false
    if ((value.startsWith(':') && !value.startsWith("::")) || (value.endsWith(':') && !value.endsWith("::"))) return false
    val groups = value.split(':').filter(String::isNotEmpty)
    if (groups.isEmpty()) return value == "::"
    var count = 0
    for ((index, group) in groups.withIndex()) {
        if ('.' in group) {
            if (index != groups.lastIndex || !value.endsWith(group) || !isWebRtcIpv4(group)) return false
            count += 2
        } else {
            if (group.length !in 1..4 || group.any { it.digitToIntOrNull(16) == null }) return false
            count++
        }
    }
    return if (compressed) count < 8 else count == 8
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
        "urlSchemes=${server.urls.joinToString { it.substringBefore(':') }} username=$maskedUser"
    }
}

internal fun List<WebRtcIceServerConfig>.icePathFailureMessage(serverErrorCode: Int? = null): String {
    val turn = filter { it.hasTurnUrl() }
    val message = when {
        turn.isEmpty() -> AppStrings.webrtc_ice_failed_without_turn
        turn.all { it.username.isBlank() || it.password.isEmpty() } -> AppStrings.webrtc_ice_failed_turn_credentials
        else -> AppStrings.webrtc_ice_failed_check_services
    }
    // Some STUN callbacks omit the error class (401 becomes 1); never reconstruct it.
    val reported = when (serverErrorCode) {
        701 -> AppStrings.webrtc_ice_server_unreachable
        401, 403, 441 -> AppStrings.webrtc_ice_server_auth_rejected
        else -> null
    }
    return listOfNotNull(message, reported).joinToString("; ")
}

private fun WebRtcIceServerConfig.hasTurnUrl(): Boolean {
    return urls.any { url ->
        val normalized = url.trim().lowercase()
        normalized.startsWith("turn:") || normalized.startsWith("turns:")
    }
}
