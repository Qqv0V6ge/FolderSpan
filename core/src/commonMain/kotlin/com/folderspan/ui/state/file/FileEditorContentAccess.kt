package com.folderspan.ui.state.file

import strings.AppStrings

import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.main.DiskBase
import com.folderspan.data.main.device.Device
import com.folderspan.data.main.network.NetworkAccess
import com.folderspan.data.main.share.Share
import com.folderspan.editor.FileEditorContentSource
import com.folderspan.editor.FileEditorSourceCapabilities
import com.folderspan.editor.FileEditorSourceChangedException
import com.folderspan.editor.FileEditorSourceSnapshot
import com.folderspan.editor.FileEditorUnsupportedOperationException
import com.folderspan.service.data.RenameInfo
import com.folderspan.utils.FileAccessPermission
import com.folderspan.utils.FileUtils
import com.folderspan.utils.PathUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random
import kotlin.time.Clock

private const val FILE_EDITOR_CACHE_DIRECTORY = "editor-content"
private const val FILE_EDITOR_MAX_READ_RANGE_BYTES = 4L * 1024 * 1024
private const val FILE_EDITOR_DEVICE_WRITE_CHUNK_BYTES = 4 * 1024 * 1024
private const val FILE_EDITOR_CACHE_MAX_ENTRIES = 16
private const val FILE_EDITOR_CACHE_MAX_AGE_MILLIS = 24L * 60L * 60L * 1_000L

internal suspend fun createFileEditorContentSource(
    file: FileSimpleInfo,
    requestedCanWrite: Boolean,
    device: Device? = null,
    share: Share? = null,
    networkAccess: NetworkAccess? = null,
): Result<FileEditorContentSource> {
    if (file.isDirectory) {
        return Result.failure(IllegalArgumentException(AppStrings.ui_directory_cannot_be_used_as_an_editor_content_source))
    }
    if (file.size < 0L) {
        return Result.failure(IllegalArgumentException(AppStrings.ui_file_size_invalid))
    }

    return when (file.protocol) {
        FileProtocol.Local -> Result.success(
            localEditorContentSource(
                path = file.path,
                initialSize = file.size,
                canWrite = requestedCanWrite,
            )
        )

        FileProtocol.Device -> {
            val resolvedDevice = device
                ?: return Result.failure(IllegalStateException(AppStrings.message_task_device_disconnected))
            Result.success(
                deviceEditorContentSource(
                    file = file,
                    device = resolvedDevice,
                    canWrite = requestedCanWrite,
                )
            )
        }

        FileProtocol.Share -> {
            val resolvedShare = share
                ?: return Result.failure(IllegalStateException(AppStrings.message_task_share_session_expired))
            Result.success(
                LambdaFileEditorContentSource(
                    initialSize = file.size,
                    canWrite = false,
                    capabilities = FileEditorSourceCapabilities(
                        supportsSaveAs = true,
                    ),
                    snapshotBlock = { Result.success(file.toEditorSourceSnapshot()) },
                    readBlock = { start, end ->
                        resolvedShare.readBytes(file.path, start, end)
                    },
                    replaceBlock = { _, _, _ ->
                        Result.failure(IllegalStateException(AppStrings.ui_shared_files_as_read_only))
                    },
                )
            )
        }

        FileProtocol.Network -> {
            val resolvedAccess = networkAccess
                ?: return Result.failure(IllegalStateException(AppStrings.ui_the_network_storage_connection_has_failed))
            openCachedNetworkEditorContentSource(
                file = file,
                networkAccess = resolvedAccess,
                requestedCanWrite = requestedCanWrite,
            )
        }
    }
}

