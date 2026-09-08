package com.folderspan.routes

import strings.AppStrings

import com.folderspan.service.webrtc.signaling.*
import com.folderspan.utils.LogKit
import kotlinx.serialization.json.Json

private val rawHttpWebRtcJson = Json { ignoreUnknownKeys = true }

internal suspend fun RawHttpApiDispatcher.handleWebRtcSignaling(request: RawHttpRequest): RawHttpResponse {
    return when {
        request.method.equals("GET", ignoreCase = true) && request.path == "/api/webrtc/signaling/discover" ->
            handleWebRtcSignalingDiscover(request)

        request.method.equals("POST", ignoreCase = true) && request.path == "/api/webrtc/signaling/join" ->
            handleWebRtcSignalingJoin(request)

        request.method.equals("POST", ignoreCase = true) && request.path == "/api/webrtc/signaling/send" ->
            handleWebRtcSignalingSend(request)

        request.method.equals("GET", ignoreCase = true) && request.path == "/api/webrtc/signaling/poll" ->
            handleWebRtcSignalingPoll(request)

        request.method.equals("POST", ignoreCase = true) && request.path == "/api/webrtc/signaling/leave" ->
            handleWebRtcSignalingLeave(request)

        else -> rawJsonResponse(
            HttpWebRtcSignalResponse(
                accepted = false,
                code = "NOT_FOUND",
                message = "WebRTC signaling endpoint not found"
            ),
            request = request,
            statusCode = 404
        )
    }
}

private suspend fun RawHttpApiDispatcher.handleWebRtcSignalingDiscover(request: RawHttpRequest? = null): RawHttpResponse {
    if (request == null || !hasRequiredFileShareAccessKey(request)) {
        return rawJsonResponse(
            HttpWebRtcDiscoverResponse(
                code = "DISCOVER_UNAUTHORIZED",
                message = AppStrings.ui_webrtc_configured_file_share_access_key_required,
            ),
            request = request,
            statusCode = 401,
        )
    }
    val response = rawHttpWebRtcSignalingHub.discoverHostLocked()
    return rawJsonResponse(response, request = request)
}

private suspend fun RawHttpApiDispatcher.handleWebRtcSignalingJoin(request: RawHttpRequest): RawHttpResponse {
    return runCatching {
        val joinRequest = rawHttpWebRtcJson.decodeFromString(
            HttpWebRtcJoinRequest.serializer(),
            request.body.decodeToString()
        )
        if (joinRequest.role == HttpWebRtcSignalingRole.Host) {
            val requestSecret = request.header(HTTP_WEBRTC_HOST_SECRET_HEADER).orEmpty()
            if (!HttpWebRtcHostAuth.verify(requestSecret)) {
                return@runCatching HttpWebRtcJoinResponse(
                    clientId = "",
                    peers = emptyList(),
                    code = "HOST_JOIN_FORBIDDEN",
                    message = "Host can only be registered by the local app"
                )
            }
            rawHttpWebRtcSignalingHub.registerHostLocked(joinRequest.device)
        } else {
            if (!hasRequiredFileShareAccessKey(request)) {
                HttpWebRtcJoinResponse(
                    clientId = "",
                    peers = emptyList(),
                    code = "BROWSER_JOIN_FORBIDDEN",
                    message = AppStrings.ui_webrtc_configured_file_share_access_key_required,
                )
            } else {
                rawHttpWebRtcSignalingHub.joinLocked(joinRequest.role, joinRequest.device)
            }
        }
    }.fold(
        onSuccess = { response ->
            rawJsonResponse(response, request = request, statusCode = joinStatusCode(response.code))
        },
        onFailure = { error ->
            LogKit.w("${AppStrings.ui_http_webrtc_join_failed}: ${error.message}")
            rawJsonResponse(
                HttpWebRtcSignalResponse(
                    accepted = false,
                    code = "INVALID_MESSAGE",
                    message = error.message ?: "Invalid join request"
                ),
                request = request,
                statusCode = 400
            )
        }
    )
}

