package com.folderspan.service.session

internal actual suspend fun connectPinnedDeviceSessionChannel(
    host: String,
    port: Int,
    expectedFingerprintSha256: String,
): DeviceSessionByteChannel {
    return JvmDeviceSessionByteChannel(
        createPinnedDeviceSessionSocket(host, port, expectedFingerprintSha256),
    )
}

internal actual suspend fun connectUnpinnedDeviceSessionChannel(
    host: String,
    port: Int,
): DeviceSessionBootstrapConnection {
    val (socket, fingerprint) = createUnpinnedDeviceSessionSocket(host, port)
    return DeviceSessionBootstrapConnection(
        channel = JvmDeviceSessionByteChannel(socket),
        peerFingerprintSha256 = fingerprint,
    )
}