private fun localEditorContentSource(
    path: String,
    initialSize: Long,
    canWrite: Boolean,
): FileEditorContentSource = LambdaFileEditorContentSource(
    initialSize = initialSize,
    canWrite = canWrite,
    localPathProvider = { path },
    capabilities = FileEditorSourceCapabilities(
        supportsRangeWrite = canWrite,
        supportsStreamedReplace = canWrite,
        supportsSaveAs = true,
        supportsAtomicReplace = canWrite,
    ),
    snapshotBlock = {
        withContext(Dispatchers.Default) {
            FileUtils.getFile(FileAccessPermission.Allowed, path)
                .map(FileSimpleInfo::toEditorSourceSnapshot)
        }
    },
    readBlock = { start, end -> readLocalRange(path, start, end) },
    writeRangeBlock = { currentSize, start, data ->
        withContext(Dispatchers.Default) {
            FileUtils.writeBytes(FileAccessPermission.Allowed, path, currentSize, data, start).mapCatching { success ->
                if (!success) throw IllegalStateException(AppStrings.ui_write_the_file_range_successfully)
            }
        }
    },
    replaceBlock = { newSize, content, onProgress ->
        replaceLocalFileAtomically(
            path = path,
            newSize = newSize,
            content = content,
            onProgress = onProgress,
        )
    },
)

private fun deviceEditorContentSource(
    file: FileSimpleInfo,
    device: Device,
    canWrite: Boolean,
): FileEditorContentSource = LambdaFileEditorContentSource(
    initialSize = file.size,
    canWrite = canWrite,
    capabilities = FileEditorSourceCapabilities(
        supportsRangeWrite = canWrite,
        supportsStreamedReplace = canWrite,
        supportsSaveAs = true,
        supportsAtomicReplace = canWrite,
    ),
    snapshotBlock = {
        device.files.get(file.path).map(FileSimpleInfo::toEditorSourceSnapshot)
    },
    readBlock = { start, end -> device.files.readBytes(file.path, start, end) },
    writeRangeBlock = { currentSize, start, data ->
        device.files.writeBytes(
            path = file.path,
            fileSize = currentSize,
            data = data,
            offset = start,
        ).mapCatching { success ->
            if (!success) throw IllegalStateException(AppStrings.ui_insertion_of_device_file_range_failed)
        }
    },
    replaceBlock = { newSize, content, onProgress ->
        replaceDeviceFileAtomically(
            device = device,
            path = file.path,
            newSize = newSize,
            content = content,
            onProgress = onProgress,
        )
    },
)

private suspend fun openCachedNetworkEditorContentSource(
    file: FileSimpleInfo,
    networkAccess: NetworkAccess,
    requestedCanWrite: Boolean,
): Result<FileEditorContentSource> {
    cleanupStaleEditorContentCaches()
    val initialCachePath = buildEditorCachePath(file.name)
    val prepared = try {
        networkAccess.downloadFileToLocal(
            file = file,
            localPath = initialCachePath,
        )
    } catch (error: Throwable) {
        deleteLocalFileBestEffort(initialCachePath)
        throw error
    }
    if (prepared.isFailure || !prepared.getOrDefault(false)) {
        deleteLocalFileBestEffort(initialCachePath)
        return Result.failure(prepared.exceptionOrNull() ?: Exception(AppStrings.ui_download_editor_cache_failed))
    }

    val actualSize = withContext(Dispatchers.Default) {
        FileUtils.getFile(FileAccessPermission.Allowed, initialCachePath).map { cached -> cached.size }
    }.getOrElse { error ->
        deleteLocalFileBestEffort(initialCachePath)
        return Result.failure(error)
    }
    var activeCachePath = initialCachePath
    val endpointCanWrite = (networkAccess as? DiskBase)?.menuPermission?.write == true

    return Result.success(
        LambdaFileEditorContentSource(
            initialSize = actualSize,
            canWrite = requestedCanWrite && endpointCanWrite,
            localPathProvider = { activeCachePath },
            capabilities = FileEditorSourceCapabilities(
                supportsStreamedReplace = requestedCanWrite && endpointCanWrite,
                supportsSaveAs = true,
            ),
            snapshotBlock = {
                withContext(Dispatchers.Default) {
                    networkAccess.getFile(file.path).map(FileSimpleInfo::toEditorSourceSnapshot)
                }
            },
            readBlock = { start, end -> readLocalRange(activeCachePath, start, end) },
            replaceBlock = replace@{ newSize, content, onProgress ->
                val stagedPath = buildEditorCachePath(file.name, suffix = "save")
                val staged = writeFlowToLocalFile(
                    path = stagedPath,
                    newSize = newSize,
                    content = content,
                    onProgress = { _, _ -> },
                )
                if (staged.isFailure) {
                    deleteLocalFileBestEffort(stagedPath)
                    return@replace staged
                }

                val uploaded = uploadCachedEditorContent(
                    networkAccess = networkAccess,
                    localPath = stagedPath,
                    remotePath = file.path,
                    size = newSize,
                    onProgress = onProgress,
                )
                if (uploaded.isFailure || !uploaded.getOrDefault(false)) {
                    deleteLocalFileBestEffort(stagedPath)
                    return@replace Result.failure(
                        uploaded.exceptionOrNull() ?: Exception(AppStrings.ui_upload_editing_content_failed)
                    )
                }

                val previousPath = activeCachePath
                activeCachePath = stagedPath
                deleteLocalFileBestEffort(previousPath)
                onProgress(newSize, newSize)
                Result.success(Unit)
            },
            closeBlock = {
                deleteLocalFileBestEffort(activeCachePath)
            },
        )
    )
}

