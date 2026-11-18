package com.folderspan.service.session

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal actual suspend fun connectPinnedDeviceSessionChannel(
    host: String,
    port: Int,
    expectedFingerprintSha256: String,
    connectTimeoutMillis: Int,
): DeviceSessionByteChannel {
    var opened: DeviceSessionByteChannel? = null
    return try {
        withContext(Dispatchers.IO) {
            val socket = createPinnedDeviceSessionSocket(host, port, expectedFingerprintSha256, connectTimeoutMillis)
            AndroidDeviceSessionByteChannel(socket).also { opened = it }
        }
    } catch (error: Throwable) {
        opened?.close()
        throw error
    }
}

internal actual suspend fun connectUnpinnedDeviceSessionChannel(
    host: String,
    port: Int,
): DeviceSessionBootstrapConnection {
    val (socket, fingerprint) = createUnpinnedDeviceSessionSocket(host, port)
    return DeviceSessionBootstrapConnection(
        channel = AndroidDeviceSessionByteChannel(socket),
        peerFingerprintSha256 = fingerprint,
    )
}
