package com.folderspan.service.mcp.file

import com.folderspan.data.file.FileProtocol
import com.folderspan.data.main.Local
import com.folderspan.extensions.getFileAndFolder
import com.folderspan.utils.FileAccessPermission
import com.folderspan.utils.FileSensitivity
import com.folderspan.utils.FileUtils
import com.folderspan.utils.PathUtils
import com.folderspan.utils.SensitiveFileAccessPolicy
import com.folderspan.utils.isRestrictedAliasFilesystemPath
import com.folderspan.utils.isWriteRangeWithinFile
import kotlinx.coroutines.Dispatchers
import strings.AppStrings
import kotlinx.coroutines.withContext

class LocalFileEndpointGateway : FileEndpointGateway {
    override val endpoint = FileEndpointRef(FileProtocol.Local)
    override val pathSeparator: String = PathUtils.getPathSeparator()
    override val permissions = FileEndpointPermissions.from(Local().menuPermission)

    override suspend fun list(path: String): Result<List<FileEndpointEntry>> = withContext(Dispatchers.Default) {
        runCatching {
            val resolved = requireOrdinaryPath(path)
            val parent = FileUtils.getFile(FileAccessPermission.Allowed, resolved).getOrThrow()
            rejectUnsafeLocalEntry(parent)
            if (!parent.isDirectory) throw FileEndpointException(FileEndpointErrorCode.InvalidArgument, "path is not a directory")
            resolved.getFileAndFolder(FileAccessPermission.Allowed).getOrThrow().map { child ->
                val classification = SensitiveFileAccessPolicy.classify(child.path)
                if (!classification.isProtected) rejectUnsafeLocalEntry(child)
                child.withCopy(
                    protocol = FileProtocol.Local,
                    protocolId = "",
                    sensitivity = classification.sensitivity,
                    sensitivityCategory = classification.category,
                )
                    .toEndpointEntry(endpoint, permissions)
            }
        }
    }

    override suspend fun info(path: String): Result<FileEndpointEntry> = withContext(Dispatchers.Default) {
        runCatching {
            val resolved = requireOrdinaryPath(path, allowNonExistentLeaf = true)
            val entry = FileUtils.getFile(FileAccessPermission.Allowed, resolved).getOrThrow()
            rejectUnsafeLocalEntry(entry)
            entry.withCopy(protocol = FileProtocol.Local, protocolId = "")
                .toEndpointEntry(endpoint, permissions)
        }
    }

    override suspend fun readRange(path: String, offset: Long, length: Int): Result<FileRangeResult> =
        withContext(Dispatchers.Default) {
            runCatching {
                requireRange(offset, length)
                val resolved = requireOrdinaryPath(path)
                val entry = FileUtils.getFile(FileAccessPermission.Allowed, resolved).getOrThrow()
                rejectUnsafeLocalEntry(entry)
                if (entry.isDirectory) throw FileEndpointException(FileEndpointErrorCode.InvalidArgument, "directory content cannot be read")
                if (offset > entry.size) throw FileEndpointException(FileEndpointErrorCode.InvalidArgument, "offset exceeds file size")
                val end = (offset + length).coerceAtMost(entry.size)
                FileRangeResult(
                    bytes = FileUtils.readFileRange(FileAccessPermission.Allowed, entry.path, offset, end).getOrThrow(),
                    totalSize = entry.size,
                    offset = offset,
                )
            }
        }

    override suspend fun rename(path: String, newName: String): Result<FileEndpointEntry> = withContext(Dispatchers.Default) {
        runCatching {
            validateEndpointLeafName(newName)
            val resolved = requireOrdinaryPath(path)
            val (parent, oldName) = parentAndName(resolved, pathSeparator)
            val destination = requireOrdinaryPath(
                path = joinEndpointPath(parent, newName, pathSeparator),
                allowNonExistentLeaf = true,
            )
            val source = FileUtils.getFile(FileAccessPermission.Allowed, resolved).getOrThrow()
            rejectUnsafeLocalEntry(source)
            FileUtils.rename(FileAccessPermission.Allowed, parent, oldName, newName).getOrThrow()
            info(destination).getOrThrow()
        }
    }

    override suspend fun createDirectory(path: String): Result<FileEndpointEntry> = withContext(Dispatchers.Default) {
        runCatching {
            val resolved = requireOrdinaryPath(path, allowNonExistentLeaf = true)
            rejectUnsafeLocalParent(resolved)
            FileUtils.createFolder(FileAccessPermission.Allowed, resolved).getOrThrow()
            info(resolved).getOrThrow()
        }
    }

    override suspend fun createFile(path: String): Result<FileEndpointEntry> = withContext(Dispatchers.Default) {
        runCatching {
            val resolved = requireOrdinaryPath(path, allowNonExistentLeaf = true)
            rejectUnsafeLocalParent(resolved)
            FileUtils.createFile(FileAccessPermission.Allowed, resolved).getOrThrow()
            info(resolved).getOrThrow()
        }
    }