private suspend fun uploadCachedEditorContent(
    networkAccess: NetworkAccess,
    localPath: String,
    remotePath: String,
    size: Long,
    onProgress: suspend (writtenBytes: Long, totalBytes: Long) -> Unit,
): Result<Boolean> = coroutineScope {
    val progressUpdates = Channel<Pair<Long, Long>>(Channel.CONFLATED)
    val progressCollector = launch {
        for ((completed, total) in progressUpdates) {
            onProgress(
                completed.coerceIn(0L, size),
                total.coerceAtLeast(size),
            )
        }
    }
    val result = try {
        networkAccess.uploadFileFromLocal(
            localPath = localPath,
            remotePath = remotePath,
            size = size,
            onProgress = { completed, total ->
                progressUpdates.trySend(completed to total)
            },
        )
    } finally {
        progressUpdates.close()
    }
    progressCollector.join()
    result
}

private class LambdaFileEditorContentSource(
    initialSize: Long,
    override val canWrite: Boolean,
    private val localPathProvider: (() -> String?)? = null,
    override val capabilities: FileEditorSourceCapabilities,
    private val snapshotBlock: suspend () -> Result<FileEditorSourceSnapshot>,
    private val readBlock: suspend (startOffset: Long, endOffsetExclusive: Long) -> Result<ByteArray>,
    private val writeRangeBlock: (suspend (
        currentSize: Long,
        startOffset: Long,
        data: ByteArray,
    ) -> Result<Unit>)? = null,
    private val replaceBlock: suspend (
        newSize: Long,
        content: Flow<ByteArray>,
        onProgress: suspend (writtenBytes: Long, totalBytes: Long) -> Unit,
    ) -> Result<Unit>,
    private val closeBlock: suspend () -> Unit = {},
) : FileEditorContentSource {
    override var size: Long = initialSize
        private set

    override val localPath: String?
        get() = localPathProvider?.invoke()

    override suspend fun currentSnapshot(): Result<FileEditorSourceSnapshot> =
        snapshotBlock().mapCatching { snapshot ->
            if (snapshot.size < 0L) throw IllegalStateException(AppStrings.ui_content_snapshot_size_invalid)
            snapshot
        }

    override suspend fun readRange(
        startOffset: Long,
        endOffsetExclusive: Long,
    ): Result<ByteArray> {
        if (startOffset < 0L || endOffsetExclusive < startOffset || endOffsetExclusive > size) {
            return Result.failure(IllegalArgumentException(AppStrings.ui_read_range_invalid))
        }
        if (endOffsetExclusive - startOffset > FILE_EDITOR_MAX_READ_RANGE_BYTES) {
            return Result.failure(IllegalArgumentException(AppStrings.ui_once_the_read_range_cannot_exceed_4_mib))
        }
        return readBlock(startOffset, endOffsetExclusive).mapCatching { bytes ->
            val expectedSize = endOffsetExclusive - startOffset
            if (bytes.size.toLong() != expectedSize) {
                throw IllegalStateException(AppStrings.ui_read_range_length_mismatch)
            }
            bytes
        }
    }

    override suspend fun replaceContent(
        newSize: Long,
        content: Flow<ByteArray>,
        expectedSnapshot: FileEditorSourceSnapshot?,
        onProgress: suspend (writtenBytes: Long, totalBytes: Long) -> Unit,
    ): Result<Unit> {
        if (!canWrite || !capabilities.supportsStreamedReplace) {
            return Result.failure(IllegalStateException(AppStrings.ui_file_is_read_only))
        }
        if (newSize < 0L) {
            return Result.failure(IllegalArgumentException(AppStrings.ui_file_size_invalid))
        }
        val validation = validateSnapshot(expectedSnapshot)
        if (validation.isFailure) {
            return Result.failure(validation.exceptionOrNull() ?: IllegalStateException(AppStrings.editor_content_source_validation_failed))
        }
        return replaceBlock(newSize, content, onProgress).onSuccess {
            size = newSize
        }
    }

    override suspend fun writeRange(
        startOffset: Long,
        data: ByteArray,
        expectedSnapshot: FileEditorSourceSnapshot?,
        onProgress: suspend (writtenBytes: Long, totalBytes: Long) -> Unit,
    ): Result<Unit> {
        if (!canWrite || !capabilities.supportsRangeWrite) {
            return Result.failure(FileEditorUnsupportedOperationException(AppStrings.ui_long_range_writing))
        }
        if (startOffset < 0L || startOffset + data.size.toLong() > size) {
            return Result.failure(IllegalArgumentException(AppStrings.error_write_range_invalid))
        }
        val validation = validateSnapshot(expectedSnapshot)
        if (validation.isFailure) {
            return Result.failure(validation.exceptionOrNull() ?: IllegalStateException(AppStrings.editor_content_source_validation_failed))
        }
        val writer = writeRangeBlock
            ?: return Result.failure(FileEditorUnsupportedOperationException(AppStrings.ui_long_range_writing))
        val result = writer(size, startOffset, data.copyOf())
        if (result.isSuccess) {
            onProgress(data.size.toLong(), data.size.toLong())
        }
        return result
    }

    private suspend fun validateSnapshot(
        expectedSnapshot: FileEditorSourceSnapshot?,
    ): Result<Unit> {
        if (expectedSnapshot == null) return Result.success(Unit)
        return currentSnapshot().mapCatching { actual ->
            if (actual != expectedSnapshot) {
                throw FileEditorSourceChangedException(expectedSnapshot, actual)
            }
        }
    }

    override suspend fun close() {
        closeBlock()
    }
}

