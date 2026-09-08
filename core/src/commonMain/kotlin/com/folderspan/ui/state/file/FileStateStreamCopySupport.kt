package com.folderspan.ui.state.file

import strings.AppStrings

import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.main.network.NetworkAccess
import com.folderspan.data.main.share.SYSTEM_SHARE_DESK_ID
import com.folderspan.exception.AuthorityException
import com.folderspan.service.http.archive.FolderSpanArchiveCodec
import com.folderspan.service.network.containsUnsafeNetworkPathSegment
import com.folderspan.service.network.isRemotePathWithinRoot
import com.folderspan.service.network.isUnsafeNetworkPathSegment
import com.folderspan.service.network.unsafeNetworkWritePathError
import com.folderspan.service.operation.TraversalEndpointKind
import com.folderspan.service.operation.collectDirectoryEntriesAdaptive
import com.folderspan.service.operation.resolveTraversalParallelism
import com.folderspan.service.operation.resolveTraversalRuntimeMaxParallelism
import com.folderspan.utils.FileAccessPermission
import com.folderspan.utils.PathUtils
import com.folderspan.utils.SensitiveFileAccessPolicy
import com.folderspan.utils.isPathInsideRootLexically
import kotlinx.coroutines.CancellationException

internal const val STREAM_NETWORK_PROGRESS_BLOCK_SIZE = 1024L * 1024L

internal fun shouldResolveRemoteShareSession(protocolId: String): Boolean {
    return protocolId.isNotBlank() && protocolId != SYSTEM_SHARE_DESK_ID
}

internal fun isUsableLocalUploadPath(path: String): Boolean {
    if (path.isBlank()) return false
    if (path.startsWith("content:", ignoreCase = true)) return false
    return PathUtils.exists(FileAccessPermission.Allowed, path)
}

internal fun joinRemotePath(parent: String, name: String, separator: String): String {
    val normalizedSeparator = separator.ifBlank { "/" }
    val normalizedParent = parent.ifBlank { normalizedSeparator }
    return if (normalizedParent.endsWith(normalizedSeparator)) {
        normalizedParent + name
    } else {
        normalizedParent + normalizedSeparator + name
    }
}

internal fun splitRemoteParentAndName(path: String, separator: String): Pair<String, String> {
    val normalizedSeparator = separator.ifBlank { "/" }
    val trimmed = if (path != normalizedSeparator && path.endsWith(normalizedSeparator)) {
        path.dropLast(normalizedSeparator.length)
    } else {
        path
    }
    val index = trimmed.lastIndexOf(normalizedSeparator)
    if (index < 0) return "" to trimmed
    val parent = trimmed.substring(0, index)
    val name = trimmed.substring(index + normalizedSeparator.length)
    return parent to name
}

internal fun relativeRemotePath(
    sourceRoot: String,
    sourcePath: String,
    destRoot: String,
    sourceSeparator: String,
    destSeparator: String,
): String {
    return resolveRemoteCopyPath(
        sourceRoot = sourceRoot,
        sourcePath = sourcePath,
        destRoot = destRoot,
        sourceSeparator = sourceSeparator,
        destSeparator = destSeparator,
    )
}

internal fun resolveRemoteCopyPath(
    sourceRoot: String,
    sourcePath: String,
    destRoot: String,
    sourceSeparator: String,
    destSeparator: String,
): String {
    val srcSeparator = sourceSeparator.ifBlank { "/" }
    val remoteSeparator = destSeparator.ifBlank { "/" }
    if (containsUnsafeNetworkPathSegment(destRoot)) {
        throw AuthorityException(AppStrings.ui_remote_path_invalid)
    }
    val srcRoot = if (sourceRoot.endsWith(srcSeparator)) sourceRoot else sourceRoot + srcSeparator
    val relativeRaw = sourcePath.removePrefix(srcRoot).replace(srcSeparator, remoteSeparator)
    if (relativeRaw.isBlank() || relativeRaw == sourcePath) {
        return destRoot
    }
    if (containsUnsafeNetworkPathSegment(relativeRaw)) {
        throw AuthorityException(AppStrings.ui_remote_path_invalid)
    }
    val relative = try {
        FolderSpanArchiveCodec.normalizeRelativePath(
            relativeRaw.replace(remoteSeparator, "/").replace('\\', '/').trim('/'),
        )
    } catch (_: IllegalArgumentException) {
        throw AuthorityException(AppStrings.ui_remote_path_invalid)
    }
    val resolved = joinRemotePath(destRoot, relative.replace("/", remoteSeparator), remoteSeparator)
    if (!isRemotePathWithinRoot(destRoot, resolved, remoteSeparator)) {
        throw AuthorityException(AppStrings.ui_download_path_exceeds_the_target_directory)
    }
    return resolved
}