    override suspend fun delete(path: String): Result<Boolean> = withContext(Dispatchers.Default) {
        runCatching {
            val resolved = requireOrdinaryPath(path)
            parentAndName(resolved, pathSeparator)
            val entry = FileUtils.getFile(FileAccessPermission.Allowed, resolved).getOrThrow()
            rejectUnsafeLocalEntry(entry)
            FileUtils.deleteFile(FileAccessPermission.Allowed, entry.path).getOrThrow()
        }
    }

    override suspend fun writeRange(path: String, fileSize: Long, offset: Long, bytes: ByteArray): Result<Boolean> =
        withContext(Dispatchers.Default) {
            runCatching {
                if (!isWriteRangeWithinFile(fileSize, offset, bytes.size.toLong())) {
                    throw FileEndpointException(FileEndpointErrorCode.InvalidArgument, AppStrings.error_write_range_invalid)
                }
                val resolved = requireOrdinaryPath(path, allowNonExistentLeaf = true)
                rejectUnsafeLocalParent(resolved)
                FileUtils.writeBytes(FileAccessPermission.Allowed, resolved, fileSize, bytes, offset).getOrThrow()
            }
        }

    override suspend fun abortWrite(path: String): Result<Boolean> = withContext(Dispatchers.Default) {
        runCatching {
            val normalized = normalizeEndpointPath(path, pathSeparator)
            denySensitiveOrRestricted(normalized)
            if (PathUtils.isSymbolicLink(FileAccessPermission.Allowed, normalized)) {
                throw FileEndpointException(FileEndpointErrorCode.PermissionDenied, AppStrings.error_symbolic_links_not_accessible)
            }
            val resolved = requireOrdinaryPath(path)
            val entry = FileUtils.getFile(FileAccessPermission.Allowed, resolved).getOrThrow()
            rejectUnsafeLocalEntry(entry)
            FileUtils.deleteFile(FileAccessPermission.Allowed, entry.path).getOrThrow()
        }
    }

    override fun availableBytes(path: String): Long? {
        val resolved = runCatching { requireOrdinaryPath(path, allowNonExistentLeaf = true) }.getOrNull() ?: return null
        return FileUtils.freeSpace(FileAccessPermission.Allowed, resolved).takeIf { it >= 0L }
    }

    private fun rejectUnsafeLocalParent(path: String) {
        val (parent) = parentAndName(path, pathSeparator)
        val resolvedParent = requireOrdinaryPath(parent)
        val parentInfo = FileUtils.getFile(FileAccessPermission.Allowed, resolvedParent).getOrThrow()
        rejectUnsafeLocalEntry(parentInfo)
        if (!parentInfo.isDirectory) throw FileEndpointException(FileEndpointErrorCode.InvalidArgument, "parent is not a directory")
    }

    private fun rejectUnsafeLocalEntry(entry: com.folderspan.data.file.FileSimpleInfo) {
        if (!entry.isSymbolicLinkKnown || entry.isSymbolicLink || PathUtils.isSymbolicLink(FileAccessPermission.Allowed, entry.path)) {
            throw FileEndpointException(FileEndpointErrorCode.PermissionDenied, AppStrings.error_symbolic_links_not_accessible)
        }
    }

    private fun requireOrdinaryPath(path: String, allowNonExistentLeaf: Boolean = false): String {
        val normalized = normalizeEndpointPath(path, pathSeparator)
        denySensitiveOrRestricted(normalized)
        val resolved = PathUtils.resolveCanonicalPath(
            FileAccessPermission.Allowed,
            normalized,
            allowNonExistentLeaf,
        ) ?: throw FileEndpointException(FileEndpointErrorCode.PermissionDenied, "path is not accessible")
        denySensitiveOrRestricted(resolved)
        return resolved
    }

    private fun denySensitiveOrRestricted(path: String) {
        if (isRestrictedAliasFilesystemPath(path, pathSeparator)) {
            throw FileEndpointException(FileEndpointErrorCode.PermissionDenied, "path is not accessible")
        }
        val classification = SensitiveFileAccessPolicy.classify(path)
        if (classification.sensitivity != FileSensitivity.None) {
            throw FileEndpointException(
                FileEndpointErrorCode.PermissionDenied,
                SensitiveFileAccessPolicy.protectedPathMessage(classification),
            )
        }
    }
}

internal fun requireRange(offset: Long, length: Int) {
    if (offset < 0L || length <= 0 || length > MAX_FILE_RANGE_BYTES) {
        throw FileEndpointException(FileEndpointErrorCode.InvalidArgument, "file range is invalid")
    }
}

internal fun joinEndpointPath(parent: String, name: String, separator: String): String =
    parent.trimEnd('/', '\\') + separator + name

const val DEFAULT_FILE_RANGE_BYTES = 256 * 1024
const val MAX_FILE_RANGE_BYTES = 4 * 1024 * 1024