private fun FileSimpleInfo.toEditorSourceSnapshot(): FileEditorSourceSnapshot =
    FileEditorSourceSnapshot(
        size = size,
        updatedAt = updatedDate.takeIf { it > 0L },
        revision = "$size:$updatedDate",
    )

private suspend fun readLocalRange(
    path: String,
    startOffset: Long,
    endOffsetExclusive: Long,
): Result<ByteArray> {
    if (startOffset == endOffsetExclusive) return Result.success(byteArrayOf())
    val requestedSize = endOffsetExclusive - startOffset
    return FileUtils.readFileRangeChunks(
        permission = FileAccessPermission.Allowed,
        path = path,
        start = startOffset,
        end = endOffsetExclusive,
        chunkSize = requestedSize,
    ).firstOrNull()
        ?.map { (_, bytes) -> bytes }
        ?: Result.failure(IllegalStateException(AppStrings.ui_read_range_not_returned_data))
}

private suspend fun replaceLocalFileAtomically(
    path: String,
    newSize: Long,
    content: Flow<ByteArray>,
    onProgress: suspend (writtenBytes: Long, totalBytes: Long) -> Unit,
): Result<Unit> {
    val parts = splitEditorPath(path, PathUtils.getPathSeparator())
        ?: return Result.failure(IllegalArgumentException(AppStrings.ui_file_path_invalid))
    val token = editorPathToken()
    val stagedName = buildTemporaryEditorName(parts.name, token, "stage")
    val backupName = buildTemporaryEditorName(parts.name, token, "backup")
    val stagedPath = joinEditorPath(parts.parent, stagedName, parts.separator)
    val backupPath = joinEditorPath(parts.parent, backupName, parts.separator)

    val staged = writeFlowToLocalFile(stagedPath, newSize, content, onProgress)
    if (staged.isFailure) {
        deleteLocalFileBestEffort(stagedPath)
        return staged
    }

    val backup = withContext(Dispatchers.Default) {
        FileUtils.rename(FileAccessPermission.Allowed, parts.parent, parts.name, backupName)
    }
    if (backup.isFailure || !backup.getOrDefault(false)) {
        deleteLocalFileBestEffort(stagedPath)
        return Result.failure(backup.exceptionOrNull() ?: Exception(AppStrings.ui_create_file_backup_failed))
    }

    val activate = withContext(Dispatchers.Default) {
        FileUtils.rename(FileAccessPermission.Allowed, parts.parent, stagedName, parts.name)
    }
    if (activate.isFailure || !activate.getOrDefault(false)) {
        withContext(Dispatchers.Default) {
            FileUtils.rename(FileAccessPermission.Allowed, parts.parent, backupName, parts.name)
        }
        deleteLocalFileBestEffort(stagedPath)
        return Result.failure(activate.exceptionOrNull() ?: Exception(AppStrings.ui_replace_file_failed))
    }

    deleteLocalFileBestEffort(backupPath)
    return Result.success(Unit)
}

