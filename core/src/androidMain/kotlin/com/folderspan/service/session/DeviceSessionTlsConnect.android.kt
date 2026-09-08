package com.folderspan.service.session

internal actual fun connectPinnedDeviceSessionChannel(
    host: String,
    port: Int,
    expectedFingerprintSha256: String,
): DeviceSessionByteChannel {
    return AndroidDeviceSessionByteChannel(
        createPinnedDeviceSessionSocket(host, port, expectedFingerprintSha256),
    )
}

internal actual fun connectUnpinnedDeviceSessionChannel(
    host: String,
    port: Int,
): DeviceSessionBootstrapConnection {
    val (socket, fingerprint) = createUnpinnedDeviceSessionSocket(host, port)
    return DeviceSessionBootstrapConnection(
        channel = AndroidDeviceSessionByteChannel(socket),
        peerFingerprintSha256 = fingerprint,
    )
}
