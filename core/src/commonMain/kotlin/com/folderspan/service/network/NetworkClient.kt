package com.folderspan.service.network

import com.folderspan.data.main.network.NetworkDriveExtras
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import com.folderspan.exception.NetworkUnsupportedException
import strings.AppStrings

@Serializable
data class NetworkConnectionConfig(
    val protocol: String,
    val host: String,
    val username: String,
    val password: String,
    val pathSeparator: String = "/",
    val extras: NetworkDriveExtras = NetworkDriveExtras(),
    @Transient internal val sessionOwner: String = "",
) {
    override fun toString() = "NetworkConnectionConfig(protocol=$protocol)"
}

// Protocol-level client interface.
@Serializable
data class NetworkFileEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long = 0L,
    val createdDate: Long = 0L,
    val updatedDate: Long = 0L,
    val isHidden: Boolean = false,
    val isSymbolicLink: Boolean = false,
    val isSymbolicLinkKnown: Boolean = false,
)

interface NetworkClient {
    /** Stateless clients and clients borrowing a shared idle pool have no per-instance resources. */
    suspend fun close() = Unit

    suspend fun list(
        path: String,
        requestId: String? = null,
        batchId: String? = null,
    ): Result<List<NetworkFileEntry>>

    suspend fun download(
        remotePath: String,
        localPath: String,
        size: Long,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): Result<Boolean>

    suspend fun upload(
        localPath: String,
        remotePath: String,
        size: Long,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): Result<Boolean>

    /**
     * 按顺序消费源字节并上传到 [remotePath]。
     *
     * [size] ≥ 0 时作为 Content-Length / 进度总量；未知长度时传负数，需要长度的协议应失败而不是静默落地。
     */
    suspend fun uploadFromSource(
        remotePath: String,
        size: Long,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
        readChunk: suspend () -> ByteArray?,
    ): Result<Boolean>

    suspend fun copyFile(sourcePath: String, targetPath: String): Result<Boolean> =
        Result.failure(
            NetworkUnsupportedException(
                AppStrings.ui_current_network_protocol_does_not_support_remote_single_file_replication.format(arg0 = (sourcePath), arg1 = (targetPath))
            )
        )

    suspend fun rename(path: String, newPath: String): Result<Boolean>

    suspend fun delete(path: String, isDirectory: Boolean): Result<Boolean>

    suspend fun createFolder(path: String): Result<Boolean>

    suspend fun createFile(path: String): Result<Boolean>
}

internal interface ChunkReadableNetworkClient {
    suspend fun downloadByChunks(
        remotePath: String,
        size: Long,
        onChunk: suspend (chunk: ByteArray, totalBytes: Long) -> Result<Unit>,
    ): Result<Boolean>
}

class UnsupportedNetworkClient(
    private val message: String
) : NetworkClient {
    private fun failure(): Result<Boolean> = Result.failure(NetworkUnsupportedException(message))
    private fun listFailure(): Result<List<NetworkFileEntry>> = Result.failure(NetworkUnsupportedException(message))

    override suspend fun list(
        path: String,
        requestId: String?,
        batchId: String?
    ): Result<List<NetworkFileEntry>> = listFailure()

    override suspend fun download(
        remotePath: String,
        localPath: String,
        size: Long,
        onProgress: (Long, Long) -> Unit
    ): Result<Boolean> = failure()

    override suspend fun upload(
        localPath: String,
        remotePath: String,
        size: Long,
        onProgress: (Long, Long) -> Unit
    ): Result<Boolean> = failure()

    override suspend fun uploadFromSource(
        remotePath: String,
        size: Long,
        onProgress: (Long, Long) -> Unit,
        readChunk: suspend () -> ByteArray?,
    ): Result<Boolean> = failure()

    override suspend fun rename(path: String, newPath: String): Result<Boolean> = failure()

    override suspend fun delete(path: String, isDirectory: Boolean): Result<Boolean> = failure()

    override suspend fun createFolder(path: String): Result<Boolean> = failure()

    override suspend fun createFile(path: String): Result<Boolean> = failure()
}

expect object NetworkClientFactory {
    fun create(network: NetworkConnectionConfig): NetworkClient
}