internal fun resolveLocalCopyPath(
    sourceRoot: String,
    sourcePath: String,
    destRoot: String,
    sourceSeparator: String,
): String {
    val srcSeparator = sourceSeparator.ifBlank { PathUtils.getPathSeparator() }
    val localSeparator = PathUtils.getPathSeparator().ifBlank { "/" }
    if (containsUnsafeNetworkPathSegment(destRoot.replace(localSeparator, "/").replace('\\', '/'))) {
        throw AuthorityException(AppStrings.ui_remote_path_invalid)
    }
    val srcRoot = if (sourceRoot.endsWith(srcSeparator)) sourceRoot else sourceRoot + srcSeparator
    val relativeRaw = sourcePath.removePrefix(srcRoot).replace(srcSeparator, "/")
    if (relativeRaw.isBlank() || relativeRaw == sourcePath) {
        throw AuthorityException(AppStrings.ui_remote_path_invalid)
    }
    if (containsUnsafeNetworkPathSegment(relativeRaw)) {
        throw AuthorityException(AppStrings.ui_remote_path_invalid)
    }
    val relative = try {
        FolderSpanArchiveCodec.normalizeRelativePath(
            relativeRaw.replace('\\', '/').trim('/'),
        )
    } catch (_: IllegalArgumentException) {
        throw AuthorityException(AppStrings.ui_remote_path_invalid)
    }
    val resolved = FolderSpanArchiveCodec.buildTargetPath(
        rootPath = destRoot,
        relativePath = relative,
        separator = localSeparator,
    )
    if (!isPathInsideRootLexically(destRoot, resolved, localSeparator)) {
        throw AuthorityException(AppStrings.ui_download_path_exceeds_the_target_directory)
    }
    if (
        PathUtils.exists(FileAccessPermission.Allowed, destRoot) &&
        !PathUtils.isPathWithinRoot(
            FileAccessPermission.Allowed,
            destRoot,
            resolved,
            allowNonExistentLeaf = true,
        )
    ) {
        throw AuthorityException(AppStrings.ui_download_path_exceeds_the_target_directory)
    }
    SensitiveFileAccessPolicy.deniedException(resolved)?.let { error -> throw error }
    return resolved
}

internal fun resolveDirectoryCopyTargetPath(
    sourceRoot: String,
    sourcePath: String,
    destRoot: String,
    sourceSeparator: String,
    destSeparator: String,
    destIsLocal: Boolean,
): String {
    return if (destIsLocal) {
        resolveLocalCopyPath(
            sourceRoot = sourceRoot,
            sourcePath = sourcePath,
            destRoot = destRoot,
            sourceSeparator = sourceSeparator,
        )
    } else {
        resolveRemoteCopyPath(
            sourceRoot = sourceRoot,
            sourcePath = sourcePath,
            destRoot = destRoot,
            sourceSeparator = sourceSeparator,
            destSeparator = destSeparator,
        )
    }
}

internal fun streamNetworkProgressBlocks(totalBytes: Long): Int {
    val size = totalBytes.coerceAtLeast(0L)
    if (size <= 0L) return 1
    return ((size + STREAM_NETWORK_PROGRESS_BLOCK_SIZE - 1L) / STREAM_NETWORK_PROGRESS_BLOCK_SIZE)
        .toInt()
        .coerceAtLeast(1)
}

internal fun streamNetworkProgressCur(doneBytes: Long, totalBytes: Long): Int {
    val total = if (totalBytes > 0L) totalBytes else doneBytes
    if (total <= 0L) return 0
    val doneBlocks = ((doneBytes + STREAM_NETWORK_PROGRESS_BLOCK_SIZE - 1L) / STREAM_NETWORK_PROGRESS_BLOCK_SIZE)
        .toInt()
    return doneBlocks.coerceIn(0, streamNetworkProgressBlocks(total))
}

