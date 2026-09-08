package com.folderspan.service.session

internal data class DeviceSessionBootstrapConnection(
    val channel: DeviceSessionByteChannel,
    val peerFingerprintSha256: String,
)

internal expect fun connectPinnedDeviceSessionChannel(
    host: String,
    port: Int,
    expectedFingerprintSha256: String,
): DeviceSessionByteChannel

internal expect fun connectUnpinnedDeviceSessionChannel(
    host: String,
    port: Int,
): DeviceSessionBootstrapConnection
