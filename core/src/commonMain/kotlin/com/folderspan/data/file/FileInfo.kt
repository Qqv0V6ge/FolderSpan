package com.folderspan.data.file

import com.folderspan.utils.FileAccessPermission
import com.folderspan.utils.FileSensitivity
import com.folderspan.extensions.*
import com.folderspan.service.data.CopyPathProgress
import com.folderspan.service.http.client.HttpRouteClientManager.Companion.MAX_LENGTH
import com.folderspan.service.operation.*
import com.folderspan.ui.state.main.*
import com.folderspan.utils.FileUtils
import com.folderspan.utils.PathUtils
import com.folderspan.utils.SensitiveFileAccessPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.protobuf.ProtoNumber
import strings.AppStrings
import kotlin.math.ceil
import kotlin.time.Clock

private suspend fun collectLocalDirectoryEntriesAdaptive(
    root: FileSimpleInfo,
    ensureRunning: suspend () -> Unit,
    onScanProgress: suspend (TraversalScanProgress) -> Unit = {},
    onEntriesDiscovered: suspend (List<FileSimpleInfo>) -> Unit = {},
    onRejectedEntry: suspend (FileSimpleInfo, Throwable) -> Unit = { _, _ -> },
): List<FileSimpleInfo> {
    if (PathUtils.isSymbolicLink(FileAccessPermission.Allowed, root.path)) return emptyList()
    return collectDirectoryEntriesAdaptive(
        root = root.withCopy(protocol = FileProtocol.Local, protocolId = ""),
        config = resolveTraversalParallelism(TraversalEndpointKind.Local),
        pathSeparator = PathUtils.getPathSeparator(),
        ensureRunning = ensureRunning,
        onScanProgress = onScanProgress,
        onEntriesDiscovered = onEntriesDiscovered,
        dynamicMaxParallelismProvider = {
            resolveTraversalRuntimeMaxParallelism(TraversalEndpointKind.Local)
        },
        rejectSymbolicLinkEntries = true,
        onRejectedEntry = onRejectedEntry,
    ) { directory ->
        PathUtils.getFileAndFolder(FileAccessPermission.Allowed, directory.path).map { entries ->
            entries.map { entry ->
                entry.withCopy(
                    protocol = FileProtocol.Local,
                    protocolId = "",
                    isSymbolicLink = entry.isSymbolicLinkKnown && entry.isSymbolicLink ||
                        (!entry.isSymbolicLinkKnown && PathUtils.isSymbolicLink(FileAccessPermission.Allowed, entry.path)),
                    isSymbolicLinkKnown = true,
                )
            }
        }
    }
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class FileSimpleInfo(
    @ProtoNumber(1) val name: String,
    @ProtoNumber(2) val description: String = "",
    @ProtoNumber(3) val isDirectory: Boolean,
    @ProtoNumber(4) val isHidden: Boolean,
    @ProtoNumber(5) var path: String,
    @ProtoNumber(6) val mineType: String,
    @ProtoNumber(7) val size: Long,
    @ProtoNumber(8) val createdDate: Long,
    @ProtoNumber(9) val updatedDate: Long,
    @ProtoNumber(10) var protocol: FileProtocol = FileProtocol.Local,
    @ProtoNumber(11) var protocolId: String = "",
    @ProtoNumber(12) val isIgnored: Boolean = false,
    @ProtoNumber(13) val isSymbolicLink: Boolean = false,
    @ProtoNumber(14) val isSymbolicLinkKnown: Boolean = false,
    @ProtoNumber(15) val sensitivity: FileSensitivity = FileSensitivity.None,
    @ProtoNumber(16) val sensitivityCategory: String = "",
) {
    @Transient
    var fileFilterType: FileFilterType =
        if (isDirectory) FileFilterType.Folder else FileFilterType.File
        internal set

    companion object {
        fun nullFileSimpleInfo() = FileSimpleInfo(
            name = "",
            description = "",
            isDirectory = false,
            isHidden = false,
            path = "",
            mineType = "",
            size = 0,
            createdDate = 0,
            updatedDate = 0
        )

        fun pathFileSimpleInfo(path: String) = FileSimpleInfo(
            name = path,
            description = "",
            isDirectory = true,
            isHidden = false,
            path = path,
            mineType = "",
            size = 0,
            createdDate = 0,
            updatedDate = 0
        )
    }

    fun withCopy(
        name: String? = null,
        description: String? = null,
        isDirectory: Boolean? = null,
        isHidden: Boolean? = null,
        path: String? = null,
        mineType: String? = null,
        size: Long? = null,
        createdDate: Long? = null,
        updatedDate: Long? = null,
        protocol: FileProtocol? = null,
        protocolId: String? = null,
        isIgnored: Boolean? = null,
        isSymbolicLink: Boolean? = null,
        isSymbolicLinkKnown: Boolean? = null,
        sensitivity: FileSensitivity? = null,
        sensitivityCategory: String? = null,
    ): FileSimpleInfo {
        val copied = FileSimpleInfo(
            name = name ?: this.name,
            description = description ?: this.description,
            isDirectory = isDirectory ?: this.isDirectory,
            isHidden = isHidden ?: this.isHidden,
            path = path ?: this.path,
            mineType = mineType ?: this.mineType,
            size = size ?: this.size,
            createdDate = createdDate ?: this.createdDate,
            updatedDate = updatedDate ?: this.updatedDate,
            protocol = protocol ?: this.protocol,
            protocolId = protocolId ?: this.protocolId,
            isIgnored = isIgnored ?: this.isIgnored,
            isSymbolicLink = isSymbolicLink ?: this.isSymbolicLink,
            isSymbolicLinkKnown = isSymbolicLinkKnown ?: this.isSymbolicLinkKnown,
            sensitivity = sensitivity ?: this.sensitivity,
            sensitivityCategory = sensitivityCategory ?: this.sensitivityCategory,
        )
        copied.fileFilterType = if (copied.isDirectory == this.isDirectory) {
            fileFilterType
        } else if (copied.isDirectory) {
            FileFilterType.Folder
        } else {
            FileFilterType.File
        }
        return copied
    }

    suspend fun writeToFile(
        destPath: String,
        onProgress: (suspend (current: Int, total: Int, doneBytes: Long) -> Unit)? = null,
        shouldContinue: suspend () -> Boolean = { true }
    ): Result<Boolean> {
        if (isSymbolicLink || (protocol == FileProtocol.Local && PathUtils.isSymbolicLink(FileAccessPermission.Allowed, path))) {
            return Result.failure(
                IllegalStateException(
                    AppStrings.file_symbolic_link_read_not_supported.format(path = path)
                )
            )
        }
        if (protocol == FileProtocol.Local) {
            SensitiveFileAccessPolicy.deniedException(path)?.let { error ->
                return Result.failure(error)
            }
        }
        if (!shouldContinue()) {
            return Result.failure(CancellationException(AppStrings.message_task_cancelled))
        }
        if (size == 0L) {
            if (isDirectory) {
                return FileUtils.createFolder(FileAccessPermission.Allowed, destPath)
            }
            return FileUtils.createFile(FileAccessPermission.Allowed, destPath)
        }

        val totalChunks = getChunkCount()
        var completedChunks = 0
        var writtenBytes = 0L
        return try {
            FileUtils.readFileChunks(FileAccessPermission.Allowed, path, MAX_LENGTH.toLong()).collect { item ->
                if (!shouldContinue()) {
                    throw CancellationException(AppStrings.message_task_cancelled)
                }
                val result = item.getOrNull() ?: throw (item.exceptionOrNull() ?: Exception(AppStrings.file_read_failed))
                val chunkIndex = result.first
                val chunkBytes = result.second
                val writeSuccess = FileUtils.writeBytes(FileAccessPermission.Allowed,
                    destPath,
                    size,
                    chunkBytes,
                    chunkIndex * MAX_LENGTH
                ).getOrElse { throw it }
                if (!writeSuccess) {
                    throw Exception(AppStrings.ui_writing_file_failed)
                }
                completedChunks++
                writtenBytes = (writtenBytes + chunkBytes.size.toLong()).coerceAtMost(size)
                onProgress?.invoke(completedChunks, totalChunks, writtenBytes)
            }
            if (completedChunks == totalChunks) {
                Result.success(true)
            } else {
                Result.failure(Exception(AppStrings.file_write_incomplete))
            }
        } catch (cancel: CancellationException) {
            Result.failure(cancel)
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    suspend fun copyTo(
        destPath: String,
        shouldContinue: suspend () -> Boolean = { true },
        onProgress: (suspend (CopyPathProgress) -> Unit)? = null,
    ): Result<Boolean> {
        if (isSymbolicLink || (protocol == FileProtocol.Local && PathUtils.isSymbolicLink(FileAccessPermission.Allowed, path))) {
            return Result.failure(
                IllegalStateException(
                    AppStrings.file_symbolic_link_copy_not_supported
                )
            )
        }
        if (protocol == FileProtocol.Local) {
            SensitiveFileAccessPolicy.deniedException(path)?.let { error ->
                return Result.failure(error)
            }
        }
        val cancellation = CancellationException(AppStrings.message_task_cancelled)

        suspend fun ensureRunning() {
            if (!shouldContinue()) {
                throw cancellation
            }
        }

        class DetailedCopyProgressTracker(
            totalBytes: Long,
        ) {
            private val startMs = Clock.System.now().toEpochMilliseconds()
            private val rateSampler = TaskProgressRateSampler(initialAt = startMs)
            private var totalBytes = totalBytes.coerceAtLeast(0L)
            private var doneBytes = 0L

            fun setTotalBytes(totalBytes: Long) {
                this.totalBytes = totalBytes.coerceAtLeast(0L)
                doneBytes = doneBytes.coerceIn(0L, this.totalBytes.coerceAtLeast(0L))
            }

            fun setDone(doneBytes: Long) {
                this.doneBytes = doneBytes.coerceIn(0L, totalBytes.coerceAtLeast(0L))
            }

            fun build(current: Long, total: Long, prefix: String): String {
                val normalizedCurrent = current.coerceAtMost(total.coerceAtLeast(0L))
                if (totalBytes <= 0L) {
                    return AppStrings.message_task_progress_units.format(
                        progress = prefix,
                        current = normalizedCurrent.toString(),
                        total = total.toString(),
                    )
                }

                val now = Clock.System.now().toEpochMilliseconds()
                val normalizedDoneBytes = doneBytes.coerceIn(0L, totalBytes)
                val rateSample = rateSampler.update(
                    completed = normalizedDoneBytes,
                    total = totalBytes,
                    now = now,
                    force = totalBytes in 1..normalizedDoneBytes,
                )

                return AppStrings.message_task_progress_with_metrics.format(
                    progress = prefix,
                    percent = normalizedDoneBytes.formatPercent(totalBytes),
                    current = normalizedCurrent.toString(),
                    total = total.toString(),
                    speed = rateSample.speedPerSecond.formatSpeed(),
                    remaining = rateSample.etaMs.formatDuration(),
                )
            }
        }

        suspend fun emitProgress(
            current: Long,
            total: Long,
            path: String,
            message: String,
            done: Boolean = false,
            success: Boolean = false,
        ) {
            onProgress?.invoke(
                CopyPathProgress(
                    progressCur = current,
                    progressMax = total,
                    path = path,
                    message = message,
                    done = done,
                    success = success,
                )
            )
        }

        if (!isDirectory) {
            val totalUnits = if (size == 0L) 1L else getChunkCount().toLong()
            val progressTracker = DetailedCopyProgressTracker(size)
            return if (size == 0L) {
                val result = if (isDirectory) FileUtils.createFolder(FileAccessPermission.Allowed, destPath) else FileUtils.createFile(FileAccessPermission.Allowed, destPath)
                if (result.isSuccess && result.getOrDefault(false)) {
                    emitProgress(1, totalUnits, destPath, AppStrings.ui_copy_completed)
                }
                result
            } else {
                writeToFile(
                    destPath = destPath,
                    onProgress = { current, total, doneBytes ->
                        progressTracker.setDone(doneBytes)
                        emitProgress(
                            current = current.toLong(),
                            total = total.toLong(),
                            path = destPath,
                            message = progressTracker.build(
                                current = current.toLong(),
                                total = total.toLong(),
                                prefix = AppStrings.ui_copy_progress
                            )
                        )
                    },
                    shouldContinue = shouldContinue
                ).onSuccess { success ->
                    if (success) {
                        emitProgress(totalUnits, totalUnits, destPath, AppStrings.ui_copy_completed)
                    }
                }
            }
        }

        val srcRootPath = path

        return try {
            var totalUnits = 1L
            var totalBytes = 0L
            var discoveredEntries = 0
            val progressTracker = DetailedCopyProgressTracker(0L)
            var completedUnits = 0L
            var completedBytes = 0L

            emitProgress(0, 0, destPath, buildCopyScanMessage())
            ensureRunning()

            val traversalEntries = collectLocalDirectoryEntriesAdaptive(
                root = this,
                ensureRunning = { ensureRunning() },
                onEntriesDiscovered = { entries ->
                    if (entries.isNotEmpty()) {
                        discoveredEntries += entries.size
                        totalUnits += entries.sumOf { item ->
                            when {
                                item.isDirectory -> 1L
                                item.size <= 0L -> 1L
                                else -> item.getChunkCount().toLong()
                            }
                        }
                        totalBytes += entries
                            .asSequence()
                            .filter { item -> !item.isDirectory }
                            .sumOf { item -> item.size.coerceAtLeast(0L) }
                        emitProgress(0, totalUnits, destPath, buildCopyScanMessage(discoveredEntries))
                    }
                },
            )
            progressTracker.setTotalBytes(totalBytes)

            ensureRunning()
            emitProgress(0, totalUnits, destPath, buildCreatingFolderMessage(destPath))
            val rootFolder = FileUtils.createFolder(FileAccessPermission.Allowed, destPath)
            if (rootFolder.isFailure) {
                return Result.failure(rootFolder.exceptionOrNull() ?: Exception(AppStrings.ui_folder_creation_failed))
            }
            if (!rootFolder.getOrDefault(false)) {
                return Result.failure(Exception(AppStrings.ui_folder_creation_failed))
            }
            completedUnits++
            progressTracker.setDone(completedBytes)
            emitProgress(
                completedUnits,
                totalUnits,
                destPath,
                progressTracker.build(completedUnits, totalUnits, buildCreatingFolderMessage(destPath))
            )

            val orderedEntries = traversalEntries.sortedWith(
                compareBy<FileSimpleInfo> { item -> !item.isDirectory }
                    .thenBy { item -> item.path.pathLevel() }
            )
            val progressMutex = Mutex()
            orderedEntries.filter { item -> item.isDirectory }.forEach { entry ->
                ensureRunning()
                val targetPath = entry.path.replaceFirst(srcRootPath, destPath)
                val created = FileUtils.createFolder(FileAccessPermission.Allowed, targetPath)
                if (created.isFailure) {
                    throw (created.exceptionOrNull() ?: Exception(AppStrings.ui_folder_creation_failed))
                }
                if (!created.getOrDefault(false)) {
                    throw Exception(AppStrings.ui_folder_creation_failed)
                }
                completedUnits++
                progressTracker.setDone(completedBytes)
                emitProgress(
                    completedUnits,
                    totalUnits,
                    targetPath,
                    progressTracker.build(completedUnits, totalUnits, buildCreatingFolderMessage(targetPath))
                )
            }

            val fileEntries = orderedEntries.filterNot { item -> item.isDirectory }
            processItemsAdaptive(
                items = fileEntries,
                config = resolveOperationParallelism(TraversalEndpointKind.Local),
                ensureRunning = { ensureRunning() },
                dynamicMaxParallelismProvider = {
                    resolveOperationRuntimeMaxParallelism(TraversalEndpointKind.Local)
                },
            ) { entry ->
                ensureRunning()
                val targetFilePath = entry.path.replaceFirst(srcRootPath, destPath)
                if (entry.size == 0L) {
                    val createFile = FileUtils.createFile(FileAccessPermission.Allowed, targetFilePath)
                    if (createFile.isFailure) {
                        throw (createFile.exceptionOrNull() ?: Exception(AppStrings.ui_writing_file_failed))
                    }
                    if (!createFile.getOrDefault(false)) {
                        throw Exception(AppStrings.ui_writing_file_failed)
                    }
                    progressMutex.withLock {
                        completedUnits++
                        progressTracker.setDone(completedBytes)
                        emitProgress(
                            completedUnits,
                            totalUnits,
                            targetFilePath,
                            progressTracker.build(completedUnits, totalUnits, AppStrings.ui_copy_completed)
                        )
                    }
                    return@processItemsAdaptive
                }

                val fileTotalUnits = entry.getChunkCount().toLong()
                val write = entry.writeToFile(
                    targetFilePath,
                    onProgress = { current, _, doneBytes ->
                        progressMutex.withLock {
                            val currentUnits = (completedUnits + current).coerceAtMost(totalUnits)
                            progressTracker.setDone((completedBytes + doneBytes).coerceAtMost(totalBytes))
                            emitProgress(
                                current = currentUnits,
                                total = totalUnits,
                                path = targetFilePath,
                                message = progressTracker.build(
                                    current = currentUnits,
                                    total = totalUnits,
                                    prefix = AppStrings.ui_copy_arg0.format(arg0 = entry.name)
                                )
                            )
                        }
                    },
                    shouldContinue = shouldContinue
                )
                if (write.isFailure) {
                    throw (write.exceptionOrNull() ?: Exception(AppStrings.ui_writing_file_failed))
                }
                if (!write.getOrDefault(false)) {
                    throw Exception(AppStrings.ui_writing_file_failed)
                }
                progressMutex.withLock {
                    completedUnits += fileTotalUnits
                    completedBytes += entry.size.coerceAtLeast(0L)
                    progressTracker.setDone(completedBytes)
                }
            }

            emitProgress(totalUnits, totalUnits, destPath, AppStrings.ui_copy_completed)
            Result.success(true)
        } catch (cancel: CancellationException) {
            Result.failure(cancel)
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    /**
     * 计算文件传输需要的分片数量
     * Calculate the number of chunks needed for file transfer
     *
     * @param chunkSize 分片大小，默认使用 MAX_LENGTH
     * @return 分片数量
     */
    fun getChunkCount(chunkSize: Int = MAX_LENGTH): Int {
        return if (size <= 0) 0 else ceil(size / chunkSize.toFloat()).toInt()
    }

    suspend fun copyTo(
        task: Task,
        destPath: String,
        taskState: TaskState
    ): Result<Boolean> {
        if (isSymbolicLink || (protocol == FileProtocol.Local && PathUtils.isSymbolicLink(FileAccessPermission.Allowed, path))) {
            return Result.failure(
                IllegalStateException(
                    AppStrings.file_symbolic_link_copy_not_supported
                )
            )
        }
        if (protocol == FileProtocol.Local) {
            SensitiveFileAccessPolicy.deniedException(path)?.let { error ->
                return Result.failure(error)
            }
        }
        val cancellation = CancellationException(AppStrings.message_task_cancelled)

        suspend fun ensureRunning() {
            if (!taskState.awaitIfPaused(task.key)) {
                if (taskState.isTaskCancelled(task.key)) {
                    throw cancellation
                }
            }
            if (taskState.isTaskCancelled(task.key)) {
                throw cancellation
            }
        }

        fun beginRuntimeByteMetrics(totalBytes: Long) {
            taskState.ensureRuntimeByteMetrics(task, totalBytes.coerceAtLeast(0L))
        }

        fun startRuntimeByteItem(path: String, totalBytes: Long) {
            taskState.startRuntimeByteItem(task, path, totalBytes.coerceAtLeast(0L))
        }

        fun updateRuntimeByteItem(path: String, doneBytes: Long) {
            taskState.putRuntimeByteProgress(task, path, doneBytes.coerceAtLeast(0L))
        }

        fun finishRuntimeByteItem(path: String, totalBytes: Long) {
            taskState.finishRuntimeByteItem(task, path, totalBytes.coerceAtLeast(0L))
        }

        fun clearRuntimeByteItem(path: String) {
            taskState.clearRuntimeByteItem(task, path)
        }

        var successCount = 0
        var failureCount = 0

        fun retryEntry(src: FileSimpleInfo, targetPath: String): TaskRetryEntry {
            return buildCopyRetryEntry(
                taskType = task.taskType,
                src = src,
                dest = buildRetryFileSimpleInfo(
                    path = targetPath,
                    protocol = FileProtocol.Local,
                    protocolId = "",
                    isDirectory = src.isDirectory,
                )
            )
        }

        fun markSuccess(src: FileSimpleInfo, targetPath: String) {
            taskState.removeResult(task, targetPath)
        }

        fun markFailure(
            src: FileSimpleInfo,
            targetPath: String,
            message: String,
            fallback: String,
        ): String {
            return taskState.recordRetryFailure(
                task = task,
                entry = retryEntry(src, targetPath),
                message = message,
                fallback = fallback,
            )
        }

        class UploadProgressTracker(
            private val totalBytes: Long,
            private val defaultTotalBlocks: Int
        ) {
            private val startMs = Clock.System.now().toEpochMilliseconds()
            private val rateSampler = TaskProgressRateSampler(initialAt = startMs)

            fun build(doneBytes: Long, doneBlocks: Int, totalBlocks: Int = defaultTotalBlocks): String {
                val now = Clock.System.now().toEpochMilliseconds()
                val normalizedDoneBytes = doneBytes.coerceIn(0L, totalBytes)
                val rateSample = rateSampler.update(
                    completed = normalizedDoneBytes,
                    total = totalBytes,
                    now = now,
                    force = totalBytes in 1..normalizedDoneBytes,
                )
                val normalizedBlocks = doneBlocks.coerceAtMost(totalBlocks)
                return AppStrings.message_task_progress_with_metrics.format(
                    progress = AppStrings.ui_transfer_progress,
                    percent = normalizedDoneBytes.formatPercent(totalBytes),
                    current = normalizedBlocks.toString(),
                    total = totalBlocks.toString(),
                    speed = rateSample.speedPerSecond.formatSpeed(),
                    remaining = rateSample.etaMs.formatDuration(),
                )
            }
        }

        // 复制文件
        if (!isDirectory) {
            val targetFilePath = path.replaceFirst(path, destPath)
            val totalBlocks = getChunkCount()
            val progressTracker = UploadProgressTracker(size, totalBlocks)
            val progressGate = TaskProgressUpdateGate()
            beginRuntimeByteMetrics(size)
            startRuntimeByteItem(targetFilePath, size)
            taskState.putResult(task, targetFilePath, AppStrings.ui_start_transfer)
            return try {
                ensureRunning()
                writeToFile(
                    targetFilePath,
                    onProgress = { current, total, doneBytes ->
                        ensureRunning()
                        val actualTotalBlocks = maxOf(totalBlocks, total)
                        val forceProgressUpdate = current >= actualTotalBlocks || doneBytes >= size
                        if (progressGate.shouldPublish(force = forceProgressUpdate)) {
                            taskState.putValue(task, "progressMax", actualTotalBlocks.toString())
                            taskState.putValue(task, "progressCur", current.toString())
                            updateRuntimeByteItem(targetFilePath, doneBytes)
                            taskState.putResult(
                                task,
                                targetFilePath,
                                progressTracker.build(
                                    doneBytes = doneBytes,
                                    doneBlocks = current,
                                    totalBlocks = actualTotalBlocks
                                )
                            )
                        }
                    },
                    shouldContinue = {
                        ensureRunning()
                        true
                    }
                ).onSuccess { success ->
                    if (success) {
                        successCount++
                        finishRuntimeByteItem(targetFilePath, size)
                        markSuccess(this, targetFilePath)
                    } else {
                        failureCount++
                        clearRuntimeByteItem(targetFilePath)
                        markFailure(this, targetFilePath, AppStrings.ui_writing_file_failed, AppStrings.ui_writing_file_failed)
                    }
                }
                    .onFailure { item ->
                        clearRuntimeByteItem(targetFilePath)
                        if (item is CancellationException) throw item
                        markFailure(this, targetFilePath, item.message ?: "", AppStrings.ui_writing_file_failed)
                        failureCount++
                    }
                Result.success(successCount == 1 && failureCount == 0)
            } catch (cancel: CancellationException) {
                Result.failure(cancel)
            }
        }

        // 复制文件夹
        var plannedEntries = 0
        var plannedBytes = 0L
        var firstFailedPath: String? = null
        var firstError: String? = null
        val progressMutex = Mutex()

        fun recordFailure(path: String, message: String) {
            val normalizedMessage = message.ifBlank { AppStrings.ui_writing_file_failed }
            taskState.putResult(task, path, normalizedMessage)
            if (firstFailedPath == null) {
                firstFailedPath = path
            }
            if (firstError == null) {
                firstError = normalizedMessage
            }
        }

        suspend fun recordDirectorySuccess(src: FileSimpleInfo, targetPath: String) {
            progressMutex.withLock {
                successCount++
                markSuccess(src, targetPath)
                taskState.putValue(task, "progressCur", (successCount + failureCount).toString())
            }
        }

        suspend fun recordEntryFailure(
            src: FileSimpleInfo,
            targetPath: String,
            message: String,
            fallback: String,
        ) {
            progressMutex.withLock {
                failureCount++
                val normalizedMessage = markFailure(
                    src = src,
                    targetPath = targetPath,
                    message = message,
                    fallback = fallback,
                )
                recordFailure(targetPath, normalizedMessage)
                taskState.putValue(task, "progressCur", (successCount + failureCount).toString())
            }
        }

        suspend fun processDirectoryEntry(entry: FileSimpleInfo) {
            ensureRunning()
            val normalizedEntry = entry.withCopy(protocol = protocol, protocolId = protocolId)
            val targetPath = entry.path.replaceFirst(path, destPath)
            taskState.putCreatingFolderProgress(task, targetPath)
            val result = FileUtils.createFolder(FileAccessPermission.Allowed, targetPath)
            if (result.isSuccess && result.getOrDefault(false)) {
                recordDirectorySuccess(normalizedEntry, targetPath)
            } else {
                recordEntryFailure(
                    src = normalizedEntry,
                    targetPath = targetPath,
                    message = result.exceptionOrNull()?.message ?: AppStrings.ui_folder_creation_failed,
                    fallback = AppStrings.ui_folder_creation_failed,
                )
            }
        }

        suspend fun copyFileEntry(entry: FileSimpleInfo) {
            ensureRunning()
            val normalizedEntry = entry.withCopy(protocol = protocol, protocolId = protocolId)
            val targetPath = entry.path.replaceFirst(path, destPath)
            val totalBlocks = entry.getChunkCount()
            val progressTracker = UploadProgressTracker(entry.size, totalBlocks)
            val progressGate = TaskProgressUpdateGate()
            startRuntimeByteItem(targetPath, entry.size)
            taskState.putResult(task, targetPath, AppStrings.ui_start_transfer)
            val write = entry.writeToFile(
                targetPath,
                onProgress = { current, total, doneBytes ->
                    ensureRunning()
                    val actualTotalBlocks = maxOf(totalBlocks, total)
                    val forceProgressUpdate = current >= actualTotalBlocks || doneBytes >= entry.size
                    if (progressGate.shouldPublish(force = forceProgressUpdate)) {
                        updateRuntimeByteItem(targetPath, doneBytes)
                        taskState.putResult(
                            task,
                            targetPath,
                            progressTracker.build(
                                doneBytes = doneBytes,
                                doneBlocks = current,
                                totalBlocks = actualTotalBlocks
                            )
                        )
                    }
                },
                shouldContinue = {
                    ensureRunning()
                    true
                }
            )

            if (write.isSuccess && write.getOrDefault(false)) {
                progressMutex.withLock {
                    successCount++
                    finishRuntimeByteItem(targetPath, entry.size)
                    markSuccess(normalizedEntry, targetPath)
                    taskState.putValue(task, "progressCur", (successCount + failureCount).toString())
                }
            } else {
                val failure = write.exceptionOrNull()
                if (failure is CancellationException) throw failure
                clearRuntimeByteItem(targetPath)
                recordEntryFailure(
                    src = normalizedEntry,
                    targetPath = targetPath,
                    message = failure?.message ?: AppStrings.ui_writing_file_failed,
                    fallback = AppStrings.ui_writing_file_failed,
                )
            }
        }

        suspend fun enqueueTraversalBatch(
            entries: List<FileSimpleInfo>,
        ) {
            if (entries.isEmpty()) return
            val normalizedEntries = entries.sortedWith(
                compareBy<FileSimpleInfo> { item -> !item.isDirectory }
                    .thenBy { item -> item.path.pathLevel() }
            )
            normalizedEntries.filter { it.isDirectory }.forEach { entry ->
                processDirectoryEntry(entry)
            }
            taskState.removeResult(task, "")
            val files = normalizedEntries.filterNot { it.isDirectory }
            processItemsAdaptive(
                items = files,
                config = resolveOperationParallelism(TraversalEndpointKind.Local),
                ensureRunning = { ensureRunning() },
                dynamicMaxParallelismProvider = {
                    resolveOperationRuntimeMaxParallelism(TraversalEndpointKind.Local)
                },
            ) { entry ->
                copyFileEntry(entry)
            }
        }

        taskState.putCopyScanProgress(task)
        val traversalEntries: List<FileSimpleInfo>

        try {
            traversalEntries = collectLocalDirectoryEntriesAdaptive(
                root = this,
                ensureRunning = { ensureRunning() },
                onScanProgress = taskState.buildCopyScanProgressPublisher(task),
                onEntriesDiscovered = { entries ->
                    plannedEntries += entries.size
                    plannedBytes += entries
                        .asSequence()
                        .filterNot { item -> item.isDirectory }
                        .sumOf { item -> item.size.coerceAtLeast(0L) }
                    taskState.putCopyScanProgress(task, plannedEntries)
                },
                onRejectedEntry = { entry, error ->
                    plannedEntries++
                    val normalizedEntry = entry.withCopy(protocol = protocol, protocolId = protocolId)
                    recordEntryFailure(
                        src = normalizedEntry,
                        targetPath = entry.path.replaceFirst(path, destPath),
                        message = error.message.orEmpty(),
                        fallback = AppStrings.file_symbolic_link_copy_not_supported,
                    )
                    taskState.putCopyScanProgress(task, plannedEntries)
                },
            )
        } catch (cancel: CancellationException) {
            return Result.failure(cancel)
        } catch (error: Throwable) {
            val message = markFailure(this, destPath, error.message ?: AppStrings.file_read_failed, AppStrings.file_read_failed)
            taskState.putResult(task, destPath, message)
            return Result.failure(error)
        }

        val resolvedProgressMax = plannedEntries.coerceAtLeast(1)
        beginRuntimeByteMetrics(plannedBytes)
        taskState.putValue(task, "progressMax", resolvedProgressMax.toString())
        taskState.putValue(task, "progressCur", "0")

        taskState.putCreatingFolderProgress(task, destPath)
        val rootFolder = FileUtils.createFolder(FileAccessPermission.Allowed, destPath)
        if (rootFolder.isFailure || !rootFolder.getOrDefault(false)) {
            taskState.putValue(task, "progressCur", "1")
            markFailure(
                this,
                destPath,
                rootFolder.exceptionOrNull()?.message ?: AppStrings.ui_folder_creation_failed,
                AppStrings.ui_folder_creation_failed,
            )
            return Result.failure(rootFolder.exceptionOrNull() ?: Exception(AppStrings.ui_folder_creation_failed))
        }

        if (plannedEntries == 0) {
            taskState.putValue(task, "progressCur", "1")
            taskState.removeResult(task, "")
            taskState.removeResult(task, destPath)
            return Result.success(true)
        }

        return try {
            enqueueTraversalBatch(traversalEntries)
            taskState.removeResult(task, "")
            if (failureCount > 0) {
                val summary = buildBatchTaskFailureMessage(
                    operation = AppStrings.ui_copy,
                    failureCount = failureCount,
                    firstFailedPath = firstFailedPath,
                    firstError = firstError,
                )
                taskState.putResult(task, destPath, summary)
                Result.failure(Exception(summary))
            } else {
                taskState.removeResult(task, destPath)
                Result.success(successCount == plannedEntries)
            }
        } catch (cancel: CancellationException) {
            Result.failure(cancel)
        } catch (error: Throwable) {
            val message = markFailure(this, destPath, error.message ?: AppStrings.file_read_failed, AppStrings.file_read_failed)
            taskState.putResult(task, destPath, message)
            Result.failure(error)
        }
    }

}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class FileInfo(
    @ProtoNumber(1) val name: String,
    @ProtoNumber(2) val description: String = "",
    @ProtoNumber(3) val isDirectory: Boolean,
    @ProtoNumber(4) val isHidden: Boolean,
    @ProtoNumber(5) val path: String,
    @ProtoNumber(6) val mineType: String,
    @ProtoNumber(7) val size: Long,
    @ProtoNumber(8) val permissions: Int,
    @ProtoNumber(9) val user: String,
    @ProtoNumber(10) val userGroup: String,
    @ProtoNumber(11) val createdDate: Long,
    @ProtoNumber(12) val updatedDate: Long,
    @ProtoNumber(13) var protocol: FileProtocol = FileProtocol.Local,
    @ProtoNumber(14) var protocolId: String = "",
) {
    companion object {

        fun pathFileInfo(path: String) = FileInfo(
            name = path,
            description = "",
            isDirectory = true,
            isHidden = false,
            path = path,
            mineType = "",
            size = 0,
            permissions = 0,
            user = "",
            userGroup = "",
            createdDate = 0,
            updatedDate = 0
        )
    }
}