internal fun sequentialRangeReadChunk(
    size: Long,
    chunkSize: Int,
    ensureRunning: suspend () -> Unit = {},
    readRange: suspend (startOffset: Long, endOffset: Long) -> Result<ByteArray>,
): suspend () -> ByteArray? {
    var offset = 0L
    var finished = false
    val safeChunk = chunkSize.coerceAtLeast(1).toLong()
    return {
        ensureRunning()
        when {
            finished -> null
            size == 0L -> {
                finished = true
                null
            }
            size > 0L && offset >= size -> {
                finished = true
                null
            }
            else -> {
                val end = if (size >= 0L) {
                    minOf(offset + safeChunk, size)
                } else {
                    offset + safeChunk
                }
                val bytes = readRange(offset, end).getOrElse { error ->
                    throw error
                }
                if (bytes.isEmpty()) {
                    finished = true
                    null
                } else {
                    if (size >= 0L && bytes.size.toLong() != end - offset) {
                        throw IllegalStateException(
                            AppStrings.ui_length_of_the_read_data_does_not_match_the_request_range,
                        )
                    }
                    offset += bytes.size
                    if (size >= 0L && offset >= size) {
                        finished = true
                    }
                    bytes
                }
            }
        }
    }
}

internal suspend fun streamUploadFileToNetwork(
    remotePath: String,
    size: Long,
    onProgress: (Long, Long) -> Unit,
    readChunk: suspend () -> ByteArray?,
    uploadFromSource: suspend (
        remotePath: String,
        size: Long,
        onProgress: (Long, Long) -> Unit,
        readChunk: suspend () -> ByteArray?,
    ) -> Result<Boolean>,
    deletePartial: suspend (remotePath: String) -> Unit,
): Result<Boolean> {
    val result = try {
        uploadFromSource(remotePath, size, onProgress, readChunk)
    } catch (cancel: CancellationException) {
        runCatching { deletePartial(remotePath) }
        throw cancel
    } catch (error: Exception) {
        runCatching { deletePartial(remotePath) }
        return Result.failure(error)
    }
    if (result.isFailure || !result.getOrDefault(false)) {
        runCatching { deletePartial(remotePath) }
    }
    return result
}

internal suspend fun streamUploadFileToNetwork(
    networkAccess: NetworkAccess,
    remotePath: String,
    size: Long,
    onProgress: (Long, Long) -> Unit,
    readChunk: suspend () -> ByteArray?,
): Result<Boolean> {
    return streamUploadFileToNetwork(
        remotePath = remotePath,
        size = size,
        onProgress = onProgress,
        readChunk = readChunk,
        uploadFromSource = { path, uploadSize, progress, next ->
            networkAccess.uploadFromSource(path, uploadSize, progress, next)
        },
        deletePartial = { path ->
            networkAccess.delete(path, isDirectory = false)
        },
    )
}

internal suspend fun createRemoteEmptyFile(
    networkAccess: NetworkAccess,
    remotePath: String,
    destSeparator: String,
): Result<Boolean> {
    unsafeNetworkWritePathError(remotePath)?.let { return Result.failure(it) }
    val (parent, name) = splitRemoteParentAndName(remotePath, destSeparator)
    if (name.isBlank() || isUnsafeNetworkPathSegment(name)) {
        return Result.failure(AuthorityException(AppStrings.ui_remote_path_invalid))
    }
    unsafeNetworkWritePathError(parent)?.let { return Result.failure(it) }
    return networkAccess.createFile(parent, name)
}

internal suspend fun createRemoteFolder(
    networkAccess: NetworkAccess,
    remotePath: String,
    destSeparator: String,
): Result<Boolean> {
    unsafeNetworkWritePathError(remotePath)?.let { return Result.failure(it) }
    val (parent, name) = splitRemoteParentAndName(remotePath, destSeparator)
    if (name.isBlank() || isUnsafeNetworkPathSegment(name)) {
        return Result.failure(AuthorityException(AppStrings.ui_remote_path_invalid))
    }
    unsafeNetworkWritePathError(parent)?.let { return Result.failure(it) }
    return networkAccess.createFolder(parent, name)
}

