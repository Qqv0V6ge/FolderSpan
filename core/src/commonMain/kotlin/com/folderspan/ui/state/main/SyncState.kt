package com.folderspan.ui.state.main

import strings.AppStrings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.folderspan.data.StatusEnum
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.main.DiskBase
import com.folderspan.data.main.Local
import com.folderspan.data.main.network.buildProtocolId
import com.folderspan.ignore.SupportedIgnoreFileNames
import com.folderspan.ignore.findExistingSupportedIgnoreFileNames
import com.folderspan.ignore.normalizeSupportedIgnoreFileNames
import com.folderspan.ui.state.file.FileState
import com.folderspan.utils.PathUtils
import com.folderspan.utils.SyncSnapshotChangeNotifier
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.time.Duration.Companion.milliseconds

private const val TARGET_ENDPOINT_CONNECT_WAIT_MS = 5_000L

class SyncState(
    private val taskStore: SyncTaskStore,
) : KoinComponent {
    private val fileState by inject<FileState>()
    private val deviceState by inject<DeviceState>()
    private val networkState by inject<NetworkState>()
    private val taskState by inject<TaskState>()
    private val endpointFiles = SyncEndpointFileOperations()

    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainScope = MainScope()
    private val runLocks = mutableMapOf<Long, Mutex>()
    private val runningOperationTasks = mutableMapOf<Long, Task>()

    val tasks = androidx.compose.runtime.mutableStateListOf<SyncTask>()
    val runHistory = androidx.compose.runtime.mutableStateMapOf<Long, List<SyncRunRecord>>()

    var revision by mutableIntStateOf(0)
        private set

    init {
        mainScope.launch {
            loadTasks()
        }
        startScheduler()
    }

    private fun notifyChanged() {
        revision++
    }

    private fun normalizePath(path: String): String {
        return path.trim().ifBlank { PathUtils.getPathSeparator() }
    }

    fun availableEndpoints(): List<SyncEndpoint> {
        val result = mutableListOf<SyncEndpoint>()
        result.add(
            SyncEndpoint(
                type = SyncEndpointType.Local,
                ref = "",
                label = AppStrings.ui_local,
                canRead = true,
                canWrite = true,
            )
        )
        result.addAll(
            deviceState.devices.map { item ->
                val permission = item.menuPermission
                SyncEndpoint(
                    type = SyncEndpointType.Device,
                    ref = item.id,
                    label = item.name,
                    canRead = permission.copy,
                    canWrite = permission.paste,
                )
            }
        )
        result.addAll(
            networkState.entries.map { entry ->
                val network = entry.network
                val permission = network.menuPermission
                val label = "${network.name} (${network.protocol})"
                SyncEndpoint(
                    type = SyncEndpointType.Network,
                    ref = network.buildProtocolId(),
                    label = label,
                    canRead = permission.copy,
                    canWrite = permission.paste,
                )
            }
        )
        return result
    }

    private fun endpointReadWrite(type: SyncEndpointType, ref: String): Pair<Boolean, Boolean>? {
        return when (type) {
            SyncEndpointType.Local -> true to true
            SyncEndpointType.Device -> {
                val permission = deviceState.devices.firstOrNull { item -> item.id == ref }?.menuPermission
                if (permission == null) null else permission.copy to permission.paste
            }

            SyncEndpointType.Network -> {
                val permission = networkState.networks.firstOrNull { item -> item.buildProtocolId() == ref }?.menuPermission
                if (permission == null) null else permission.copy to permission.paste
            }
        }
    }

    private fun endpointSeparatorForValidation(type: SyncEndpointType, ref: String): String {
        return when (type) {
            SyncEndpointType.Local -> PathUtils.getPathSeparator()
            SyncEndpointType.Device -> {
                deviceState.devices.firstOrNull { item -> item.id == ref }?.pathSeparator ?: PathUtils.getPathSeparator()
            }

            SyncEndpointType.Network -> {
                networkState.networks.firstOrNull { item -> item.buildProtocolId() == ref }?.pathSeparator ?: "/"
            }
        }
    }

    fun validateTask(
        name: String,
        sourceType: SyncEndpointType,
        sourceRef: String,
        sourcePath: String,
        targetType: SyncEndpointType,
        targetRef: String,
        targetPath: String,
        strictEndpointAvailability: Boolean = true,
    ): String? {
        if (name.isBlank()) return AppStrings.ui_task_name_cannot_empty
        if (sourcePath.isBlank()) return AppStrings.ui_source_path_cannot_empty
        if (targetPath.isBlank()) return AppStrings.ui_target_path_cannot_empty
        if (sourceType != SyncEndpointType.Local && sourceRef.isBlank()) {
            return AppStrings.ui_please_select_source_endpoint_first
        }
        if (targetType != SyncEndpointType.Local && targetRef.isBlank()) {
            return AppStrings.ui_please_select_target_endpoint_first
        }

        val normalizedSourcePath = normalizePath(sourcePath)
        val normalizedTargetPath = normalizePath(targetPath)
        if (
            sourceType == targetType &&
            sourceRef == targetRef &&
            normalizedSourcePath == normalizedTargetPath
        ) {
            return AppStrings.ui_source_target_cannot_same
        }
        if (sourceType == targetType && sourceRef == targetRef) {
            val separator = endpointSeparatorForValidation(sourceType, sourceRef)
            if (syncTargetInsideSourcePath(normalizedSourcePath, normalizedTargetPath, separator)) {
                return AppStrings.ui_target_path_cannot_within_source_directory
            }
        }

        if (strictEndpointAvailability) {
            if (sourceType == SyncEndpointType.Device && deviceState.devices.none { item -> item.id == sourceRef }) {
                return AppStrings.ui_source_device_does_not_exist_not_connected
            }
            if (targetType == SyncEndpointType.Device && deviceState.devices.none { item -> item.id == targetRef }) {
                return AppStrings.ui_target_device_does_not_exist_not_connected
            }
            if (sourceType == SyncEndpointType.Network && networkState.networks.none { it.buildProtocolId() == sourceRef }) {
                return AppStrings.ui_source_network_disk_does_not_exist
            }
            if (targetType == SyncEndpointType.Network && networkState.networks.none { it.buildProtocolId() == targetRef }) {
                return AppStrings.ui_target_network_disk_does_not_exist
            }

            val sourceReadWrite = endpointReadWrite(sourceType, sourceRef)
                ?: return AppStrings.ui_source_endpoint_unavailable
            val targetReadWrite = endpointReadWrite(targetType, targetRef)
                ?: return AppStrings.ui_target_endpoint_unavailable

            if (!sourceReadWrite.first) return AppStrings.ui_source_endpoint_does_not_support_reading
            if (!targetReadWrite.second) return AppStrings.ui_target_endpoint_does_not_support_writing
        }

        return null
    }

    suspend fun createOrUpdateTask(task: SyncTask): Result<SyncTask> {
        val error = validateTask(
            name = task.name,
            sourceType = task.sourceType,
            sourceRef = task.sourceRef,
            sourcePath = task.sourcePath,
            targetType = task.targetType,
            targetRef = task.targetRef,
            targetPath = task.targetPath,
            strictEndpointAvailability = task.id <= 0L,
        )
        if (error != null) {
            return Result.failure(IllegalArgumentException(error))
        }

        val normalizedIgnoreFileNames = normalizeSupportedIgnoreFileNames(task.ignoreFileNames)
        if (task.useIgnoreFiles) {
            val unsupported = task.ignoreFileNames.firstOrNull { fileName ->
                fileName !in SupportedIgnoreFileNames
            }
            if (unsupported != null) {
                return Result.failure(
                    IllegalArgumentException(
                        AppStrings.ui_unsupported_ignore_file_arg0.format(arg0 = unsupported)
                    )
                )
            }
            if (normalizedIgnoreFileNames.isEmpty()) {
                return Result.failure(
                    IllegalArgumentException(AppStrings.ui_select_at_least_one_ignore_file)
                )
            }
            val discovered = discoverSourceIgnoreFileNames(
                sourceType = task.sourceType,
                sourceRef = task.sourceRef,
                sourcePath = task.sourcePath,
            ).getOrElse { error -> return Result.failure(error) }
            val missing = normalizedIgnoreFileNames.firstOrNull { fileName -> fileName !in discovered }
            if (missing != null) {
                return Result.failure(
                    IllegalArgumentException(
                        AppStrings.ui_ignore_file_not_found_in_source_arg0.format(arg0 = missing)
                    )
                )
            }
        }

        val now = nowMillis()
        val normalized = task.copy(
            id = task.id.coerceAtLeast(0L),
            sourcePath = normalizePath(task.sourcePath),
            targetPath = normalizePath(task.targetPath),
            ignoreFileNames = normalizedIgnoreFileNames,
            createdAt = if (task.createdAt > 0L) task.createdAt else now,
            updatedAt = now,
            nextRunAt = computeNextRun(task.copy(updatedAt = now), now),
        )

        val saved = saveTaskToStore(normalized)

        replaceOrAddTask(saved)
        notifyChanged()
        SyncSnapshotChangeNotifier.onSyncTasksConfigurationChanged()
        return Result.success(saved)
    }

    suspend fun discoverSourceIgnoreFileNames(
        sourceType: SyncEndpointType,
        sourceRef: String,
        sourcePath: String,
    ): Result<List<String>> {
        val sourceDesk = resolveEndpoint(sourceType, sourceRef)
            ?: return Result.failure(IllegalStateException(AppStrings.ui_source_endpoint_unavailable))
        val normalizedSourcePath = normalizePath(sourcePath)
        val sourceInfo = endpointFiles.readPathInfo(
            type = sourceType,
            ref = sourceRef,
            desk = sourceDesk,
            path = normalizedSourcePath,
        ).getOrElse { error -> return Result.failure(error) }
        if (sourceInfo?.isDirectory != true) {
            return Result.failure(
                IllegalArgumentException(AppStrings.ui_advanced_ignore_requires_directory_source)
            )
        }
        return endpointFiles.listChildren(
            type = sourceType,
            ref = sourceRef,
            desk = sourceDesk,
            path = normalizedSourcePath,
        ).map(::findExistingSupportedIgnoreFileNames)
    }

    fun newTaskTemplate(): SyncTask {
        val now = nowMillis()
        return SyncTask(
            id = 0,
            name = "",
            sourceType = SyncEndpointType.Local,
            sourceRef = "",
            sourcePath = PathUtils.getHomePath(),
            targetType = SyncEndpointType.Local,
            targetRef = "",
            targetPath = PathUtils.getHomePath(),
            createdAt = now,
            updatedAt = now,
        )
    }

    fun getTask(taskId: Long): SyncTask? {
        return tasks.firstOrNull { item -> item.id == taskId }
    }

    fun snapshotTasks(): List<SyncTask> = tasks.toList()

    fun duplicateTask(taskId: Long) {
        val source = getTask(taskId) ?: return
        val now = nowMillis()
        val duplicate = source.copy(
            id = 0,
            name = AppStrings.sync_copy_name_arg0.format(arg0 = source.name),
            lastRunAt = 0,
            nextRunAt = 0,
            lastStatus = SyncRunStatus.Idle,
            lastMessage = "",
            createdAt = now,
            updatedAt = now,
        )

        mainScope.launch {
            createOrUpdateTask(duplicate)
        }
    }

    fun deleteTask(taskId: Long) {
        tasks.removeAll { item -> item.id == taskId }
        runHistory.remove(taskId)
        runLocks.remove(taskId)
        runningOperationTasks.remove(taskId)
        deleteTaskFromStoreAsync(taskId)
        notifyChanged()
        SyncSnapshotChangeNotifier.onSyncTasksConfigurationChanged()
    }

    fun updateEnabled(taskId: Long, enabled: Boolean) {
        val index = tasks.indexOfFirst { item -> item.id == taskId }
        if (index < 0) return

        val current = tasks[index]
        val now = nowMillis()
        tasks[index] = current.copy(
            enabled = enabled,
            updatedAt = now,
            nextRunAt = computeNextRun(current.copy(enabled = enabled), now)
        )
        saveTaskToStoreAsync(tasks[index])
        notifyChanged()
        SyncSnapshotChangeNotifier.onSyncTasksConfigurationChanged()
    }

    private fun computeNextRun(task: SyncTask, now: Long = nowMillis()): Long {
        if (!task.enabled) return 0
        return when (task.scheduleType) {
            SyncScheduleType.Manual -> 0
            SyncScheduleType.Interval -> {
                val minutes = task.intervalMinutes.coerceAtLeast(1)
                now + minutes * 60_000L
            }
        }
    }

    fun recordsFor(taskId: Long): List<SyncRunRecord> {
        return runHistory[taskId].orEmpty()
    }

    fun clearRunRecords(taskId: Long) {
        if (!runHistory.containsKey(taskId)) return
        runHistory.remove(taskId)
        deleteRunsFromStoreAsync(taskId)
        notifyChanged()
    }

    fun getRunRecord(taskId: Long, runId: Long): SyncRunRecord? {
        return runHistory[taskId].orEmpty().firstOrNull { item -> item.runId == runId }
    }

    fun runNow(taskId: Long) {
        val task = getTask(taskId) ?: return
        scope.launch {
            runTaskInternal(task, trigger = "manual")
        }
    }

    /** Atomically marks a manual run accepted so repeated MCP requests cannot enqueue duplicates. */
    fun runNowIfIdle(taskId: Long): SyncTask? {
        val index = tasks.indexOfFirst { item -> item.id == taskId }
        if (index < 0) return null
        val current = tasks[index]
        if (
            current.lastStatus == SyncRunStatus.Running ||
            current.lastStatus == SyncRunStatus.Queued ||
            runningOperationTasks.containsKey(taskId)
        ) {
            return null
        }
        val accepted = current.copy(
            lastStatus = SyncRunStatus.Queued,
            lastMessage = "",
            updatedAt = nowMillis(),
        )
        tasks[index] = accepted
        saveTaskToStoreAsync(accepted)
        notifyChanged()
        scope.launch { runTaskInternal(accepted, trigger = "manual") }
        return accepted
    }

    fun retryFailedItems(taskId: Long, runId: Long) {
        val record = getRunRecord(taskId, runId) ?: return
        if (record.failureCount <= 0) return
        runNow(taskId)
    }

    fun cancelActiveRun(taskId: Long) {
        val operationTask = runningOperationTasks[taskId]
        if (operationTask != null) {
            taskState.requestCancel(operationTask, AppStrings.ui_user_cancels_sync)
        }

        val index = tasks.indexOfFirst { item -> item.id == taskId }
        if (index < 0) return

        val task = tasks[index]
        if (task.lastStatus == SyncRunStatus.Running || task.lastStatus == SyncRunStatus.Queued) {
            tasks[index] = task.copy(
                lastStatus = SyncRunStatus.Canceled,
                lastMessage = AppStrings.ui_canceled,
                updatedAt = nowMillis(),
                nextRunAt = computeNextRun(task),
            )
            saveTaskToStoreAsync(tasks[index])
            notifyChanged()
        }
    }

    private suspend fun runTaskInternal(task: SyncTask, trigger: String) {
        val lock = runLocks.getOrPut(task.id) { Mutex() }
        if (!lock.tryLock()) {
            updateTaskStatus(task.id, SyncRunStatus.Queued, AppStrings.ui_task_already_running_repeated_triggering_ignored)
            return
        }

        var operationTask: Task? = null

        try {
            val current = getTask(task.id) ?: return
            val targetMissingError = targetEndpointMissingError(current)
            if (targetMissingError != null) {
                val record = skippedRunRecord(
                    task = current,
                    trigger = trigger,
                    message = syncSkippedMessage(targetMissingError),
                )
                onTaskFinished(current, record)
                return
            }

            val targetConnectError = connectTargetEndpointIfNeeded(current)
            if (targetConnectError != null) {
                val record = skippedRunRecord(
                    task = current,
                    trigger = trigger,
                    message = syncSkippedMessage(targetConnectError),
                )
                onTaskFinished(current, record)
                return
            }

            operationTask = createOperationTask(current)
            runningOperationTasks[current.id] = operationTask
            taskState.addOrUpdate(operationTask)

            updateTaskStatus(current.id, SyncRunStatus.Running, AppStrings.ui_synchronously_executing)
            val record = executeTask(current, trigger, operationTask)
            onTaskFinished(current, record)
            finalizeOperationTask(operationTask, record)
        } finally {
            operationTask?.let { item ->
                runningOperationTasks.remove(task.id)
                taskState.clearSignals(item.key)
            }
            lock.unlock()
        }
    }

    private fun skippedRunRecord(
        task: SyncTask,
        trigger: String,
        message: String,
    ): SyncRunRecord {
        val now = nowMillis()
        return SyncRunRecord(
            runId = now,
            taskId = task.id,
            trigger = trigger,
            startedAt = now,
            endedAt = now,
            status = SyncRunStatus.Failure,
            totalCount = 1,
            successCount = 0,
            failureCount = 1,
            message = message,
            items = listOf(SyncRunItem(path = task.targetPath, success = false, message = message)),
        )
    }

    private fun createOperationTask(task: SyncTask): Task {
        return Task(
            taskType = TaskType.Copy,
            status = StatusEnum.LOADING,
            values = mapOf("path" to task.sourcePath),
            protocol = toProtocol(task.sourceType),
            protocolId = task.sourceRef,
        )
    }

    private fun finalizeOperationTask(operationTask: Task, record: SyncRunRecord) {
        when (record.status) {
            SyncRunStatus.Success -> {
                taskState.delete(operationTask)
            }

            SyncRunStatus.PartialSuccess,
            SyncRunStatus.Failure,
            SyncRunStatus.Canceled,
            SyncRunStatus.Queued,
            SyncRunStatus.Running,
            SyncRunStatus.Idle,
            -> {
                val message = when (record.status) {
                    SyncRunStatus.Canceled -> AppStrings.ui_sync_canceled
                    else -> record.message
                }
                taskState.putResult(operationTask, "sync_result", message)
                taskState.updateStatus(operationTask, StatusEnum.FAILURE)
            }
        }
    }

    private suspend fun updateTaskStatus(taskId: Long, status: SyncRunStatus, message: String) {
        var updatedTask: SyncTask? = null
        withContext(Dispatchers.Main) {
            val index = tasks.indexOfFirst { item -> item.id == taskId }
            if (index < 0) return@withContext

            val task = tasks[index]
            val nextTask = task.copy(
                lastStatus = status,
                lastMessage = message,
                updatedAt = nowMillis(),
            )
            updatedTask = nextTask
            tasks[index] = nextTask
            notifyChanged()
        }

        updatedTask?.let { task ->
            saveTaskToStore(task)
        }
    }

    private suspend fun ensureOperationRunning(taskKey: Long) {
        if (!taskState.awaitIfPaused(taskKey)) {
            throw CancellationException(AppStrings.message_task_cancelled)
        }
        if (taskState.isTaskCancelled(taskKey)) {
            throw CancellationException(AppStrings.message_task_cancelled)
        }
    }

    private fun appendRunItem(
        items: MutableList<SyncRunItem>,
        path: String,
        success: Boolean,
        message: String,
    ) {
        if (items.size >= RUN_ITEM_LIMIT) return
        items.add(
            SyncRunItem(
                path = path,
                success = success,
                message = sanitizeSyncMessage(message),
            )
        )
    }

    private suspend fun copyOnce(
        task: SyncTask,
        operationTask: Task,
        sourceDesk: DiskBase,
        targetDesk: DiskBase,
        sourceFile: FileSimpleInfo,
        destinationPath: String,
    ): Result<Boolean> {
        val source = sourceFile.withCopy(
            protocol = toProtocol(task.sourceType),
            protocolId = task.sourceRef,
        )
        val destination = sourceFile.withCopy(
            path = destinationPath,
            protocol = toProtocol(task.targetType),
            protocolId = task.targetRef,
        )
        taskState.putTransientResult(operationTask, destinationPath, AppStrings.ui_start_syncing)

        ensureOperationRunning(operationTask.key)
        val result = fileState.runCopyAcrossEndpoints(
            task = operationTask,
            src = source,
            dest = destination,
            sourceDesk = sourceDesk,
            targetDesk = targetDesk,
        )

        if (result.isSuccess && result.getOrDefault(false)) {
            taskState.putTransientResult(operationTask, destinationPath, AppStrings.ui_synchronization_completed)
            return Result.success(true)
        }

        val error = result.exceptionOrNull()
        if (error is CancellationException && taskState.isTaskCancelled(operationTask.key)) {
            return Result.failure(error)
        }

        return Result.failure(error ?: IllegalStateException(AppStrings.ui_copy_failed))
    }

    private suspend fun collectSourceSnapshot(
        task: SyncTask,
        sourceDesk: DiskBase,
        sourceRoot: FileSimpleInfo,
        filters: List<String>,
    ): Result<SyncSourceSnapshot> {
        if (!sourceRoot.isDirectory) {
            val relative = fileNameOf(
                sourceRoot.path,
                endpointFiles.endpointSeparator(task.sourceType, sourceDesk),
            )
            return if (syncFilterMatches(relative, sourceRoot.name, filters)) {
                Result.success(SyncSourceSnapshot(files = emptyList(), directories = emptyList()))
            } else {
                Result.success(
                    SyncSourceSnapshot(
                        files = listOf(SyncSourceFileEntry(relativePath = relative, file = sourceRoot)),
                        directories = emptyList(),
                    )
                )
            }
        }

        val files = mutableListOf<SyncSourceFileEntry>()
        val directories = mutableListOf<String>()

        val queue = ArrayDeque<Pair<String, String>>()
        queue.addLast(sourceRoot.path to "")

        while (queue.isNotEmpty()) {
            val (currentPath, relativePrefix) = queue.removeFirst()
            val listResult = endpointFiles.listChildren(
                type = task.sourceType,
                ref = task.sourceRef,
                desk = sourceDesk,
                path = currentPath,
            )
            if (listResult.isFailure) {
                return Result.failure(
                    listResult.exceptionOrNull() ?: IllegalStateException(AppStrings.ui_failed_read_source_directory)
                )
            }

            val children = listResult.getOrDefault(emptyList())
            for (child in children) {
                val relativePath = if (relativePrefix.isBlank()) {
                    child.name
                } else {
                    "$relativePrefix/${child.name}"
                }

                if (syncFilterMatches(relativePath, child.name, filters)) {
                    continue
                }

                if (child.isDirectory) {
                    if (task.includeSubdirectories) {
                        directories.add(relativePath)
                        queue.addLast(child.path to relativePath)
                    }
                } else {
                    files.add(SyncSourceFileEntry(relativePath = relativePath, file = child))
                }
            }

            if (!task.includeSubdirectories) {
                queue.clear()
            }
        }

        val directoryList = if (task.includeEmptyDirectories) {
            directories
        } else {
            directories.filter { dirPath ->
                files.any { entry -> entry.relativePath.startsWith("$dirPath/") }
            }
        }

        return Result.success(
            SyncSourceSnapshot(
                files = files.sortedBy { item -> item.relativePath },
                directories = directoryList.sortedBy { item -> item.length },
            )
        )
    }

    private suspend fun collectTargetSnapshot(
        task: SyncTask,
        targetDesk: DiskBase,
        targetRootPath: String,
    ): Result<SyncTargetSnapshot> {
        val filesByRelative = mutableMapOf<String, FileSimpleInfo>()
        val filesByAbsolute = mutableMapOf<String, FileSimpleInfo>()
        val directoryPaths = mutableSetOf<String>()
        val occupiedPaths = mutableSetOf<String>()

        val targetSeparator = endpointFiles.endpointSeparator(task.targetType, targetDesk)
        val rootResult = endpointFiles.readPathInfo(task.targetType, task.targetRef, targetDesk, targetRootPath)
        if (rootResult.isFailure) {
            return Result.failure(rootResult.exceptionOrNull() ?: IllegalStateException(AppStrings.ui_failed_read_target_path))
        }

        val rootInfo = rootResult.getOrNull()
        if (rootInfo != null) {
            val rootAbsolute = normalizePathForCompare(rootInfo.path, targetSeparator)
            occupiedPaths.add(rootAbsolute)
            if (rootInfo.isDirectory) {
                directoryPaths.add(rootAbsolute)
            } else {
                filesByAbsolute[rootAbsolute] = rootInfo
            }
        }

        if (rootInfo?.isDirectory != true) {
            return Result.success(
                SyncTargetSnapshot(
                    rootInfo = rootInfo,
                    filesByRelative = filesByRelative,
                    filesByAbsolute = filesByAbsolute,
                    directoryPaths = directoryPaths,
                    occupiedPaths = occupiedPaths,
                )
            )
        }

        val queue = ArrayDeque<Pair<String, String>>()
        queue.addLast(targetRootPath to "")

        while (queue.isNotEmpty()) {
            val (currentPath, relativePrefix) = queue.removeFirst()
            val listResult = endpointFiles.listChildren(
                type = task.targetType,
                ref = task.targetRef,
                desk = targetDesk,
                path = currentPath,
            )
            if (listResult.isFailure) {
                return Result.failure(listResult.exceptionOrNull() ?: IllegalStateException(AppStrings.ui_read_the_target_directory_failed))
            }

            val children = listResult.getOrDefault(emptyList())
            for (child in children) {
                val relativePath = if (relativePrefix.isBlank()) {
                    child.name
                } else {
                    "$relativePrefix/${child.name}"
                }
                val absolutePath = normalizePathForCompare(child.path, targetSeparator)
                occupiedPaths.add(absolutePath)

                if (child.isDirectory) {
                    directoryPaths.add(absolutePath)
                    if (task.includeSubdirectories) {
                        queue.addLast(child.path to relativePath)
                    }
                } else {
                    filesByRelative[relativePath] = child
                    filesByAbsolute[absolutePath] = child
                }
            }
        }

        return Result.success(
            SyncTargetSnapshot(
                rootInfo = rootInfo,
                filesByRelative = filesByRelative,
                filesByAbsolute = filesByAbsolute,
                directoryPaths = directoryPaths,
                occupiedPaths = occupiedPaths,
            )
        )
    }

    private suspend fun executeDirectorySync(
        task: SyncTask,
        sourceDesk: DiskBase,
        targetDesk: DiskBase,
        sourceSnapshot: SyncSourceSnapshot,
        operationTask: Task,
    ): SyncExecutionSummary {
        val targetSeparator = endpointFiles.endpointSeparator(task.targetType, targetDesk)
        var targetRootPath = normalizePathForCompare(task.targetPath, targetSeparator)

        val snapshotResult = collectTargetSnapshot(task, targetDesk, targetRootPath)
        if (snapshotResult.isFailure) {
            val error = snapshotResult.exceptionOrNull()?.message ?: AppStrings.ui_failed_read_target_path
            return SyncExecutionSummary(
                status = SyncRunStatus.Failure,
                successCount = 0,
                failureCount = 1,
                message = sanitizeSyncMessage(error),
                items = listOf(SyncRunItem(path = targetRootPath, success = false, message = sanitizeSyncMessage(error))),
            )
        }

        val targetSnapshot = snapshotResult.getOrThrow()
        val items = mutableListOf<SyncRunItem>()

        var successCount = 0
        var failureCount = 0
        var canceled = false

        if (targetSnapshot.rootInfo != null && !targetSnapshot.rootInfo.isDirectory) {
            when (task.conflictPolicy) {
                SyncConflictPolicy.Skip -> {
                    val msg = AppStrings.ui_target_path_file_has_been_skipped_according_policy
                    appendRunItem(items, targetRootPath, true, msg)
                    return SyncExecutionSummary(
                        status = SyncRunStatus.Success,
                        successCount = 1,
                        failureCount = 0,
                        message = msg,
                        items = items,
                    )
                }

                SyncConflictPolicy.Rename -> {
                    targetRootPath = buildSyncRenamePath(
                        path = targetRootPath,
                        isDirectory = true,
                        occupied = targetSnapshot.occupiedPaths,
                        separator = targetSeparator,
                    )
                }

                SyncConflictPolicy.Replace -> {
                    val rootAbsolute = normalizePathForCompare(targetSnapshot.rootInfo.path, targetSeparator)
                    val deleteResult = endpointFiles.deletePath(
                        type = task.targetType,
                        desk = targetDesk,
                        path = rootAbsolute,
                        isDirectory = false,
                    )
                    if (deleteResult.isFailure || !deleteResult.getOrDefault(false)) {
                        val msg = deleteResult.exceptionOrNull()?.message ?: AppStrings.ui_unable_delete_target_conflict_file
                        appendRunItem(items, rootAbsolute, false, msg)
                        return SyncExecutionSummary(
                            status = SyncRunStatus.Failure,
                            successCount = 0,
                            failureCount = 1,
                            message = sanitizeSyncMessage(msg),
                            items = items,
                        )
                    }

                    targetSnapshot.filesByAbsolute.remove(rootAbsolute)
                    targetSnapshot.occupiedPaths.remove(rootAbsolute)
                    appendRunItem(items, rootAbsolute, true, AppStrings.ui_conflicting_files_removed)
                }
            }
        }

        val ensureRootResult = endpointFiles.ensureDirectoryExists(
            type = task.targetType,
            ref = task.targetRef,
            desk = targetDesk,
            path = targetRootPath,
            knownDirectories = targetSnapshot.directoryPaths,
            occupiedPaths = targetSnapshot.occupiedPaths,
        )
        if (ensureRootResult.isFailure) {
            val msg = ensureRootResult.exceptionOrNull()?.message ?: AppStrings.ui_failed_create_target_root_directory
            appendRunItem(items, targetRootPath, false, msg)
            return SyncExecutionSummary(
                status = SyncRunStatus.Failure,
                successCount = 0,
                failureCount = 1,
                message = sanitizeSyncMessage(msg),
                items = items,
            )
        }

        val dirWork = if (task.includeEmptyDirectories) sourceSnapshot.directories.size else 0
        val totalWork = sourceSnapshot.files.size + dirWork
        taskState.putValue(operationTask, "progressMax", totalWork.toString())
        var progress = 0

        if (task.includeEmptyDirectories) {
            for (relativeDir in sourceSnapshot.directories) {
                try {
                    ensureOperationRunning(operationTask.key)
                } catch (_: CancellationException) {
                    canceled = true
                    break
                }

                val targetRelative = relativeDir.replace('/', targetSeparator.first())
                val directoryPath = joinPath(targetRootPath, targetRelative, targetSeparator)
                val ensureDirectoryResult = endpointFiles.ensureDirectoryExists(
                    type = task.targetType,
                    ref = task.targetRef,
                    desk = targetDesk,
                    path = directoryPath,
                    knownDirectories = targetSnapshot.directoryPaths,
                    occupiedPaths = targetSnapshot.occupiedPaths,
                )

                if (ensureDirectoryResult.isFailure) {
                    val msg = ensureDirectoryResult.exceptionOrNull()?.message ?: AppStrings.ui_failed_create_directory
                    appendRunItem(items, directoryPath, false, msg)
                    failureCount++
                } else {
                    appendRunItem(items, directoryPath, true, AppStrings.ui_directory_ready)
                    successCount++
                }

                progress++
                taskState.putValue(operationTask, "progressCur", progress.toString())
            }
        }

        if (!canceled) {
            for ((relativePath, file) in sourceSnapshot.files) {
                try {
                    ensureOperationRunning(operationTask.key)
                } catch (_: CancellationException) {
                    canceled = true
                    break
                }

                val targetRelative = relativePath.replace('/', targetSeparator.first())
                val preferredTargetPath = joinPath(targetRootPath, targetRelative, targetSeparator)
                var finalTargetPath = preferredTargetPath
                val preferredAbsolute = normalizePathForCompare(preferredTargetPath, targetSeparator)

                val existingFile = targetSnapshot.filesByAbsolute[preferredAbsolute]
                val existingDirectory = targetSnapshot.directoryPaths.contains(preferredAbsolute)

                if (existingFile != null && !syncFileChanged(file, existingFile)) {
                    appendRunItem(items, preferredTargetPath, true, AppStrings.ui_no_changes_skipped)
                    successCount++
                    progress++
                    taskState.putValue(operationTask, "progressCur", progress.toString())
                    continue
                }

                if (existingDirectory || existingFile != null) {
                    when (task.conflictPolicy) {
                        SyncConflictPolicy.Skip -> {
                            appendRunItem(items, preferredTargetPath, true, AppStrings.ui_conflict_exists_skipped)
                            successCount++
                            progress++
                            taskState.putValue(operationTask, "progressCur", progress.toString())
                            continue
                        }

                        SyncConflictPolicy.Rename -> {
                            finalTargetPath = buildSyncRenamePath(
                                path = preferredTargetPath,
                                isDirectory = false,
                                occupied = targetSnapshot.occupiedPaths,
                                separator = targetSeparator,
                            )
                        }

                        SyncConflictPolicy.Replace -> {
                            if (existingDirectory) {
                                val deleteResult = endpointFiles.deletePath(
                                    type = task.targetType,
                                    desk = targetDesk,
                                    path = preferredAbsolute,
                                    isDirectory = true,
                                )
                                if (deleteResult.isFailure || !deleteResult.getOrDefault(false)) {
                                    val msg = deleteResult.exceptionOrNull()?.message ?: AppStrings.ui_failed_delete_conflicting_directory
                                    appendRunItem(items, preferredTargetPath, false, msg)
                                    failureCount++
                                    progress++
                                    taskState.putValue(operationTask, "progressCur", progress.toString())
                                    continue
                                }
                                targetSnapshot.directoryPaths.remove(preferredAbsolute)
                                targetSnapshot.occupiedPaths.remove(preferredAbsolute)
                            }
                        }
                    }
                }

                val parentPath = parentPathOf(finalTargetPath, targetSeparator)
                val ensureParentResult = endpointFiles.ensureDirectoryExists(
                    type = task.targetType,
                    ref = task.targetRef,
                    desk = targetDesk,
                    path = parentPath,
                    knownDirectories = targetSnapshot.directoryPaths,
                    occupiedPaths = targetSnapshot.occupiedPaths,
                )
                if (ensureParentResult.isFailure) {
                    val msg = ensureParentResult.exceptionOrNull()?.message ?: AppStrings.ui_failed_create_parent_directory
                    appendRunItem(items, finalTargetPath, false, msg)
                    failureCount++
                    progress++
                    taskState.putValue(operationTask, "progressCur", progress.toString())
                    continue
                }

                taskState.putValue(operationTask, "path", finalTargetPath)
                val copyResult = copyOnce(
                    task = task,
                    operationTask = operationTask,
                    sourceDesk = sourceDesk,
                    targetDesk = targetDesk,
                    sourceFile = file,
                    destinationPath = finalTargetPath,
                )

                if (copyResult.isSuccess && copyResult.getOrDefault(false)) {
                    appendRunItem(items, finalTargetPath, true, AppStrings.ui_synchronization_completed)
                    successCount++
                    val finalAbsolute = normalizePathForCompare(finalTargetPath, targetSeparator)
                    targetSnapshot.occupiedPaths.add(finalAbsolute)
                    targetSnapshot.filesByAbsolute[finalAbsolute] = file.withCopy(
                        path = finalTargetPath,
                        protocol = toProtocol(task.targetType),
                        protocolId = task.targetRef,
                    )
                    if (finalAbsolute == preferredAbsolute) {
                        targetSnapshot.filesByRelative[relativePath] = file.withCopy(
                            path = finalTargetPath,
                            protocol = toProtocol(task.targetType),
                            protocolId = task.targetRef,
                        )
                    }
                } else {
                    val msg = copyResult.exceptionOrNull()?.message ?: AppStrings.ui_sync_failed
                    appendRunItem(items, finalTargetPath, false, msg)
                    failureCount++
                    taskState.putResult(operationTask, finalTargetPath, sanitizeSyncMessage(msg))
                }

                progress++
                taskState.putValue(operationTask, "progressCur", progress.toString())
            }
        }

        val status = when {
            canceled -> SyncRunStatus.Canceled
            failureCount == 0 -> SyncRunStatus.Success
            successCount > 0 -> SyncRunStatus.PartialSuccess
            else -> SyncRunStatus.Failure
        }

        val message = when (status) {
            SyncRunStatus.Canceled -> AppStrings.ui_sync_canceled
            SyncRunStatus.Success -> if (successCount == 0) {
                AppStrings.ui_no_changes
            } else {
                AppStrings.ui_synchronization_successful
            }
            SyncRunStatus.PartialSuccess -> AppStrings.ui_partially_successful
            SyncRunStatus.Failure -> AppStrings.ui_sync_failed
            else -> AppStrings.ui_synchronization_completed
        }

        return SyncExecutionSummary(
            status = status,
            successCount = successCount,
            failureCount = failureCount,
            message = message,
            items = items,
        )
    }

    private suspend fun executeSingleFileSync(
        task: SyncTask,
        sourceDesk: DiskBase,
        targetDesk: DiskBase,
        sourceFile: FileSimpleInfo,
        operationTask: Task,
    ): SyncExecutionSummary {
        val items = mutableListOf<SyncRunItem>()
        val targetSeparator = endpointFiles.endpointSeparator(task.targetType, targetDesk)
        val targetRootPath = normalizePathForCompare(task.targetPath, targetSeparator)

        val targetRootResult = endpointFiles.readPathInfo(task.targetType, task.targetRef, targetDesk, targetRootPath)
        if (targetRootResult.isFailure) {
            val msg = targetRootResult.exceptionOrNull()?.message ?: AppStrings.ui_failed_read_target_path
            appendRunItem(items, targetRootPath, false, msg)
            return SyncExecutionSummary(
                status = SyncRunStatus.Failure,
                successCount = 0,
                failureCount = 1,
                message = sanitizeSyncMessage(msg),
                items = items,
            )
        }

        val targetRootInfo = targetRootResult.getOrNull()
        val knownDirectories = mutableSetOf<String>()
        val occupiedPaths = mutableSetOf<String>()

        if (targetRootInfo != null) {
            val absolute = normalizePathForCompare(targetRootInfo.path, targetSeparator)
            occupiedPaths.add(absolute)
            if (targetRootInfo.isDirectory) {
                knownDirectories.add(absolute)

                val listResult = endpointFiles.listChildren(
                    type = task.targetType,
                    ref = task.targetRef,
                    desk = targetDesk,
                    path = targetRootInfo.path,
                )
                if (listResult.isSuccess) {
                    listResult.getOrDefault(emptyList()).forEach { child ->
                        occupiedPaths.add(normalizePathForCompare(child.path, targetSeparator))
                    }
                }
            }
        }

        val preferredTargetPath = if (targetRootInfo?.isDirectory == true) {
            joinPath(targetRootPath, sourceFile.name, targetSeparator)
        } else {
            targetRootPath
        }

        val preferredResult = endpointFiles.readPathInfo(task.targetType, task.targetRef, targetDesk, preferredTargetPath)
        if (preferredResult.isFailure) {
            val msg = preferredResult.exceptionOrNull()?.message ?: AppStrings.ui_failed_read_target_file
            appendRunItem(items, preferredTargetPath, false, msg)
            return SyncExecutionSummary(
                status = SyncRunStatus.Failure,
                successCount = 0,
                failureCount = 1,
                message = sanitizeSyncMessage(msg),
                items = items,
            )
        }

        val existing = preferredResult.getOrNull()
        var finalTargetPath = preferredTargetPath

        if (existing != null && !existing.isDirectory && !syncFileChanged(sourceFile, existing)) {
            appendRunItem(items, preferredTargetPath, true, AppStrings.ui_no_changes_skipped)
            return SyncExecutionSummary(
                status = SyncRunStatus.Success,
                successCount = 1,
                failureCount = 0,
                message = AppStrings.ui_no_changes,
                items = items,
            )
        }

        if (existing != null) {
            when (task.conflictPolicy) {
                SyncConflictPolicy.Skip -> {
                    appendRunItem(items, preferredTargetPath, true, AppStrings.ui_conflict_exists_skipped)
                    return SyncExecutionSummary(
                        status = SyncRunStatus.Success,
                        successCount = 1,
                        failureCount = 0,
                        message = AppStrings.ui_conflict_skipped,
                        items = items,
                    )
                }

                SyncConflictPolicy.Rename -> {
                    finalTargetPath = buildSyncRenamePath(
                        path = preferredTargetPath,
                        isDirectory = false,
                        occupied = occupiedPaths,
                        separator = targetSeparator,
                    )
                }

                SyncConflictPolicy.Replace -> {
                    if (existing.isDirectory) {
                        val deleteResult = endpointFiles.deletePath(
                            type = task.targetType,
                            desk = targetDesk,
                            path = preferredTargetPath,
                            isDirectory = true,
                        )
                        if (deleteResult.isFailure || !deleteResult.getOrDefault(false)) {
                            val msg = deleteResult.exceptionOrNull()?.message ?: AppStrings.ui_failed_delete_conflicting_directory
                            appendRunItem(items, preferredTargetPath, false, msg)
                            return SyncExecutionSummary(
                                status = SyncRunStatus.Failure,
                                successCount = 0,
                                failureCount = 1,
                                message = sanitizeSyncMessage(msg),
                                items = items,
                            )
                        }
                    }
                }
            }
        }

        taskState.putValue(operationTask, "progressMax", "1")
        taskState.putValue(operationTask, "progressCur", "0")

        val parent = parentPathOf(finalTargetPath, targetSeparator)
        val ensureParentResult = endpointFiles.ensureDirectoryExists(
            type = task.targetType,
            ref = task.targetRef,
            desk = targetDesk,
            path = parent,
            knownDirectories = knownDirectories,
            occupiedPaths = occupiedPaths,
        )
        if (ensureParentResult.isFailure) {
            val msg = ensureParentResult.exceptionOrNull()?.message ?: AppStrings.ui_failed_create_parent_directory
            appendRunItem(items, finalTargetPath, false, msg)
            return SyncExecutionSummary(
                status = SyncRunStatus.Failure,
                successCount = 0,
                failureCount = 1,
                message = sanitizeSyncMessage(msg),
                items = items,
            )
        }

        taskState.putValue(operationTask, "path", finalTargetPath)
        val copyResult = copyOnce(
            task = task,
            operationTask = operationTask,
            sourceDesk = sourceDesk,
            targetDesk = targetDesk,
            sourceFile = sourceFile,
            destinationPath = finalTargetPath,
        )

        taskState.putValue(operationTask, "progressCur", "1")

        return if (copyResult.isSuccess && copyResult.getOrDefault(false)) {
            appendRunItem(items, finalTargetPath, true, AppStrings.ui_synchronization_completed)
            SyncExecutionSummary(
                status = SyncRunStatus.Success,
                successCount = 1,
                failureCount = 0,
                message = AppStrings.ui_synchronization_successful,
                items = items,
            )
        } else {
            val msg = copyResult.exceptionOrNull()?.message ?: AppStrings.ui_sync_failed
            appendRunItem(items, finalTargetPath, false, msg)
            taskState.putResult(operationTask, finalTargetPath, sanitizeSyncMessage(msg))
            SyncExecutionSummary(
                status = SyncRunStatus.Failure,
                successCount = 0,
                failureCount = 1,
                message = sanitizeSyncMessage(msg),
                items = items,
            )
        }
    }

    private suspend fun executeTask(
        task: SyncTask,
        trigger: String,
        operationTask: Task,
    ): SyncRunRecord {
        val startedAt = nowMillis()

        val sourceDesk = resolveEndpoint(task.sourceType, task.sourceRef)
            ?: return SyncRunRecord(
                runId = startedAt,
                taskId = task.id,
                trigger = trigger,
                startedAt = startedAt,
                endedAt = nowMillis(),
                status = SyncRunStatus.Failure,
                totalCount = 1,
                successCount = 0,
                failureCount = 1,
                message = AppStrings.ui_source_endpoint_unavailable,
                items = listOf(SyncRunItem(task.sourcePath, false, AppStrings.ui_source_endpoint_unavailable)),
            )

        val targetDesk = resolveEndpoint(task.targetType, task.targetRef)
            ?: return SyncRunRecord(
                runId = startedAt,
                taskId = task.id,
                trigger = trigger,
                startedAt = startedAt,
                endedAt = nowMillis(),
                status = SyncRunStatus.Failure,
                totalCount = 1,
                successCount = 0,
                failureCount = 1,
                message = AppStrings.ui_target_endpoint_unavailable,
                items = listOf(SyncRunItem(task.targetPath, false, AppStrings.ui_target_endpoint_unavailable)),
            )

        return try {
            ensureOperationRunning(operationTask.key)

            val sourceInfoResult = endpointFiles.readPathInfo(
                type = task.sourceType,
                ref = task.sourceRef,
                desk = sourceDesk,
                path = task.sourcePath,
            )
            if (sourceInfoResult.isFailure) {
                val message = sourceInfoResult.exceptionOrNull()?.message ?: AppStrings.ui_failed_read_source_path
                return SyncRunRecord(
                    runId = startedAt,
                    taskId = task.id,
                    trigger = trigger,
                    startedAt = startedAt,
                    endedAt = nowMillis(),
                    status = SyncRunStatus.Failure,
                    totalCount = 1,
                    successCount = 0,
                    failureCount = 1,
                    message = sanitizeSyncMessage(message),
                    items = listOf(
                        SyncRunItem(
                            path = task.sourcePath,
                            success = false,
                            message = sanitizeSyncMessage(message),
                        )
                    ),
                )
            }

            val sourceRoot = sourceInfoResult.getOrNull() ?: return SyncRunRecord(
                runId = startedAt,
                taskId = task.id,
                trigger = trigger,
                startedAt = startedAt,
                endedAt = nowMillis(),
                status = SyncRunStatus.Failure,
                totalCount = 1,
                successCount = 0,
                failureCount = 1,
                message = AppStrings.ui_source_path_does_not_exist,
                items = listOf(SyncRunItem(path = task.sourcePath, success = false, message = AppStrings.ui_source_path_does_not_exist)),
            )
            if (sourceRoot.isDirectory && task.sourceType == task.targetType && task.sourceRef == task.targetRef) {
                val separator = endpointFiles.endpointSeparator(task.sourceType, sourceDesk)
                if (syncTargetInsideSourcePath(task.sourcePath, task.targetPath, separator)) {
                    return SyncRunRecord(
                        runId = startedAt,
                        taskId = task.id,
                        trigger = trigger,
                        startedAt = startedAt,
                        endedAt = nowMillis(),
                        status = SyncRunStatus.Failure,
                        totalCount = 1,
                        successCount = 0,
                        failureCount = 1,
                        message = AppStrings.ui_target_path_cannot_within_source_directory,
                        items = listOf(
                            SyncRunItem(
                                path = task.targetPath,
                                success = false,
                                message = AppStrings.ui_target_path_cannot_within_source_directory,
                            )
                        ),
                    )
                }
            }

            val filters = parseSyncFilterPatterns(task.filterRaw)
            val sourceSnapshotResult = collectSourceSnapshot(
                task = task,
                sourceDesk = sourceDesk,
                sourceRoot = sourceRoot,
                filters = filters,
            )
            if (sourceSnapshotResult.isFailure) {
                val message = sourceSnapshotResult.exceptionOrNull()?.message ?: AppStrings.ui_failed_read_source_directory
                return SyncRunRecord(
                    runId = startedAt,
                    taskId = task.id,
                    trigger = trigger,
                    startedAt = startedAt,
                    endedAt = nowMillis(),
                    status = SyncRunStatus.Failure,
                    totalCount = 1,
                    successCount = 0,
                    failureCount = 1,
                    message = sanitizeSyncMessage(message),
                    items = listOf(
                        SyncRunItem(
                            path = task.sourcePath,
                            success = false,
                            message = sanitizeSyncMessage(message),
                        )
                    ),
                )
            }

            val sourceSnapshot = sourceSnapshotResult.getOrThrow()

            val summary = if (sourceRoot.isDirectory) {
                executeDirectorySync(
                    task = task,
                    sourceDesk = sourceDesk,
                    targetDesk = targetDesk,
                    sourceSnapshot = sourceSnapshot,
                    operationTask = operationTask,
                )
            } else {
                val single = sourceSnapshot.files.firstOrNull()?.file ?: sourceRoot
                executeSingleFileSync(
                    task = task,
                    sourceDesk = sourceDesk,
                    targetDesk = targetDesk,
                    sourceFile = single,
                    operationTask = operationTask,
                )
            }

            SyncRunRecord(
                runId = startedAt,
                taskId = task.id,
                trigger = trigger,
                startedAt = startedAt,
                endedAt = nowMillis(),
                status = summary.status,
                totalCount = summary.successCount + summary.failureCount,
                successCount = summary.successCount,
                failureCount = summary.failureCount,
                message = summary.message,
                items = summary.items,
            )
        } catch (_: CancellationException) {
            SyncRunRecord(
                runId = startedAt,
                taskId = task.id,
                trigger = trigger,
                startedAt = startedAt,
                endedAt = nowMillis(),
                status = SyncRunStatus.Canceled,
                totalCount = 1,
                successCount = 0,
                failureCount = 1,
                message = AppStrings.ui_sync_canceled,
                items = listOf(SyncRunItem(task.targetPath, false, AppStrings.ui_sync_canceled)),
            )
        } catch (error: Throwable) {
            val message = sanitizeSyncMessage(error.message ?: AppStrings.ui_synchronization_exception)
            SyncRunRecord(
                runId = startedAt,
                taskId = task.id,
                trigger = trigger,
                startedAt = startedAt,
                endedAt = nowMillis(),
                status = SyncRunStatus.Failure,
                totalCount = 1,
                successCount = 0,
                failureCount = 1,
                message = message,
                items = listOf(SyncRunItem(task.targetPath, false, message)),
            )
        }
    }

    private suspend fun onTaskFinished(task: SyncTask, record: SyncRunRecord) {
        var updatedTask: SyncTask? = null
        withContext(Dispatchers.Main) {
            val existing = runHistory[task.id].orEmpty()
            runHistory[task.id] = (listOf(record) + existing).take(RUN_HISTORY_LIMIT)

            val index = tasks.indexOfFirst { item -> item.id == task.id }
            if (index >= 0) {
                val current = tasks[index]
                val now = nowMillis()
                val nextTask = current.copy(
                    lastRunAt = record.endedAt,
                    lastStatus = record.status,
                    lastMessage = record.message,
                    nextRunAt = computeNextRun(current, now),
                    updatedAt = now,
                )
                updatedTask = nextTask
                tasks[index] = nextTask
            }
            notifyChanged()
        }

        withContext(Dispatchers.Default) {
            taskStore.appendRun(record)
            updatedTask?.let { taskItem ->
                taskStore.saveTask(taskItem)
            }
        }
    }

    private fun resolveEndpoint(type: SyncEndpointType, ref: String): DiskBase? {
        return when (type) {
            SyncEndpointType.Local -> Local()
            SyncEndpointType.Device -> deviceState.devices.firstOrNull { item -> item.id == ref }
            SyncEndpointType.Network -> networkState.networks.firstOrNull { item -> item.buildProtocolId() == ref }
        }
    }

    private fun targetEndpointMissingError(task: SyncTask): String? {
        return when (task.targetType) {
            SyncEndpointType.Local -> null
            SyncEndpointType.Device -> {
                val exists = deviceState.devices.any { item -> item.id == task.targetRef } ||
                    deviceState.socketDevices.any { item -> item.id == task.targetRef }
                syncTargetEndpointMissingError(task.targetType, exists)
            }

            SyncEndpointType.Network -> {
                val exists = networkState.entries.any { item -> item.network.buildProtocolId() == task.targetRef }
                syncTargetEndpointMissingError(task.targetType, exists)
            }
        }
    }

    private suspend fun connectTargetEndpointIfNeeded(task: SyncTask): String? {
        return when (task.targetType) {
            SyncEndpointType.Local -> null
            SyncEndpointType.Device -> connectTargetDeviceIfNeeded(task)
            SyncEndpointType.Network -> connectTargetNetworkIfNeeded(task)
        }
    }

    private suspend fun connectTargetDeviceIfNeeded(task: SyncTask): String? {
        if (resolveEndpoint(SyncEndpointType.Device, task.targetRef) != null) return null
        val socketDevice = deviceState.socketDevices.firstOrNull { item -> item.id == task.targetRef }
            ?: return syncTargetEndpointMissingError(SyncEndpointType.Device, exists = false)

        val connectResult = runCatching {
            deviceState.connect(socketDevice)
        }
        if (connectResult.isFailure) {
            return syncConnectionFailureMessage(SyncEndpointType.Device, connectResult.exceptionOrNull())
        }

        val connected = waitForTargetEndpoint(task)
        return if (connected) null else syncConnectionFailureMessage(SyncEndpointType.Device, null)
    }

    private suspend fun connectTargetNetworkIfNeeded(task: SyncTask): String? {
        if (networkState.connectedEntries.any { item -> item.network.buildProtocolId() == task.targetRef }) return null
        val entry = networkState.entries.firstOrNull { item -> item.network.buildProtocolId() == task.targetRef }
            ?: return syncTargetEndpointMissingError(SyncEndpointType.Network, exists = false)
        val rootPath = entry.network.getRootPaths().firstOrNull()?.path ?: entry.network.pathSeparator

        val listResult = try {
            entry.network.getList(rootPath)
        } catch (error: Throwable) {
            return syncConnectionFailureMessage(SyncEndpointType.Network, error)
        }
        if (listResult.isFailure) {
            return syncConnectionFailureMessage(SyncEndpointType.Network, listResult.exceptionOrNull())
        }

        withContext(Dispatchers.Main) {
            networkState.connectEntry(entry)
        }
        return null
    }

    private suspend fun waitForTargetEndpoint(task: SyncTask): Boolean {
        return resolveEndpoint(task.targetType, task.targetRef) != null || withTimeoutOrNull(
            TARGET_ENDPOINT_CONNECT_WAIT_MS.milliseconds
        ) {
            while (resolveEndpoint(task.targetType, task.targetRef) == null) {
                delay(100.milliseconds)
            }
            true
        } == true
    }

    private fun syncConnectionFailureMessage(
        type: SyncEndpointType,
        error: Throwable?,
    ): String {
        val label = when (type) {
            SyncEndpointType.Local -> AppStrings.ui_target_endpoint
            SyncEndpointType.Device -> AppStrings.ui_target_device
            SyncEndpointType.Network -> AppStrings.ui_target_network_disk
        }
        val detail = sanitizeSyncMessage(error?.message).trim()
        return if (detail.isBlank()) {
            AppStrings.ui_arg0_connection_failed.format(arg0 = label)
        } else {
            AppStrings.ui_arg0_connection_failed_arg1.format(arg0 = label, arg1 = detail)
        }
    }

    private fun startScheduler() {
        scope.launch {
            while (true) {
                delay(1_000.milliseconds)
                val now = nowMillis()
                val dueTasks = tasks.filter { item ->
                    item.enabled &&
                            item.scheduleType == SyncScheduleType.Interval &&
                            item.nextRunAt > 0 &&
                            item.nextRunAt <= now &&
                            item.lastStatus != SyncRunStatus.Running
                }

                dueTasks.forEach { dueTask ->
                    runTaskInternal(dueTask, trigger = "schedule")
                }
            }
        }
    }

    private fun replaceOrAddTask(task: SyncTask) {
        val index = tasks.indexOfFirst { item -> item.id == task.id }
        if (index >= 0) {
            tasks[index] = task
        } else {
            tasks.add(task)
        }
    }

    private suspend fun saveTaskToStore(task: SyncTask): SyncTask {
        return withContext(Dispatchers.Default) {
            taskStore.saveTask(task)
        }
    }

    private fun saveTaskToStoreAsync(task: SyncTask) {
        scope.launch {
            saveTaskToStore(task)
        }
    }

    private suspend fun deleteTaskFromStore(taskId: Long) {
        if (taskId <= 0L) return
        withContext(Dispatchers.Default) {
            taskStore.deleteTask(taskId)
        }
    }

    private fun deleteTaskFromStoreAsync(taskId: Long) {
        scope.launch {
            deleteTaskFromStore(taskId)
        }
    }

    private fun deleteRunsFromStoreAsync(taskId: Long) {
        scope.launch {
            withContext(Dispatchers.Default) {
                taskStore.deleteRuns(taskId)
            }
        }
    }

    private suspend fun loadTasks() {
        val reloaded = withContext(Dispatchers.Default) {
            taskStore.loadTasks()
        }
        val reloadedRunHistory = withContext(Dispatchers.Default) {
            reloaded.associate { task -> task.id to taskStore.loadRuns(task.id) }
        }

        withContext(Dispatchers.Main) {
            tasks.clear()
            tasks.addAll(reloaded)
            runHistory.clear()
            reloadedRunHistory.forEach { (taskId, records) ->
                if (records.isNotEmpty()) {
                    runHistory[taskId] = records
                }
            }
            notifyChanged()
        }
    }

    suspend fun reloadTasksFromStore() {
        loadTasks()
    }

}