private suspend fun RawHttpApiDispatcher.handleWebRtcSignalingSend(request: RawHttpRequest): RawHttpResponse {
    return runCatching {
        val signalRequest = rawHttpWebRtcJson.decodeFromString(
            HttpWebRtcSignalRequest.serializer(),
            request.body.decodeToString()
        )
        rawHttpWebRtcSignalingHub.sendLocked(
            clientId = signalRequest.clientId,
            clientToken = signalRequest.clientToken,
            message = signalRequest.message
        )
    }.fold(
        onSuccess = { response ->
            rawJsonResponse(response, request = request, statusCode = signalStatusCode(response))
        },
        onFailure = { error ->
            LogKit.w("${AppStrings.ui_http_webrtc_send_failed}: ${error.message}")
            rawJsonResponse(
                HttpWebRtcSignalResponse(
                    accepted = false,
                    code = "INVALID_MESSAGE",
                    message = error.message ?: "Invalid signaling message"
                ),
                request = request,
                statusCode = 400
            )
        }
    )
}

private suspend fun RawHttpApiDispatcher.handleWebRtcSignalingPoll(request: RawHttpRequest): RawHttpResponse {
    if (request.queryParameters.containsKey("clientToken")) {
        return rawJsonResponse(
            HttpWebRtcSignalResponse(
                accepted = false,
                code = "UNAUTHORIZED",
                message = AppStrings.ui_webrtc_client_token_header_required,
            ),
            request = request,
            statusCode = 401,
        )
    }
    val clientId = request.queryParameters["clientId"].orEmpty()
    val clientToken = request.header(HTTP_WEBRTC_CLIENT_TOKEN_HEADER).orEmpty()
    if (clientToken.isBlank()) {
        return rawJsonResponse(
            HttpWebRtcSignalResponse(
                accepted = false,
                code = "UNAUTHORIZED",
                message = AppStrings.ui_webrtc_client_token_missing,
            ),
            request = request,
            statusCode = 401,
        )
    }
    val cursor = request.queryParameters["cursor"]?.toLongOrNull() ?: 0L
    val response = rawHttpWebRtcSignalingHub.pollLocked(clientId, clientToken, cursor)
    return rawJsonResponse(response, request = request)
}

private suspend fun RawHttpApiDispatcher.handleWebRtcSignalingLeave(request: RawHttpRequest): RawHttpResponse {
    return runCatching {
        val leaveRequest = rawHttpWebRtcJson.decodeFromString(
            HttpWebRtcLeaveRequest.serializer(),
            request.body.decodeToString()
        )
        rawHttpWebRtcSignalingHub.leaveLocked(leaveRequest.clientId, leaveRequest.clientToken)
    }.fold(
        onSuccess = { response ->
            rawJsonResponse(response, request = request, statusCode = if (response.accepted) 200 else 409)
        },
        onFailure = { error ->
            LogKit.w("${AppStrings.ui_http_webrtc_leave_failed}: ${error.message}")
            rawJsonResponse(
                HttpWebRtcSignalResponse(
                    accepted = false,
                    code = "INVALID_MESSAGE",
                    message = error.message ?: "Invalid leave request"
                ),
                request = request,
                statusCode = 400
            )
        }
    )
}

private inline fun <reified T> RawHttpApiDispatcher.rawJsonResponse(
    value: T,
    request: RawHttpRequest? = null,
    statusCode: Int = 200,
): RawHttpResponse {
    return rawBytes(
        rawHttpWebRtcJson.encodeToString(value).encodeToByteArray(),
        contentType = "application/json; charset=UTF-8",
        headers = webRtcSignalingHeaders(this, request)
    ).copy(statusCode = statusCode, reasonPhrase = reasonPhrase(statusCode))
}

private fun joinStatusCode(code: String?): Int {
    return when (code) {
        null -> 200
        "INVALID_DEVICE" -> 400
        "PEER_LIMIT_EXCEEDED" -> 429
        else -> 403
    }
}

private fun signalStatusCode(response: HttpWebRtcSignalResponse): Int {
    return when {
        response.accepted -> 200
        response.code == "RATE_LIMITED" -> 429
        response.code == "INVALID_MESSAGE" -> 400
        else -> 409
    }
}
