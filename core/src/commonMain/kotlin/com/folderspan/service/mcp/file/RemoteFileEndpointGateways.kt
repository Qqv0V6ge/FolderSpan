package com.folderspan.service.mcp.file

import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.main.device.Device
import com.folderspan.data.main.network.Network
import com.folderspan.data.main.share.Share
import com.folderspan.service.data.RenameInfo
import com.folderspan.utils.FileAccessPermission
import com.folderspan.utils.FileUtils
import com.folderspan.utils.PathUtils
import com.folderspan.utils.isWriteRangeWithinFile
import strings.AppStrings

class DeviceFileEndpointGateway(
    private val device: Device,
) : FileEndpointGateway {
    override val endpoint = FileEndpointRef(FileProtocol.Device, device.id)
    override val pathSeparator: String = device.pathSeparator
    override val permissions = FileEndpointPermissions.from(device.menuPermission)

    override suspend fun list(path: String): Result<List<FileEndpointEntry>> =
        device.paths.getList(normalizeEndpointPath(path, pathSeparator)).map { entries ->
            entries.map { it.scoped(endpoint).toEndpointEntry(endpoint, permissions) }
        }

    override suspend fun info(path: String): Result<FileEndpointEntry> =
        device.files.get(normalizeEndpointPath(path, pathSeparator)).map { entry ->
            rejectUnknownOrSymbolicLink(entry)
            entry.scoped(endpoint).toEndpointEntry(endpoint, permissions)
        }

    override suspend fun readRange(path: String, offset: Long, length: Int): Result<FileRangeResult> = runCatching {
        requireRange(offset, length)
        val entry = info(path).getOrThrow()
        if (entry.isDirectory) throw FileEndpointException(FileEndpointErrorCode.InvalidArgument, "directory content cannot be read")
        if (offset > entry.size) throw FileEndpointException(FileEndpointErrorCode.InvalidArgument, "offset exceeds file size")
        val end = (offset + length).coerceAtMost(entry.size)
        FileRangeResult(device.files.readBytes(entry.path, offset, end).getOrThrow(), entry.size, offset)
    }

    override suspend fun rename(path: String, newName: String): Result<FileEndpointEntry> = runCatching {
        validateEndpointLeafName(newName)
        val (parent, oldName) = parentAndName(path, pathSeparator)
        val result = device.files.rename(listOf(RenameInfo(parent, oldName, newName))).getOrThrow().singleOrNull()
            ?: throw FileEndpointException(FileEndpointErrorCode.IoError, "rename result is missing")
        result.getOrThrow()
        info(joinEndpointPath(parent, newName, pathSeparator)).getOrThrow()
    }

    override suspend fun createDirectory(path: String): Result<FileEndpointEntry> = runCatching {
        val normalized = normalizeEndpointPath(path, pathSeparator)
        device.files.createFolders(listOf(normalized)).getOrThrow().single().getOrThrow()
        info(normalized).getOrThrow()
    }

    override suspend fun createFile(path: String): Result<FileEndpointEntry> = runCatching {
        val normalized = normalizeEndpointPath(path, pathSeparator)
        device.files.createFiles(listOf(normalized)).getOrThrow().single().getOrThrow()
        info(normalized).getOrThrow()
    }

    override suspend fun delete(path: String): Result<Boolean> = runCatching {
        val normalized = normalizeEndpointPath(path, pathSeparator)
        parentAndName(normalized, pathSeparator)
        device.files.delete(listOf(normalized)).getOrThrow().single().getOrThrow()
    }

    override suspend fun writeRange(path: String, fileSize: Long, offset: Long, bytes: ByteArray): Result<Boolean> {
        if (!isWriteRangeWithinFile(fileSize, offset, bytes.size.toLong())) {
            return Result.failure(
                FileEndpointException(FileEndpointErrorCode.InvalidArgument, AppStrings.error_write_range_invalid),
            )
        }
        return device.files.writeBytes(normalizeEndpointPath(path, pathSeparator), fileSize, bytes, offset)
    }
}

