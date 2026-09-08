package com.folderspan.service.session

internal val DEVICE_SESSION_ALPN_WIRE_BYTES: ByteArray = byteArrayOf(
    DEVICE_SESSION_ALPN.length.toByte(),
    *DEVICE_SESSION_ALPN.encodeToByteArray(),
)

internal fun isDeviceSessionAlpn(protocol: String?): Boolean {
    return protocol == DEVICE_SESSION_ALPN
}
