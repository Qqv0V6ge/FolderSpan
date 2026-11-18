package com.folderspan.service.session

internal data class DeviceSessionBootstrapConnection(
    val channel: DeviceSessionByteChannel,
    val peerFingerprintSha256: String,
)

internal expect suspend fun connectPinnedDeviceSessionChannel(
    host: String,
    port: Int,
    expectedFingerprintSha256: String,
    connectTimeoutMillis: Int = 0,
): DeviceSessionByteChannel

internal expect suspend fun connectUnpinnedDeviceSessionChannel(
    host: String,
    port: Int,
): DeviceSessionBootstrapConnection