class ShareFileEndpointGateway(
    private val share: Share,
) : FileEndpointGateway {
    override val endpoint = FileEndpointRef(FileProtocol.Share, share.id)
    override val pathSeparator: String = share.pathSeparator.ifBlank { "/" }
    override val permissions: FileEndpointPermissions
        get() = FileEndpointPermissions.from(share.menuPermission)

    override suspend fun list(path: String): Result<List<FileEndpointEntry>> =
        share.getFileList(normalizeEndpointPath(path, pathSeparator)).map { entries ->
            entries.map { it.scoped(endpoint).toEndpointEntry(endpoint, permissions) }
        }

    override suspend fun info(path: String): Result<FileEndpointEntry> = runCatching {
        val normalized = normalizeEndpointPath(path, pathSeparator)
        val (parent, name) = parentAndName(normalized, pathSeparator)
        val entry = share.getFileList(parent).getOrThrow().firstOrNull { it.path == normalized || it.name == name }
            ?: throw FileEndpointException(FileEndpointErrorCode.NotFound, "shared file was not found")
        rejectUnknownOrSymbolicLink(entry)
        entry.scoped(endpoint).toEndpointEntry(endpoint, permissions)
    }

    override suspend fun readRange(path: String, offset: Long, length: Int): Result<FileRangeResult> = runCatching {
        requireRange(offset, length)
        val entry = info(path).getOrThrow()
        if (!permissions.read) throw FileEndpointException(FileEndpointErrorCode.PermissionDenied, "share read capability is disabled")
        if (entry.isDirectory) throw FileEndpointException(FileEndpointErrorCode.InvalidArgument, "directory content cannot be read")
        if (offset > entry.size) throw FileEndpointException(FileEndpointErrorCode.InvalidArgument, "offset exceeds file size")
        val end = (offset + length).coerceAtMost(entry.size)
        FileRangeResult(share.readBytes(entry.path, offset, end).getOrThrow(), entry.size, offset)
    }

    override suspend fun rename(path: String, newName: String): Result<FileEndpointEntry> = runCatching {
        throw FileEndpointException(FileEndpointErrorCode.Unsupported, "share is read-only")
    }

    override suspend fun createDirectory(path: String): Result<FileEndpointEntry> = runCatching {
        throw FileEndpointException(FileEndpointErrorCode.Unsupported, "share is read-only")
    }

    override suspend fun createFile(path: String): Result<FileEndpointEntry> = runCatching {
        throw FileEndpointException(FileEndpointErrorCode.Unsupported, "share is read-only")
    }

    override suspend fun delete(path: String): Result<Boolean> = runCatching {
        throw FileEndpointException(FileEndpointErrorCode.Unsupported, "share is read-only")
    }

    override suspend fun writeRange(
        path: String,
        fileSize: Long,
        offset: Long,
        bytes: ByteArray,
    ): Result<Boolean> = Result.failure(
        FileEndpointException(FileEndpointErrorCode.Unsupported, "share is read-only"),
    )

    override suspend fun abortWrite(path: String): Result<Boolean> = Result.failure(
        FileEndpointException(FileEndpointErrorCode.Unsupported, "share is read-only"),
    )
}

