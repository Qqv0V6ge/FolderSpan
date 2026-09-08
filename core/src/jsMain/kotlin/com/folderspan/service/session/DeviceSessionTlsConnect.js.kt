package com.folderspan.service.session

internal actual fun connectPinnedDeviceSessionChannel(
    host: String,
    port: Int,
    expectedFingerprintSha256: String,
): DeviceSessionByteChannel {
    error("JS/Wasm clients do not open folderspan/1 device sessions")
}

internal actual fun connectUnpinnedDeviceSessionChannel(
    host: String,
    port: Int,
): DeviceSessionBootstrapConnection {
    error("JS/Wasm clients do not open folderspan/1 device sessions")
}
