package com.folderspan.service.network

import com.folderspan.data.main.network.NetworkDriveExtras
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.coroutines.CancellationException
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
    val reference: StorageReference? = null,
)

@Serializable
data class StorageCapabilities(
    val read: Boolean = true,
    val write: Boolean = true,
    val delete: Boolean = true,
    val rename: Boolean = true,
    val sync: Boolean = true,
) {
    companion object {
        val ReadWrite = StorageCapabilities()
        val ReadOnly = StorageCapabilities(write = false, delete = false, rename = false)
        val Unavailable = StorageCapabilities(read = false, write = false, delete = false, rename = false)
    }
}

internal class StorageEntryNotFoundException : Exception("Storage entry not found")

interface StorageProvider : StorageUploadPersistence, StorageContentManifestStore {
    val capabilities: StorageCapabilities get() = StorageCapabilities.ReadWrite
    suspend fun effectiveCapabilities(): Result<StorageCapabilities> = Result.success(capabilities)
    val rootLocation: StoragePath get() = StoragePath("/")

    suspend fun advancedCapabilities(path: StoragePath): Result<StorageAdvancedCapabilities> =
        Result.success(StorageAdvancedCapabilities())

    suspend fun startUpload(target: StoragePath, size: Long): Result<StorageUploadSession> =
        unavailableAdvancedOperation(target, StorageAdvancedFeature.RESUMABLE_UPLOAD)
    suspend fun queryUpload(session: StorageUploadSession): Result<StorageUploadSession> =
        unavailableAdvancedOperation(session.target, StorageAdvancedFeature.RESUMABLE_UPLOAD)
    suspend fun uploadChunk(session: StorageUploadSession, offset: Long, bytes: ByteArray): Result<StorageUploadSession> =
        unavailableAdvancedOperation(session.target, StorageAdvancedFeature.RESUMABLE_UPLOAD)
    suspend fun finishUpload(session: StorageUploadSession): Result<StorageUploadSession> =
        unavailableAdvancedOperation(session.target, StorageAdvancedFeature.RESUMABLE_UPLOAD)
    suspend fun cancelUpload(session: StorageUploadSession): Result<Unit> =
        unavailableAdvancedOperation(session.target, StorageAdvancedFeature.RESUMABLE_UPLOAD)

    suspend fun listVersions(path: StoragePath, cursor: String? = null): Result<StoragePage<StorageVersion>> =
        unavailableAdvancedOperation(path, StorageAdvancedFeature.LIST_VERSIONS)
    suspend fun readVersion(version: StorageVersion, onChunk: suspend (ByteArray, Long) -> Result<Unit>): Result<Unit> =
        unavailableAdvancedOperation(version.file, StorageAdvancedFeature.READ_VERSION)
    suspend fun restoreVersion(version: StorageVersion, confirmed: Boolean): Result<NetworkFileEntry> =
        unavailableAdvancedOperation(version.file, StorageAdvancedFeature.RESTORE_VERSION)

    suspend fun listShares(path: StoragePath, cursor: String? = null): Result<StoragePage<StorageShare>> =
        unavailableAdvancedOperation(path, StorageAdvancedFeature.LIST_SHARES)
    suspend fun createShare(path: StoragePath, request: StorageShareRequest, confirmed: Boolean): Result<StorageShare> =
        unavailableAdvancedOperation(path, if (request.kind == StorageShareKind.LINK) StorageAdvancedFeature.CREATE_LINK else StorageAdvancedFeature.INVITE_USER)
    suspend fun revokeShare(share: StorageShare): Result<Unit> =
        unavailableAdvancedOperation(share.file, StorageAdvancedFeature.REVOKE_SHARE)

    suspend fun stat(path: StoragePath): Result<NetworkFileEntry> {
        val name = path.path.substringAfterLast('/')
        val parent = path.path.substringBeforeLast('/', "/").ifEmpty { "/" }
        val parentReference = path.reference?.let { reference ->
            if (reference.parentId.isNotEmpty()) StorageReference(id = reference.parentId)
            else reference.copy(id = "", parentId = "")
        }
        return list(StoragePath(parent, parentReference)).mapCatching { entries ->
            val id = path.reference?.id.orEmpty()
            val matches = entries.filter { if (id.isNotEmpty()) it.reference?.id == id else it.name == name }
            if (matches.isEmpty()) throw StorageEntryNotFoundException()
            check(matches.size == 1) { "Storage entry path is ambiguous" }
            matches.single()
        }
    }

    suspend fun list(path: StoragePath, requestId: String? = null, batchId: String? = null): Result<List<NetworkFileEntry>> =
        list(path.path, requestId, batchId)

