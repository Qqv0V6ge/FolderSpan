package com.folderspan.pro.domain.usecase

import com.folderspan.pro.domain.model.NetworkConfigurationSnapshot
import com.folderspan.pro.domain.model.NetworkSnapshotItem
import kotlinx.serialization.json.JsonElement
import kotlin.io.encoding.Base64

interface ConfigurationSnapshotProvider {
    val key: String
    suspend fun read(): JsonElement
    suspend fun apply(value: JsonElement)
}

data class EncryptedNetworkSnapshotItem(
    val name: String,
    val protocol: String,
    val host: String,
    val username: String,
    val encryptedPassword: String,
    val pathSeparator: String,
    val pinned: Boolean,
    val encryptedExtras: ByteArray,
)

class NetworkConfigurationSnapshotCodec {
    fun encode(networks: List<EncryptedNetworkSnapshotItem>): NetworkConfigurationSnapshot =
        NetworkConfigurationSnapshot(
            networks = networks.map { item ->
                NetworkSnapshotItem(
                    name = item.name,
                    protocol = item.protocol,
                    host = item.host,
                    username = item.username,
                    encryptedPassword = item.encryptedPassword,
                    pathSeparator = item.pathSeparator,
                    pinned = item.pinned,
                    encryptedExtras = encodeExtras(item.encryptedExtras),
                )
            },
        )

    fun decode(snapshot: NetworkConfigurationSnapshot): List<EncryptedNetworkSnapshotItem> =
        snapshot.networks.mapNotNull { item ->
            runCatching {
                EncryptedNetworkSnapshotItem(
                    name = item.name,
                    protocol = item.protocol,
                    host = item.host,
                    username = item.username,
                    encryptedPassword = item.encryptedPassword,
                    pathSeparator = item.pathSeparator,
                    pinned = item.pinned,
                    encryptedExtras = decodeExtras(item.encryptedExtras),
                )
            }.getOrNull()
        }

    private fun encodeExtras(value: ByteArray): String =
        if (value.isEmpty()) "" else Base64.encode(value)

    private fun decodeExtras(value: String): ByteArray =
        if (value.isBlank()) ByteArray(0) else Base64.decode(value)
}