internal suspend fun streamCopyDirectoryToNetwork(
    source: FileSimpleInfo,
    destination: FileSimpleInfo,
    sourceSeparator: String,
    destSeparator: String,
    networkAccess: NetworkAccess,
    ensureRunning: suspend () -> Unit,
    traversalKind: TraversalEndpointKind,
    listChildren: suspend (FileSimpleInfo) -> Result<List<FileSimpleInfo>>,
    copyFile: suspend (sourceFile: FileSimpleInfo, destPath: String) -> Result<Boolean>,
    onScanProgress: suspend (discoveredEntries: Int) -> Unit = {},
    onCreateFolder: suspend (path: String) -> Unit = {},
    onEntryProgress: suspend (processed: Int, total: Int, path: String) -> Unit = { _, _, _ -> },
): Result<Boolean> {
    ensureRunning()
    val traversalEntries = try {
        collectDirectoryEntriesAdaptive(
            root = source,
            config = resolveTraversalParallelism(traversalKind),
            pathSeparator = sourceSeparator.ifBlank { "/" },
            ensureRunning = ensureRunning,
            onScanProgress = { progress ->
                onScanProgress(progress.discoveredEntries)
            },
            dynamicMaxParallelismProvider = {
                resolveTraversalRuntimeMaxParallelism(traversalKind)
            },
            rejectSymbolicLinkEntries = true,
        ) { directory ->
            listChildren(directory)
        }
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (error: Throwable) {
        return Result.failure(error)
    }

    val ordered = traversalEntries.sortedWith(
        compareBy<FileSimpleInfo> { entry -> !entry.isDirectory }
            .thenBy { entry -> entry.path.length },
    )
    for (entry in ordered) {
        resolveDirectoryCopyRemotePath(
            source = source,
            entry = entry,
            destination = destination,
            sourceSeparator = sourceSeparator,
            destSeparator = destSeparator,
        ).getOrElse { error -> return Result.failure(error) }
    }

    onCreateFolder(destination.path)
    val rootCreate = createRemoteFolder(networkAccess, destination.path, destSeparator)
    if (rootCreate.isFailure || !rootCreate.getOrDefault(false)) {
        return Result.failure(rootCreate.exceptionOrNull() ?: Exception(AppStrings.ui_folder_creation_failed))
    }

    val plannedEntries = traversalEntries.size
    if (plannedEntries == 0) {
        onEntryProgress(1, 1, destination.path)
        return Result.success(true)
    }

    var processed = 0
    val total = plannedEntries.coerceAtLeast(1)

    suspend fun markProcessed(path: String) {
        processed++
        onEntryProgress(processed, total, path)
    }

    for (entry in ordered.filter { item -> item.isDirectory }) {
        ensureRunning()
        val remotePath = resolveDirectoryCopyRemotePath(
            source = source,
            entry = entry,
            destination = destination,
            sourceSeparator = sourceSeparator,
            destSeparator = destSeparator,
        ).getOrElse { error -> return Result.failure(error) }
        if (remotePath.isBlank() || remotePath == destination.path) {
            markProcessed(remotePath)
            continue
        }
        onCreateFolder(remotePath)
        val created = createRemoteFolder(networkAccess, remotePath, destSeparator)
        if (created.isFailure || !created.getOrDefault(false)) {
            return Result.failure(created.exceptionOrNull() ?: Exception(AppStrings.ui_folder_creation_failed))
        }
        markProcessed(remotePath)
    }

    for (entry in ordered.filterNot { item -> item.isDirectory }) {
        ensureRunning()
        val remotePath = resolveDirectoryCopyRemotePath(
            source = source,
            entry = entry,
            destination = destination,
            sourceSeparator = sourceSeparator,
            destSeparator = destSeparator,
        ).getOrElse { error -> return Result.failure(error) }
        if (remotePath.isBlank() || remotePath == destination.path) {
            markProcessed(remotePath)
            continue
        }
        val copied = copyFile(entry, remotePath)
        if (copied.isFailure || !copied.getOrDefault(false)) {
            return copied
        }
        markProcessed(remotePath)
    }

    return Result.success(true)
}

private fun resolveDirectoryCopyRemotePath(
    source: FileSimpleInfo,
    entry: FileSimpleInfo,
    destination: FileSimpleInfo,
    sourceSeparator: String,
    destSeparator: String,
): Result<String> {
    return runCatching {
        resolveRemoteCopyPath(
            sourceRoot = source.path,
            sourcePath = entry.path,
            destRoot = destination.path,
            sourceSeparator = sourceSeparator,
            destSeparator = destSeparator,
        )
    }
}
