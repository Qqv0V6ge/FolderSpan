package com.folderspan.service.mcp.file

import com.folderspan.data.StatusEnum
import com.folderspan.data.file.FileProtocol
import com.folderspan.ui.state.main.Task
import com.folderspan.ui.state.main.TaskState
import com.folderspan.ui.state.main.TaskType
import com.folderspan.utils.SensitiveFileAccessPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class FileConflictPolicy {
    Error,
    Skip,
    Overwrite,
    Rename,
}

@Serializable
enum class FileTransferItemStatus {
    Copied,
    Skipped,
    Failed,
    SourceDeleteFailed,
}

@Serializable
data class FileTransferItemResult(
    val source: FileLocator,
    val target: FileLocator?,
    val status: FileTransferItemStatus,
    val errorCode: String? = null,
    val message: String? = null,
)

@Serializable
data class FileTransferReport(
    val items: List<FileTransferItemResult>,
) {
    val copiedCount: Int get() = items.count { it.status == FileTransferItemStatus.Copied }
    val skippedCount: Int get() = items.count { it.status == FileTransferItemStatus.Skipped }
    val failedCount: Int get() = items.count { it.status == FileTransferItemStatus.Failed }
    val sourceDeleteFailedCount: Int get() = items.count { it.status == FileTransferItemStatus.SourceDeleteFailed }
    val succeeded: Boolean get() = failedCount == 0 && sourceDeleteFailedCount == 0
}

interface FileTransferControl {
    suspend fun ensureActive()
    suspend fun onProgress(completedItems: Int, totalItems: Int, path: String)

    companion object {
        val None = object : FileTransferControl {
            override suspend fun ensureActive() = Unit
            override suspend fun onProgress(completedItems: Int, totalItems: Int, path: String) = Unit
        }
    }
}