    suspend fun download(remotePath: StoragePath, localPath: String, size: Long, onProgress: (Long, Long) -> Unit = { _, _ -> }): Result<Boolean> =
        download(remotePath.path, localPath, size, onProgress)

    suspend fun upload(localPath: String, remotePath: StoragePath, size: Long, onProgress: (Long, Long) -> Unit = { _, _ -> }): Result<Boolean> =
        upload(localPath, remotePath.path, size, onProgress)

    suspend fun uploadFromSource(remotePath: StoragePath, size: Long, onProgress: (Long, Long) -> Unit = { _, _ -> }, readChunk: suspend () -> ByteArray?): Result<Boolean> =
        uploadFromSource(remotePath.path, size, onProgress, readChunk)

    suspend fun copyFile(sourcePath: StoragePath, targetPath: StoragePath): Result<Boolean> = copyFile(sourcePath.path, targetPath.path)
    suspend fun rename(path: StoragePath, newPath: String): Result<Boolean> = rename(path.path, newPath)
    suspend fun move(from: StoragePath, to: StoragePath): Result<Boolean> {
        return try {
            val permissions = effectiveCapabilities().getOrThrow()
            check(permissions.write && permissions.delete && permissions.rename) { "Storage move is unsupported" }
            require(!containsUnsafeNetworkPathSegment(from.path) && !containsUnsafeNetworkPathSegment(to.path)) { "Invalid move path" }
            val source = from.path.replace('\\', '/').trimEnd('/')
            val target = to.path.replace('\\', '/').trimEnd('/')
            require(source.isNotEmpty() && target.isNotEmpty() && source != target && !target.startsWith("$source/")) { "Invalid move destination" }
            val existing = stat(to)
            if (existing.isSuccess) error("Move target already exists")
            check(existing.exceptionOrNull() is StorageEntryNotFoundException) { "Cannot verify that the move target is absent" }
            rename(from, to.path)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { Result.failure(error) }
    }
    suspend fun delete(path: StoragePath, isDirectory: Boolean): Result<Boolean> = delete(path.path, isDirectory)
    suspend fun deleteEmptyDirectory(path: StoragePath): Result<Boolean> = deleteEmptyDirectory(path.path)
    suspend fun deleteEmptyDirectory(path: String): Result<Boolean> =
        Result.failure(NetworkUnsupportedException("Deleting only empty directories is unsupported"))
    suspend fun createFolder(path: StoragePath): Result<Boolean> = createFolder(path.path)
    suspend fun createFile(path: StoragePath): Result<Boolean> = createFile(path.path)


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

    suspend fun setModifiedTime(path: String, millis: Long): Result<Boolean> =
        Result.failure(NetworkUnsupportedException("Modification time is not supported"))

    suspend fun rename(path: String, newPath: String): Result<Boolean>

    suspend fun delete(path: String, isDirectory: Boolean): Result<Boolean>

    suspend fun createFolder(path: String): Result<Boolean>

    suspend fun createFile(path: String): Result<Boolean>
}

internal interface ChunkReadableNetworkClient {
    /** Optional bounded metadata batch; callers authenticate every selected member before using it. */
    suspend fun downloadContentMetadata(
        directory: StoragePath,
        entries: List<NetworkFileEntry>,
        maxEntryBytes: Int,
    ): Result<Map<String, ByteArray>?> = Result.success(null)

    /**
     * The caller must buffer or hash every byte and authenticate the complete body before using it.
     * Authenticated content identity may replace the file's native ID; container identity still applies.
     */
    suspend fun downloadForContentVerification(
        remotePath: StoragePath,
        size: Long,
        onChunk: suspend (chunk: ByteArray, totalBytes: Long) -> Result<Unit>,
    ): Result<Boolean> = downloadByChunks(remotePath, size, onChunk)

    suspend fun downloadByChunks(
        remotePath: StoragePath,
        size: Long,
        onChunk: suspend (chunk: ByteArray, totalBytes: Long) -> Result<Unit>,
    ): Result<Boolean> = downloadByChunks(remotePath.path, size, onChunk)

    suspend fun downloadByChunks(
        remotePath: String,
        size: Long,
        onChunk: suspend (chunk: ByteArray, totalBytes: Long) -> Result<Unit>,
    ): Result<Boolean>
}

class UnsupportedNetworkClient(
    private val message: String
) : StorageProvider {
    override val capabilities = StorageCapabilities.Unavailable

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
    fun create(network: NetworkConnectionConfig): StorageProvider
}