private suspend fun replaceDeviceFileAtomically(
    device: Device,
    path: String,
    newSize: Long,
    content: Flow<ByteArray>,
    onProgress: suspend (writtenBytes: Long, totalBytes: Long) -> Unit,
): Result<Unit> {
    val parts = splitEditorPath(path, device.pathSeparator.ifBlank { "/" })
        ?: return Result.failure(IllegalArgumentException(AppStrings.ui_file_path_invalid))
    val token = editorPathToken()
    val stagedName = buildTemporaryEditorName(parts.name, token, "stage")
    val backupName = buildTemporaryEditorName(parts.name, token, "backup")
    val stagedPath = joinEditorPath(parts.parent, stagedName, parts.separator)
    val backupPath = joinEditorPath(parts.parent, backupName, parts.separator)

    val staged = writeFlowToDeviceFile(
        device = device,
        path = stagedPath,
        newSize = newSize,
        content = content,
        onProgress = onProgress,
    )
    if (staged.isFailure) {
        deleteDeviceFileBestEffort(device, stagedPath)
        return staged
    }

    val backup = firstDeviceOperation(
        device.files.rename(listOf(RenameInfo(parts.parent, parts.name, backupName))),
        AppStrings.ui_failed_create_remote_file_backup,
    )
    if (backup.isFailure) {
        deleteDeviceFileBestEffort(device, stagedPath)
        return backup
    }

    val activate = firstDeviceOperation(
        device.files.rename(listOf(RenameInfo(parts.parent, stagedName, parts.name))),
        AppStrings.ui_failed_replace_remote_file,
    )
    if (activate.isFailure) {
        firstDeviceOperation(
            device.files.rename(listOf(RenameInfo(parts.parent, backupName, parts.name))),
            AppStrings.ui_failed_restore_remote_files,
        )
        deleteDeviceFileBestEffort(device, stagedPath)
        return activate
    }

    deleteDeviceFileBestEffort(device, backupPath)
    return Result.success(Unit)
}

private suspend fun writeFlowToLocalFile(
    path: String,
    newSize: Long,
    content: Flow<ByteArray>,
    onProgress: suspend (writtenBytes: Long, totalBytes: Long) -> Unit,
): Result<Unit> {
    if (newSize == 0L) {
        val validation = validateEmptyContent(content)
        if (validation.isFailure) return validation
        val created = withContext(Dispatchers.Default) {
            if (PathUtils.exists(FileAccessPermission.Allowed, path)) {
                val deleted = FileUtils.deleteFile(FileAccessPermission.Allowed, path)
                if (deleted.isFailure || !deleted.getOrDefault(false)) {
                    return@withContext Result.failure(
                        deleted.exceptionOrNull() ?: Exception(AppStrings.ui_clear_file_failed)
                    )
                }
            }
            FileUtils.createFile(FileAccessPermission.Allowed, path)
                .flatMapBoolean(AppStrings.ui_failed_create_empty_file)
        }
        if (created.isSuccess) {
            onProgress(0L, 0L)
        }
        return created
    }

    var sourceBytes = 0L
    val ranges = flow {
        content.collect { bytes ->
            if (bytes.isEmpty()) return@collect
            val nextOffset = sourceBytes + bytes.size
            if (nextOffset > newSize) {
                throw IllegalArgumentException(AppStrings.ui_edit_content_over_the_declared_size)
            }
            emit(sourceBytes to bytes)
            sourceBytes = nextOffset
        }
        if (sourceBytes != newSize) {
            throw IllegalStateException(AppStrings.ui_adjust_the_length_of_the_content_to_match_the_size_of_the_declaration)
        }
    }
    return FileUtils.writeByteRanges(
        permission = FileAccessPermission.Allowed,
        path = path,
        fileSize = newSize,
        ranges = ranges,
        onRangeWritten = { offset, bytesWritten ->
            onProgress(offset + bytesWritten, newSize)
        },
    ).flatMapBoolean(AppStrings.ui_writing_file_failed)
}