class FileGatewayTransferCoordinator(
    private val resolver: FileEndpointResolver,
    private val chunkBytes: Int = DEFAULT_FILE_RANGE_BYTES,
) {
    init {
        require(chunkBytes in 1..MAX_FILE_RANGE_BYTES)
    }

    suspend fun copy(
        sources: List<FileLocator>,
        targetDirectory: FileLocator,
        conflictPolicy: FileConflictPolicy = FileConflictPolicy.Error,
        control: FileTransferControl = FileTransferControl.None,
    ): FileTransferReport = transfer(sources, targetDirectory, conflictPolicy, move = false, control)

    suspend fun move(
        sources: List<FileLocator>,
        targetDirectory: FileLocator,
        conflictPolicy: FileConflictPolicy = FileConflictPolicy.Error,
        control: FileTransferControl = FileTransferControl.None,
    ): FileTransferReport = transfer(sources, targetDirectory, conflictPolicy, move = true, control)

    suspend fun delete(
        sources: List<FileLocator>,
        control: FileTransferControl = FileTransferControl.None,
    ): FileTransferReport {
        val results = mutableListOf<FileTransferItemResult>()
        sources.forEachIndexed { index, source ->
            control.ensureActive()
            val item = runCatching {
                val (gateway, normalizedPath) = resolver.resolve(source)
                if (!gateway.permissions.delete) {
                    throw FileEndpointException(FileEndpointErrorCode.PermissionDenied, "endpoint does not allow deletion")
                }
                gateway.delete(normalizedPath).getOrThrow()
                FileTransferItemResult(source.copy(path = normalizedPath), null, FileTransferItemStatus.Copied)
            }.getOrElse { error -> source.failure(null, error) }
            results += item
            control.onProgress(index + 1, sources.size, source.path)
        }
        return FileTransferReport(results)
    }

    suspend fun retryDeleteSources(
        priorReport: FileTransferReport,
        control: FileTransferControl = FileTransferControl.None,
    ): FileTransferReport {
        val retryItems = priorReport.items.filter { it.status == FileTransferItemStatus.SourceDeleteFailed }
        val retained = priorReport.items.filterNot { it.status == FileTransferItemStatus.SourceDeleteFailed }.toMutableList()
        retryItems.forEachIndexed { index, item ->
            control.ensureActive()
            val retryResult = runCatching {
                val (gateway, path) = resolver.resolve(item.source)
                gateway.delete(path).getOrThrow()
                item.copy(status = FileTransferItemStatus.Copied, errorCode = null, message = null)
            }.getOrElse { error ->
                item.copy(errorCode = error.endpointCode(), message = error.safeTransferMessage())
            }
            retained += retryResult
            control.onProgress(index + 1, retryItems.size, item.source.path)
        }
        return FileTransferReport(retained)
    }

    private suspend fun transfer(
        sources: List<FileLocator>,
        targetDirectory: FileLocator,
        conflictPolicy: FileConflictPolicy,
        move: Boolean,
        control: FileTransferControl,
    ): FileTransferReport {
        require(sources.isNotEmpty()) { "sources must not be empty" }
        val (targetGateway, targetDirectoryPath) = resolver.resolve(targetDirectory)
        if (!targetGateway.permissions.write) {
            throw FileEndpointException(FileEndpointErrorCode.PermissionDenied, "target endpoint does not allow writing")
        }
        val targetInfo = targetGateway.info(targetDirectoryPath).getOrThrow()
        if (!targetInfo.isDirectory) throw FileEndpointException(FileEndpointErrorCode.InvalidArgument, "target is not a directory")

        val results = mutableListOf<FileTransferItemResult>()
        sources.forEachIndexed { index, source ->
            control.ensureActive()
            val itemResults = runCatching {
                copyTree(source, targetGateway, targetDirectoryPath, conflictPolicy, move, control)
            }.getOrElse { error -> listOf(source.failure(null, error)) }
            results += itemResults
            control.onProgress(index + 1, sources.size, source.path)
        }
        return FileTransferReport(results)
    }

    private suspend fun copyTree(
        source: FileLocator,
        targetGateway: FileEndpointGateway,
        targetDirectoryPath: String,
        conflictPolicy: FileConflictPolicy,
        move: Boolean,
        control: FileTransferControl,
    ): List<FileTransferItemResult> {
        val (sourceGateway, sourcePath) = resolver.resolve(source)
        if (!sourceGateway.permissions.read) {
            throw FileEndpointException(FileEndpointErrorCode.PermissionDenied, "source endpoint does not allow reading")
        }
        if (move && !sourceGateway.permissions.delete) {
            throw FileEndpointException(FileEndpointErrorCode.PermissionDenied, "source endpoint does not allow deletion")
        }
        val sourceInfo = sourceGateway.info(sourcePath).getOrThrow()
        val initialTarget = joinEndpointPath(targetDirectoryPath, sourceInfo.name, targetGateway.pathSeparator)
        val targetPath = resolveConflictTarget(targetGateway, initialTarget, sourceInfo.isDirectory, conflictPolicy)
            ?: return listOf(
                FileTransferItemResult(
                    source = source.copy(path = sourcePath),
                    target = FileLocator(targetGateway.endpoint, initialTarget),
                    status = FileTransferItemStatus.Skipped,
                )
            )
        val targetLocator = FileLocator(targetGateway.endpoint, targetPath)
        if (source.endpoint == targetGateway.endpoint && sourcePath == targetPath) {
            throw FileEndpointException(FileEndpointErrorCode.Conflict, "source and target paths are identical")
        }

        if (!sourceInfo.isDirectory) {
            copyFile(sourceGateway, sourceInfo, targetGateway, targetPath, control)
            val copied = FileTransferItemResult(source.copy(path = sourcePath), targetLocator, FileTransferItemStatus.Copied)
            if (!move) return listOf(copied)
            return listOf(deleteMovedSource(sourceGateway, copied))
        }

        targetGateway.createDirectory(targetPath).getOrThrow()
        val childResults = mutableListOf<FileTransferItemResult>()
        val children = sourceGateway.list(sourcePath).getOrThrow()
        children.forEach { child ->
            control.ensureActive()
            childResults += copyTree(
                source = FileLocator(source.endpoint, child.path),
                targetGateway = targetGateway,
                targetDirectoryPath = targetPath,
                conflictPolicy = conflictPolicy,
                move = move,
                control = control,
            )
        }
        val directoryCopied = FileTransferItemResult(source.copy(path = sourcePath), targetLocator, FileTransferItemStatus.Copied)
        if (!move) return listOf(directoryCopied) + childResults
        val hasChildFailure = childResults.any {
            it.status == FileTransferItemStatus.Failed || it.status == FileTransferItemStatus.SourceDeleteFailed
        }
        return if (hasChildFailure) {
            listOf(directoryCopied.copy(status = FileTransferItemStatus.SourceDeleteFailed, message = "child move is incomplete")) + childResults
        } else {
            listOf(deleteMovedSource(sourceGateway, directoryCopied)) + childResults
        }
    }

    private suspend fun copyFile(
        sourceGateway: FileEndpointGateway,
        source: FileEndpointEntry,
        targetGateway: FileEndpointGateway,
        targetPath: String,
        control: FileTransferControl,
    ) {
        val (targetParent) = parentAndName(targetPath, targetGateway.pathSeparator)
        val freeBytes = targetGateway.availableBytes(targetParent)
        if (freeBytes != null && freeBytes < source.size) {
            throw FileEndpointException(FileEndpointErrorCode.IoError, "insufficient staging disk space")
        }
        if (source.size == 0L) {
            targetGateway.createFile(targetPath).getOrThrow()
            return
        }
        var offset = 0L
        var completed = false
        try {
            while (offset < source.size) {
                control.ensureActive()
                val length = minOf(chunkBytes.toLong(), source.size - offset).toInt()
                val range = sourceGateway.readRange(source.path, offset, length).getOrThrow()
                if (range.bytes.isEmpty()) throw FileEndpointException(FileEndpointErrorCode.IoError, "source stream ended early")
                targetGateway.writeRange(targetPath, source.size, offset, range.bytes).getOrThrow()
                offset += range.bytes.size
            }
            completed = true
        } finally {
            if (!completed) targetGateway.abortWrite(targetPath)
        }
    }

    private suspend fun deleteMovedSource(
        sourceGateway: FileEndpointGateway,
        copied: FileTransferItemResult,
    ): FileTransferItemResult = runCatching {
        sourceGateway.delete(copied.source.path).getOrThrow()
        copied
    }.getOrElse { error ->
        copied.copy(
            status = FileTransferItemStatus.SourceDeleteFailed,
            errorCode = error.endpointCode(),
            message = error.safeTransferMessage(),
        )
    }

    private suspend fun resolveConflictTarget(
        gateway: FileEndpointGateway,
        initialPath: String,
        isDirectory: Boolean,
        policy: FileConflictPolicy,
    ): String? {
        if (gateway.info(initialPath).isFailure) return initialPath
        return when (policy) {
            FileConflictPolicy.Error -> throw FileEndpointException(FileEndpointErrorCode.Conflict, "target already exists")
            FileConflictPolicy.Skip -> null
            FileConflictPolicy.Overwrite -> {
                gateway.delete(initialPath).getOrThrow()
                initialPath
            }
            FileConflictPolicy.Rename -> deterministicAvailablePath(gateway, initialPath, isDirectory)
        }
    }

    private suspend fun deterministicAvailablePath(
        gateway: FileEndpointGateway,
        initialPath: String,
        isDirectory: Boolean,
    ): String {
        val (parent, name) = parentAndName(initialPath, gateway.pathSeparator)
        val dot = if (isDirectory) -1 else name.lastIndexOf('.').takeIf { it > 0 } ?: -1
        val stem = if (dot > 0) name.substring(0, dot) else name
        val extension = if (dot > 0) name.substring(dot) else ""
        for (index in 1..10_000) {
            val candidate = joinEndpointPath(parent, "$stem ($index)$extension", gateway.pathSeparator)
            if (gateway.info(candidate).isFailure) return candidate
        }
        throw FileEndpointException(FileEndpointErrorCode.Conflict, "no deterministic target name is available")
    }
}