class NetworkFileEndpointGateway(
    private val network: Network,
) : FileEndpointGateway {
    override val endpoint = FileEndpointRef(FileProtocol.Network, network.protocolId)
    override val pathSeparator: String = network.pathSeparator
    override val permissions = FileEndpointPermissions.from(network.menuPermission)

    override suspend fun list(path: String): Result<List<FileEndpointEntry>> =
        network.getList(normalizeEndpointPath(path, pathSeparator)).map { entries ->
            entries.map { it.scoped(endpoint).toEndpointEntry(endpoint, permissions) }
        }

    override suspend fun info(path: String): Result<FileEndpointEntry> = runCatching {
        val normalized = normalizeEndpointPath(path, pathSeparator)
        val (parent, name) = parentAndName(normalized, pathSeparator)
        val entry = network.getList(parent).getOrThrow().firstOrNull { it.path == normalized || it.name == name }
            ?: throw FileEndpointException(FileEndpointErrorCode.NotFound, "network file was not found")
        rejectUnknownOrSymbolicLink(entry)
        entry.scoped(endpoint).toEndpointEntry(endpoint, permissions)
    }

    override suspend fun readRange(path: String, offset: Long, length: Int): Result<FileRangeResult> = runCatching {
        requireRange(offset, length)
        val entry = info(path).getOrThrow()
        if (entry.isDirectory) throw FileEndpointException(FileEndpointErrorCode.InvalidArgument, "directory content cannot be read")
        if (offset > entry.size) throw FileEndpointException(FileEndpointErrorCode.InvalidArgument, "offset exceeds file size")
        val stage = stagingPath(endpoint.sourceId, entry.path)
        try {
            ensureStagingParent(stage)
            network.downloadFileToLocal(entry.toFileSimpleInfo(), stage).getOrThrow()
            val end = (offset + length).coerceAtMost(entry.size)
            FileRangeResult(FileUtils.readFileRange(FileAccessPermission.Allowed, stage, offset, end).getOrThrow(), entry.size, offset)
        } finally {
            FileUtils.deleteFile(FileAccessPermission.Allowed, stage)
        }
    }

    override suspend fun rename(path: String, newName: String): Result<FileEndpointEntry> = runCatching {
        validateEndpointLeafName(newName)
        val (parent, oldName) = parentAndName(path, pathSeparator)
        network.rename(parent, oldName, newName).getOrThrow()
        info(joinEndpointPath(parent, newName, pathSeparator)).getOrThrow()
    }

    override suspend fun createDirectory(path: String): Result<FileEndpointEntry> = runCatching {
        val normalized = normalizeEndpointPath(path, pathSeparator)
        val (parent, name) = parentAndName(normalized, pathSeparator)
        network.createFolder(parent, name).getOrThrow()
        info(normalized).getOrThrow()
    }

    override suspend fun createFile(path: String): Result<FileEndpointEntry> = runCatching {
        val normalized = normalizeEndpointPath(path, pathSeparator)
        val (parent, name) = parentAndName(normalized, pathSeparator)
        network.createFile(parent, name).getOrThrow()
        info(normalized).getOrThrow()
    }

    override suspend fun delete(path: String): Result<Boolean> = runCatching {
        val entry = info(path).getOrThrow()
        network.delete(entry.path, entry.isDirectory).getOrThrow()
    }

    override suspend fun writeRange(path: String, fileSize: Long, offset: Long, bytes: ByteArray): Result<Boolean> = runCatching {
        if (!isWriteRangeWithinFile(fileSize, offset, bytes.size.toLong())) {
            throw FileEndpointException(FileEndpointErrorCode.InvalidArgument, AppStrings.error_write_range_invalid)
        }
        val normalized = normalizeEndpointPath(path, pathSeparator)
        val stage = stagingPath(endpoint.sourceId, normalized)
        val completesFile = bytes.size.toLong() == fileSize - offset
        try {
            ensureStagingParent(stage)
            val stageExists = FileUtils.getFile(FileAccessPermission.Allowed, stage).isSuccess
            if (offset == 0L && stageExists) {
                FileUtils.deleteFile(FileAccessPermission.Allowed, stage).getOrThrow()
            } else if (offset > 0L && !stageExists) {
                val current = info(normalized).getOrThrow()
                network.downloadFileToLocal(current.toFileSimpleInfo(), stage).getOrThrow()
            }
            FileUtils.writeBytes(FileAccessPermission.Allowed, stage, fileSize, bytes, offset).getOrThrow()
            if (completesFile) {
                network.uploadFileFromLocal(stage, normalized, fileSize).getOrThrow()
            }
            true
        } finally {
            if (completesFile) FileUtils.deleteFile(FileAccessPermission.Allowed, stage)
        }
    }

    override suspend fun abortWrite(path: String): Result<Boolean> = runCatching {
        val stage = stagingPath(endpoint.sourceId, normalizeEndpointPath(path, pathSeparator))
        val entry = FileUtils.getFile(FileAccessPermission.Allowed, stage).getOrThrow()
        FileUtils.deleteFile(FileAccessPermission.Allowed, entry.path).getOrThrow()
    }
}

private fun FileSimpleInfo.scoped(endpoint: FileEndpointRef): FileSimpleInfo =
    withCopy(protocol = endpoint.protocol, protocolId = endpoint.sourceId)

private fun rejectUnknownOrSymbolicLink(entry: FileSimpleInfo) {
    if (!entry.isSymbolicLinkKnown || entry.isSymbolicLink) {
        throw FileEndpointException(FileEndpointErrorCode.PermissionDenied, "endpoint did not provide safe symbolic-link metadata")
    }
}

private fun FileEndpointEntry.toFileSimpleInfo(): FileSimpleInfo = FileSimpleInfo(
    name = name,
    isDirectory = isDirectory,
    isHidden = isHidden,
    path = path,
    mineType = mimeType,
    size = size,
    createdDate = createdAt,
    updatedDate = updatedAt,
    protocol = endpoint.protocol,
    protocolId = endpoint.sourceId,
    isSymbolicLink = isSymbolicLink,
    isSymbolicLinkKnown = isSymbolicLinkKnown,
    sensitivity = sensitivity,
    sensitivityCategory = sensitivityCategory,
)

private fun stagingPath(sourceId: String, path: String): String {
    val separator = PathUtils.getPathSeparator()
    val suffix = (31 * sourceId.hashCode() + path.hashCode()).toUInt().toString(16)
    return PathUtils.getCachePath().trimEnd('/', '\\') + separator + "mcp-file-staging" + separator + "$suffix.tmp"
}

private fun ensureStagingParent(path: String) {
    val separator = PathUtils.getPathSeparator()
    val parent = path.substringBeforeLast(separator)
    if (FileUtils.getFile(FileAccessPermission.Allowed, parent).isFailure) {
        FileUtils.createFolder(FileAccessPermission.Allowed, parent).getOrThrow()
    }
}