private suspend fun writeFlowToDeviceFile(
    device: Device,
    path: String,
    newSize: Long,
    content: Flow<ByteArray>,
    onProgress: suspend (writtenBytes: Long, totalBytes: Long) -> Unit,
): Result<Unit> {
    if (newSize == 0L) {
        val validation = validateEmptyContent(content)
        if (validation.isFailure) return validation
        val created = firstDeviceOperation(device.files.createFiles(listOf(path)), AppStrings.ui_failed_create_remote_empty_file)
        if (created.isSuccess) {
            onProgress(0L, 0L)
        }
        return created
    }

    return try {
        var offset = 0L
        var blockIndex = 0L
        content.collect { bytes ->
            if (bytes.isEmpty()) return@collect
            if (offset + bytes.size > newSize) {
                throw IllegalArgumentException(AppStrings.ui_edit_content_over_the_declared_size)
            }
            var chunkStart = 0
            while (chunkStart < bytes.size) {
                val chunkEnd = minOf(chunkStart + FILE_EDITOR_DEVICE_WRITE_CHUNK_BYTES, bytes.size)
                val chunk = if (chunkStart == 0 && chunkEnd == bytes.size) {
                    bytes
                } else {
                    bytes.copyOfRange(chunkStart, chunkEnd)
                }
                val written = device.files.writeBytes(
                    path = path,
                    fileSize = newSize,
                    data = chunk,
                    offset = offset,
                    blockIndex = blockIndex,
                )
                if (written.isFailure || !written.getOrDefault(false)) {
                    throw (written.exceptionOrNull() ?: Exception(AppStrings.ui_write_to_remote_file_failed))
                }
                offset += chunk.size
                blockIndex += 1L
                chunkStart = chunkEnd
                onProgress(offset, newSize)
            }
        }
        if (offset != newSize) {
            throw IllegalStateException(AppStrings.ui_adjust_the_length_of_the_content_to_match_the_size_of_the_declaration)
        }
        Result.success(Unit)
    } catch (error: Throwable) {
        Result.failure(error)
    }
}

private suspend fun validateEmptyContent(content: Flow<ByteArray>): Result<Unit> = try {
    content.collect { bytes ->
        if (bytes.isNotEmpty()) {
            throw IllegalArgumentException(AppStrings.ui_empty_file_cannot_contain_data)
        }
    }
    Result.success(Unit)
} catch (error: Throwable) {
    Result.failure(error)
}

private fun Result<Boolean>.flatMapBoolean(failureMessage: String): Result<Unit> = fold(
    onSuccess = { success ->
        if (success) Result.success(Unit) else Result.failure(Exception(failureMessage))
    },
    onFailure = { error -> Result.failure(error) },
)

private fun firstDeviceOperation(
    operation: Result<List<Result<Boolean>>>,
    failureMessage: String,
): Result<Unit> {
    val results = operation.getOrElse { error -> return Result.failure(error) }
    val first = results.firstOrNull() ?: return Result.failure(Exception(failureMessage))
    return first.flatMapBoolean(failureMessage)
}

private suspend fun deleteDeviceFileBestEffort(device: Device, path: String) {
    runCatching { device.files.delete(listOf(path)) }
}

