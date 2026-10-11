package com.folderspan.service.session

internal expect object DeviceLanBeaconUdp {
    suspend fun broadcast(payload: ByteArray, port: Int = DEVICE_LAN_BEACON_PORT)

    suspend fun receive(
        port: Int = DEVICE_LAN_BEACON_PORT,
        timeoutMs: Long,
    ): List<Pair<String, ByteArray>>

    suspend fun listen(
        port: Int = DEVICE_LAN_BEACON_PORT,
        onPacket: suspend (host: String, payload: ByteArray) -> Unit,
    )
}
