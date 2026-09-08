package com.folderspan.data.main.network

import com.folderspan.utils.FileAccessPermission
import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.file.PathInfo
import com.folderspan.data.main.DiskMenuPermission
import com.folderspan.data.main.device.DeviceType
import com.folderspan.exception.NetworkUnsupportedException
import com.folderspan.getSocketDevice
import com.folderspan.service.http.client.ShareNetworkRouteClient
import com.folderspan.ui.state.main.Task
import com.folderspan.utils.FileUtils
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.koin.core.component.KoinComponent
import strings.AppStrings

class NetworkShare(
    name: String,
    baseUrl: String,
    password: String = "",
    userAgent: String = buildNetworkShareUserAgent(),
    pathSeparator: String = "/",
    username: String = "",
    pinned: Boolean = false,
) : Network(
    name = name,
    pathSeparator = pathSeparator.ifBlank { "/" },
    protocol = PROTOCOL,
    host = baseUrl.trimEnd('/'),
    username = username,
    password = password,
    pinned = pinned,
), KoinComponent {
    override val menuPermission: DiskMenuPermission = DiskMenuPermission(
        read = true,
        copy = true,
        favorite = false,
        info = true,
    )

    private val routeClient = ShareNetworkRouteClient(
        baseUrl = host,
        password = password,
        userAgent = userAgent
    )

    override val protocolId: String = buildProtocolId()
    val baseUrl: String
        get() = host

    override fun disconnect(): Boolean {
        return routeClient.disconnect()
    }

    override fun getRootPaths(): List<PathInfo> = listOf(PathInfo("/", 0L, 0L))

    override suspend fun getList(
        path: String,
        requestId: String?,
        batchId: String?,
    ): Result<List<FileSimpleInfo>> {
        return routeClient.getList(path, requestId, batchId)
            .map { entries -> tagEntries(entries) }
    }

    override fun traverse(
        path: String,
        requestId: String?,
        batchId: String?,
    ): Flow<Result<List<FileSimpleInfo>>> {
        return routeClient.traversePath(path, requestId, batchId)
            .map { result -> result.map { entries -> tagEntries(entries) } }
    }

    override fun getFile(path: String): Result<FileSimpleInfo> {
        return Result.success(FileSimpleInfo.pathFileSimpleInfo(path))
    }

    override suspend fun copyTo(
        task: Task,
        srcFileSimpleInfo: FileSimpleInfo,
        destFileSimpleInfo: FileSimpleInfo,
    ): Result<Boolean> {
        return routeClient.copyTo(task, srcFileSimpleInfo, destFileSimpleInfo)
    }

    override suspend fun downloadFileToLocal(
        file: FileSimpleInfo,
        localPath: String,
        onProgress: (completedBytes: Long, totalBytes: Long) -> Unit,
    ): Result<Boolean> {
        if (file.isDirectory) {
            return Result.failure(NetworkUnsupportedException(AppStrings.network_directory_editor_source_not_supported))
        }
        if (file.size == 0L) {
            return FileUtils.createFile(FileAccessPermission.Allowed, localPath)
        }
        var completedBytes = 0L
        return routeClient.writeBytes(
            path = file.path,
            requestId = "network-share-editor:${file.protocolId}:${file.path.hashCode()}",
        ) { chunk, offset, contentLength ->
            val totalBytes = contentLength
                ?.takeIf { value -> value >= 0L }
                ?: maxOf(file.size, offset + chunk.size)
            val write = FileUtils.writeBytes(FileAccessPermission.Allowed,
                path = localPath,
                fileSize = totalBytes,
                data = chunk,
                offset = offset,
            )
            if (write.isSuccess && write.getOrDefault(false)) {
                completedBytes = maxOf(completedBytes, offset + chunk.size)
                onProgress(completedBytes, totalBytes)
                Result.success(Unit)
            } else {
                Result.failure(write.exceptionOrNull() ?: Exception(AppStrings.ui_insert_into_editor_cache_failed))
            }
        }
    }

    override suspend fun uploadFileFromLocal(
        localPath: String,
        remotePath: String,
        size: Long,
        onProgress: (completedBytes: Long, totalBytes: Long) -> Unit,
    ): Result<Boolean> = Result.failure(NetworkUnsupportedException(AppStrings.ui_linkshare_does_not_support_writing_files))

    override suspend fun uploadFromSource(
        remotePath: String,
        size: Long,
        onProgress: (completedBytes: Long, totalBytes: Long) -> Unit,
        readChunk: suspend () -> ByteArray?,
    ): Result<Boolean> = Result.failure(NetworkUnsupportedException(AppStrings.ui_linkshare_does_not_support_writing_files))

    override suspend fun downloadFileByChunks(
        task: Task,
        srcFileSimpleInfo: FileSimpleInfo,
        onChunk: suspend (chunk: ByteArray, totalBytes: Long) -> Result<Unit>,
    ): Result<Boolean> {
        if (srcFileSimpleInfo.isDirectory) {
            return Result.failure(NetworkUnsupportedException(AppStrings.network_directory_stream_relay_not_supported))
        }
        return routeClient.writeBytes(
            path = srcFileSimpleInfo.path,
            requestId = "network-share-relay:${task.key}",
        ) { chunk, _, contentLength ->
            onChunk(chunk, contentLength ?: srcFileSimpleInfo.size)
        }
    }

    override suspend fun cancelBatch(batchId: String, reason: String): Int {
        return routeClient.cancelBatch(batchId, reason)
    }

    override suspend fun rename(path: String, oldName: String, newName: String): Result<Boolean> {
        return Result.failure(NetworkUnsupportedException(AppStrings.ui_linkshare_does_not_support_renaming))
    }

    override suspend fun delete(path: String, isDirectory: Boolean): Result<Boolean> {
        return Result.failure(NetworkUnsupportedException(AppStrings.ui_linkshare_does_not_support_deleting))
    }

    override suspend fun createFolder(path: String, name: String): Result<Boolean> {
        return Result.failure(NetworkUnsupportedException(AppStrings.ui_linkshare_does_not_support_creating_folders))
    }

    override suspend fun createFile(path: String, name: String): Result<Boolean> {
        return Result.failure(NetworkUnsupportedException(AppStrings.ui_linkshare_does_not_support_creating_files))
    }

    internal fun tagEntries(entries: List<FileSimpleInfo>): List<FileSimpleInfo> {
        return entries.map { item ->
            item.withCopy(
                protocol = FileProtocol.Network,
                protocolId = protocolId
            )
        }
    }

    companion object {
        const val PROTOCOL = "LinkShare"
    }
}

private fun buildNetworkShareUserAgent(): String {
    val device = getSocketDevice()
    val platformHint = when (device.type) {
        DeviceType.Android -> "Android"
        DeviceType.IOS -> "iPhone"
        DeviceType.JVM -> "ktor"
        DeviceType.JS -> "JavaScript"
    }
    return "FolderSpan/$platformHint (${device.name}; ${device.id})"
}