private suspend fun deleteLocalFileBestEffort(path: String) {
    withContext(NonCancellable + Dispatchers.Default) {
        runCatching {
            if (PathUtils.exists(FileAccessPermission.Allowed, path)) {
                FileUtils.deleteFile(FileAccessPermission.Allowed, path)
            }
        }
    }
}

private suspend fun cleanupStaleEditorContentCaches(activePaths: Set<String> = emptySet()) {
    withContext(Dispatchers.Default) {
        val separator = PathUtils.getPathSeparator()
        val cacheRoot = PathUtils.getCachePath().trimEnd('/', '\\')
        val directory = joinEditorPath(cacheRoot, FILE_EDITOR_CACHE_DIRECTORY, separator)
        if (!PathUtils.exists(FileAccessPermission.Allowed, directory)) return@withContext
        val entries = PathUtils.getFileAndFolder(FileAccessPermission.Allowed, directory)
            .getOrDefault(emptyList())
        selectStaleEditorCachePaths(
            entries = entries,
            activePaths = activePaths,
            nowMillis = Clock.System.now().toEpochMilliseconds(),
        ).forEach { path ->
            runCatching { FileUtils.deleteFile(FileAccessPermission.Allowed, path) }
        }
    }
}

internal fun selectStaleEditorCachePaths(
    entries: List<FileSimpleInfo>,
    activePaths: Set<String>,
    nowMillis: Long,
    maxAgeMillis: Long = FILE_EDITOR_CACHE_MAX_AGE_MILLIS,
    maxEntries: Int = FILE_EDITOR_CACHE_MAX_ENTRIES,
): List<String> {
    require(maxAgeMillis >= 0L)
    require(maxEntries >= 0)
    return entries
        .asSequence()
        .filter { !it.isDirectory && it.path !in activePaths }
        .sortedByDescending(FileSimpleInfo::updatedDate)
        .mapIndexedNotNull { index, entry ->
            val stale = entry.updatedDate <= 0L || nowMillis - entry.updatedDate > maxAgeMillis
            entry.path.takeIf { stale || index >= maxEntries }
        }
        .toList()
}

private fun buildEditorCachePath(name: String, suffix: String = "source"): String {
    val separator = PathUtils.getPathSeparator()
    val cacheRoot = PathUtils.getCachePath().trimEnd('/', '\\')
    val directory = joinEditorPath(cacheRoot, FILE_EDITOR_CACHE_DIRECTORY, separator)
    PathUtils.createDirectoryIfNotExists(FileAccessPermission.Allowed, directory)
    val safeName = sanitizeEditorFileName(name.ifBlank { "file" })
    return joinEditorPath(
        directory,
        "$safeName.${editorPathToken()}.$suffix",
        separator,
    )
}

private data class EditorPathParts(
    val parent: String,
    val name: String,
    val separator: String,
)

private fun splitEditorPath(path: String, separator: String): EditorPathParts? {
    if (path.isBlank() || separator.isBlank()) return null
    val index = path.lastIndexOf(separator)
    val name = if (index >= 0) path.substring(index + separator.length) else path
    if (name.isBlank()) return null
    val parent = when {
        index < 0 -> ""
        index == 0 -> separator
        else -> path.substring(0, index)
    }
    return EditorPathParts(parent, name, separator)
}

private fun joinEditorPath(parent: String, name: String, separator: String): String = when {
    parent.isBlank() -> name
    parent.endsWith(separator) -> parent + name
    else -> parent + separator + name
}

private fun buildTemporaryEditorName(originalName: String, token: String, role: String): String {
    val base = sanitizeEditorFileName(originalName).take(80).ifBlank { "file" }
    return ".$base.folderspan-$token-$role"
}

private fun sanitizeEditorFileName(name: String): String = buildString(name.length.coerceAtMost(96)) {
    name.take(96).forEach { character ->
        append(
            when {
                character.isLetterOrDigit() -> character
                character == '.' || character == '-' || character == '_' -> character
                else -> '_'
            }
        )
    }
}

private fun editorPathToken(): String =
    "${Clock.System.now().toEpochMilliseconds()}-${Random.nextInt().toUInt().toString(16)}"
