package com.folderspan.service.webrtc.signaling

import com.folderspan.extensions.randomString

const val HTTP_WEBRTC_HOST_SECRET_HEADER = "x-folderspan-webrtc-host-secret"
const val HTTP_WEBRTC_CLIENT_TOKEN_HEADER = "x-folderspan-webrtc-client-token"

internal object HttpWebRtcHostAuth {
    val secret: String = 48.randomString(includeSpecial = false)

    fun verify(value: String): Boolean {
        return secret.secureEquals(value)
    }
}

internal fun String.secureEquals(other: String): Boolean {
    if (isBlank() || other.isBlank()) return false
    var diff = length xor other.length
    val max = maxOf(length, other.length)
    for (index in 0 until max) {
        val a = getOrNull(index)?.code ?: 0
        val b = other.getOrNull(index)?.code ?: 0
        diff = diff or (a xor b)
    }
    return diff == 0
}
