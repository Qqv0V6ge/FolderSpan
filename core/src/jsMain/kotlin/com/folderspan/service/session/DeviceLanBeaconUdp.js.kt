package com.folderspan.service.session

import kotlinx.coroutines.awaitCancellation

internal actual object DeviceLanBeaconUdp {
    actual suspend fun broadcast(payload: ByteArray, port: Int) = Unit

    actual suspend fun receive(port: Int, timeoutMs: Long): List<Pair<String, ByteArray>> = emptyList()

    actual suspend fun listen(
        port: Int,
        onPacket: suspend (host: String, payload: ByteArray) -> Unit,
    ) {
        awaitCancellation()
    }
}