class FileGatewayTaskSubmitter(
    private val taskState: TaskState,
    private val coordinator: FileGatewayTransferCoordinator,
    @Suppress("unused") private val scope: CoroutineScope,
) {
    fun submitCopy(
        sources: List<FileLocator>,
        target: FileLocator,
        policy: FileConflictPolicy = FileConflictPolicy.Error,
    ): Long = submit(TaskType.Copy, sources, target, policy)

    fun submitMove(
        sources: List<FileLocator>,
        target: FileLocator,
        policy: FileConflictPolicy = FileConflictPolicy.Error,
    ): Long = submit(TaskType.Move, sources, target, policy)

    fun submitDelete(sources: List<FileLocator>): Long {
        rejectProtectedLocalPaths(sources)
        val task = createTask(TaskType.Delete, sources, null, null)
        taskState.addOrUpdate(task)
        taskState.registerTaskHandler(task) {
            executeTask(task) { control -> coordinator.delete(sources, control) }
        }
        return task.key
    }

    fun retryDeleteSources(taskKey: Long): Boolean {
        val task = taskState.getTask(taskKey) ?: return false
        val report = task.values[REPORT_VALUE_KEY]?.let { runCatching { JSON.decodeFromString<FileTransferReport>(it) }.getOrNull() }
            ?: return false
        if (report.sourceDeleteFailedCount == 0) return false
        rejectProtectedLocalPaths(
            report.items
                .filter { item -> item.status == FileTransferItemStatus.SourceDeleteFailed }
                .map { item -> item.source },
        )
        taskState.updateStatus(task, StatusEnum.LOADING)
        taskState.registerTaskHandler(task) {
            executeTask(task) { control -> coordinator.retryDeleteSources(report, control) }
        }
        return true
    }

    private fun submit(
        type: TaskType,
        sources: List<FileLocator>,
        target: FileLocator,
        policy: FileConflictPolicy,
    ): Long {
        rejectProtectedLocalPaths(sources + target)
        val task = createTask(type, sources, target, policy)
        taskState.addOrUpdate(task)
        taskState.registerTaskHandler(task) {
            executeTask(task) { control ->
                if (type == TaskType.Move) coordinator.move(sources, target, policy, control)
                else coordinator.copy(sources, target, policy, control)
            }
        }
        return task.key
    }

    private fun rejectProtectedLocalPaths(locators: List<FileLocator>) {
        val protected = locators.asSequence()
            .filter { locator -> locator.endpoint.protocol == FileProtocol.Local }
            .map { locator -> SensitiveFileAccessPolicy.classify(locator.path) }
            .firstOrNull { classification -> classification.isProtected }
        if (protected != null) {
            throw FileEndpointException(
                FileEndpointErrorCode.PermissionDenied,
                SensitiveFileAccessPolicy.protectedPathMessage(protected),
            )
        }
    }

    private suspend fun executeTask(
        task: Task,
        operation: suspend (FileTransferControl) -> FileTransferReport,
    ) {
        val control = object : FileTransferControl {
            override suspend fun ensureActive() {
                if (!taskState.awaitIfPaused(task.key) || taskState.isTaskCancelled(task.key)) {
                    throw CancellationException("task was cancelled")
                }
            }

            override suspend fun onProgress(completedItems: Int, totalItems: Int, path: String) {
                taskState.putValues(
                    task,
                    listOf(
                        "progressCur" to completedItems.toString(),
                        "progressMax" to totalItems.toString(),
                        "path" to path,
                    ),
                )
            }
        }
        val report = operation(control)
        taskState.putValue(task, REPORT_VALUE_KEY, JSON.encodeToString(report))
        report.items.filter { it.status == FileTransferItemStatus.Failed || it.status == FileTransferItemStatus.SourceDeleteFailed }
            .forEach { item -> taskState.putResult(task, item.source.path, item.message.orEmpty()) }
        taskState.updateStatus(task, if (report.succeeded) StatusEnum.SUCCESS else StatusEnum.FAILURE)
    }

    private fun createTask(
        type: TaskType,
        sources: List<FileLocator>,
        target: FileLocator?,
        policy: FileConflictPolicy?,
    ): Task = Task(
        taskType = type,
        status = StatusEnum.LOADING,
        protocol = target?.endpoint?.protocol ?: sources.firstOrNull()?.endpoint?.protocol ?: FileProtocol.Local,
        protocolId = target?.endpoint?.sourceId ?: sources.firstOrNull()?.endpoint?.sourceId.orEmpty(),
        values = buildMap {
            put("mcpSources", JSON.encodeToString(sources))
            target?.let { put("mcpTarget", JSON.encodeToString(it)) }
            policy?.let { put("mcpConflictPolicy", it.name.lowercase()) }
        },
    )

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }
        const val REPORT_VALUE_KEY = "mcpTransferReport"
    }
}

private fun FileLocator.failure(target: FileLocator?, error: Throwable): FileTransferItemResult =
    FileTransferItemResult(
        source = this,
        target = target,
        status = FileTransferItemStatus.Failed,
        errorCode = error.endpointCode(),
        message = error.safeTransferMessage(),
    )

private fun Throwable.endpointCode(): String =
    (this as? FileEndpointException)?.code?.wireValue ?: FileEndpointErrorCode.IoError.wireValue

private fun Throwable.safeTransferMessage(): String = when (this) {
    is FileEndpointException -> message.orEmpty()
    is CancellationException -> "operation cancelled"
    else -> "file operation failed"
}
