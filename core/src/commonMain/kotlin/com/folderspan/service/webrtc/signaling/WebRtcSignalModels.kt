package com.folderspan.service.webrtc.signaling

import com.folderspan.service.data.SocketDevice
import com.folderspan.service.data.DeviceSessionBootstrapAuthorization
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SignalingDevice(
    val id: String,
    val name: String? = null,
    val pathSeparator: String? = null,
    val host: String? = null,
    val port: Int? = null,
    val type: String? = null,
    val connectType: String? = null,
    val userUuid: String? = null,
)

@Serializable
data class SignalingIceCandidate(
    val sdpMid: String,
    val sdpMLineIndex: Int,
    val candidate: String
)

@Serializable
data class SignalingMessage(
    val type: String,
    val roomId: String? = null,
    val from: SignalingDevice? = null,
    val to: SignalingDevice? = null,
    val sdp: String? = null,
    val candidate: SignalingIceCandidate? = null,
    val peers: List<SignalingDevice>? = null,
    val ts: Long? = null,
    val code: String? = null,
    val connectionAttemptId: String? = null,
    val bootstrapAuthorization: DeviceSessionBootstrapAuthorization? = null,
    val shareNonce: String? = null,
    val tlsFingerprintSha256: String? = null,
    @SerialName("message")
    val errorMessage: String? = null
)

fun SocketDevice.toSignalingDevice(overriddenId: String? = null): SignalingDevice {
    return SignalingDevice(
        id = overriddenId ?: id,
        name = name,
        pathSeparator = pathSeparator,
        host = host,
        port = port,
        type = type.name,
        connectType = connectType.name
    )
}

fun SignalingDevice.asTargetDevice(): SignalingDevice {
    return SignalingDevice(
        id = id,
        userUuid = userUuid?.trim()?.takeIf(String::isNotBlank),
    )
}
