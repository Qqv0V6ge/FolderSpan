package com.folderspan.data.main.network

import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.file.PathInfo
import com.folderspan.service.network.StoragePath
import com.folderspan.exception.NetworkUnsupportedException
import com.folderspan.ui.state.main.Task
import kotlinx.coroutines.flow.Flow
import strings.AppStrings

interface NetworkAccess {
    val protocolId: String
    suspend fun getList(directory: FileSimpleInfo, requestId: String? = null, batchId: String? = null): Result<List<FileSimpleInfo>> =
        getList(directory.path, requestId, batchId)

    suspend fun getFile(file: FileSimpleInfo): Result<FileSimpleInfo> = getFile(file.path)

    suspend fun rename(file: FileSimpleInfo, newName: String): Result<Boolean> =
        rename(file.path.substringBeforeLast('/', "/"), file.name, newName)
    suspend fun delete(file: FileSimpleInfo): Result<Boolean> = delete(file.path, file.isDirectory)
    suspend fun deleteEmptyDirectory(file: FileSimpleInfo): Result<Boolean> =
        Result.failure(NetworkUnsupportedException("Deleting only empty directories is unsupported"))
    suspend fun move(file: FileSimpleInfo, to: StoragePath): Result<Boolean> =
        Result.failure(NetworkUnsupportedException("Storage move is unsupported"))
    suspend fun createFolder(path: StoragePath): Result<Boolean> = createFolder(path.path.substringBeforeLast('/', "/").ifEmpty { "/" }, path.path.substringAfterLast('/'))
    suspend fun createFile(path: StoragePath): Result<Boolean> = createFile(path.path.substringBeforeLast('/', "/").ifEmpty { "/" }, path.path.substringAfterLast('/'))
    suspend fun createFolder(directory: FileSimpleInfo, name: String): Result<Boolean> = createFolder(directory.path, name)
    suspend fun createFile(directory: FileSimpleInfo, name: String): Result<Boolean> = createFile(directory.path, name)
    suspend fun uploadFileFromLocal(localPath: String, remotePath: StoragePath, size: Long, onProgress: (Long, Long) -> Unit = { _, _ -> }): Result<Boolean> =
        uploadFileFromLocal(localPath, remotePath.path, size, onProgress)
    suspend fun uploadFromSource(remotePath: StoragePath, size: Long, onProgress: (Long, Long) -> Unit = { _, _ -> }, readChunk: suspend () -> ByteArray?): Result<Boolean> =
        uploadFromSource(remotePath.path, size, onProgress, readChunk)

    suspend fun getList(
        path: String,
        requestId: String? = null,
        batchId: String? = null,
    ): Result<List<FileSimpleInfo>>

    fun traverse(
        path: String,
        requestId: String? = null,
        batchId: String? = null,
    ): Flow<Result<List<FileSimpleInfo>>>

    fun getRootPaths(): List<PathInfo>

    fun getFile(path: String): Result<FileSimpleInfo>

    suspend fun copyTo(
        task: Task,
        srcFileSimpleInfo: FileSimpleInfo,
        destFileSimpleInfo: FileSimpleInfo,
    ): Result<Boolean>

    /**
     * 在同一个网络端点内部复制单个文件。
     *
     * 实现必须委托给远端协议自身的复制能力，不得通过本地临时文件下载后再上传。
     * 协议或服务器明确不支持时，应返回 [NetworkUnsupportedException]，
     * 由上层决定是否使用本地临时文件中转。
     */
    suspend fun copyFileWithinEndpoint(
        task: Task,
        srcFileSimpleInfo: FileSimpleInfo,
        destFileSimpleInfo: FileSimpleInfo,
    ): Result<Boolean> = Result.failure(
        NetworkUnsupportedException(AppStrings.ui_the_current_network_endpoint_does_not_support_service_replication)
    )

    /**
     * 将单个远端文件流式下载到本地路径。
     *
     * 实现必须直接写入 [localPath]，不得先把完整文件聚合为 ByteArray。
     */
    suspend fun downloadFileToLocal(
        file: FileSimpleInfo,
        localPath: String,
        onProgress: (completedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): Result<Boolean>

    /**
     * 将本地暂存文件完整上传并替换远端文件。
     */
    suspend fun uploadFileFromLocal(
        localPath: String,
        remotePath: String,
        size: Long,
        onProgress: (completedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): Result<Boolean>

    /**
     * 按顺序消费源字节并上传到远端路径，不经过 [uploadFileFromLocal]。
     *
     * [size] ≥ 0 时作为进度与 Content-Length；未知长度时传负数。
     */
    suspend fun uploadFromSource(
        remotePath: String,
        size: Long,
        onProgress: (completedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
        readChunk: suspend () -> ByteArray?,
    ): Result<Boolean> = Result.failure(
        NetworkUnsupportedException(AppStrings.network_protocol_write_not_supported)
    )

    suspend fun setModifiedTime(path: String, millis: Long): Result<Boolean> =
        Result.failure(NetworkUnsupportedException("Modification time is not supported"))

    suspend fun rename(path: String, oldName: String, newName: String): Result<Boolean>

    suspend fun delete(path: String, isDirectory: Boolean): Result<Boolean>

    suspend fun createFolder(path: String, name: String): Result<Boolean>

    suspend fun createFile(path: String, name: String): Result<Boolean>

    fun disconnect(): Boolean = true

    suspend fun cancelBatch(batchId: String, reason: String = AppStrings.ui_cancel_list_request): Int = 0
}

internal interface ChunkReadableNetworkAccess {
    suspend fun downloadFileByChunks(
        task: Task,
        srcFileSimpleInfo: FileSimpleInfo,
        onChunk: suspend (chunk: ByteArray, totalBytes: Long) -> Result<Unit>,
    ): Result<Boolean>
}
