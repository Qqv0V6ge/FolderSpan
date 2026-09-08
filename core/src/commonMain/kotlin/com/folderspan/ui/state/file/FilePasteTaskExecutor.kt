package com.folderspan.ui.state.file

import strings.AppStrings

import com.folderspan.data.StatusEnum
import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.main.DiskBase
import com.folderspan.data.main.device.Device
import com.folderspan.data.main.network.NetworkAccess
import com.folderspan.extensions.parentPath
import com.folderspan.ui.state.main.DeviceEndpointUnavailableException
import com.folderspan.ui.state.main.Task
import com.folderspan.ui.state.main.TaskEndpointUnavailableException
import com.folderspan.ui.state.main.TaskState
import com.folderspan.ui.state.main.TaskType
import com.folderspan.ui.state.main.resolveTaskFailureMessage
import com.folderspan.utils.FileAccessPermission
import com.folderspan.utils.FileUtils
import com.folderspan.utils.LogKit
import com.folderspan.utils.PathUtils
import com.folderspan.service.http.clipboard.ClipboardStagedDownload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

internal class FilePasteTaskExecutor(
    private val taskState: TaskState,
    private val webRtcBrowserZipDownloader: FileStateWebRtcBrowserZipDownloader,
    private val pasteOperationPlanner: FilePasteOperationPlanner,
    private val getFileAndFolder: suspend (String) -> Result<List<FileSimpleInfo>>,
    private val getFileAndFolderForDesk: suspend (DiskBase, String) -> Result<List<FileSimpleInfo>>,
    private val getFile: suspend (String) -> Result<FileSimpleInfo>,
    private val executeCopyTask: suspend (Task, FileSimpleInfo, FileSimpleInfo) -> Result<Boolean>,
    private val executeMoveTask: suspend (Task, FileSimpleInfo, FileSimpleInfo) -> Result<Boolean>,
    private val finishSuccessfulTask: (Task) -> Unit,
    private val updateFileAndFolder: suspend () -> Unit,
    private val currentDesk: () -> DiskBase,
    private val resolveDevice: (DiskBase?, String) -> Device?,
    private val resolveNetworkAccess: (DiskBase?, String) -> NetworkAccess?,
    private val downloadUrlForCopy: suspend (
        String,
        suspend (Task, ClipboardStagedDownload) -> Result<Boolean>,
    ) -> Boolean,
) {
    suspend fun pasteCopyFile(
        destFileInfo: FileSimpleInfo,
        srcFiles: List<FileSimpleInfo>,
        fileOperationState: FileOperationState,
    ) {
        if (webRtcBrowserZipDownloader.shouldUseBrowserZipDownloadForPaste(destFileInfo, srcFiles)) {
            val destDirectory = if (destFileInfo.isDirectory) {
                destFileInfo.path
            } else {
                destFileInfo.path.parentPath(PathUtils.getPathSeparator())
            }
            if (srcFiles.size == 1 && srcFiles.single().isDirectory) {
                webRtcBrowserZipDownloader.startFolderZipDownload(srcFiles.single(), destDirectory)
            } else {
                webRtcBrowserZipDownloader.startMultiZipDownload(srcFiles, destDirectory)
            }
            return
        }

        val fileOperations = resolvePasteOperations(destFileInfo, srcFiles, fileOperationState) ?: return
        val urlOperations = fileOperations.filter { ClipboardUrlShareFiles.downloadUrl(it.src) != null }
        if (urlOperations.isNotEmpty()) {
            for (operation in urlOperations) {
                val url = ClipboardUrlShareFiles.downloadUrl(operation.src) ?: continue
                val copied = downloadUrlForCopy(url) { task, staged ->
                    val path = staged.localPath ?: return@downloadUrlForCopy Result.success(false)
                    val replace = deleteReplaceTargetIfNeeded(task, operation.replaceTarget)
                    if (replace.isFailure || !replace.getOrDefault(false)) return@downloadUrlForCopy replace
                    val localFile = operation.src.withCopy(
                        path = path,
                        size = staged.size,
                        mineType = staged.contentType.orEmpty(),
                        protocol = FileProtocol.Local,
                        protocolId = "",
                    )
                    executeCopyTask(task, localFile, operation.dest).also { result ->
                        if (result.getOrDefault(false)) updateFileAndFolder()
                    }
                }
                if (!copied) return
            }
        }
        enqueuePasteOperations(
            fileOperations = fileOperations - urlOperations.toSet(),
            taskType = TaskType.Copy,
            fallbackMessage = AppStrings.ui_file_copy_failed,
            execute = executeCopyTask,
        )
    }

    suspend fun pasteCopyFilesWithReplace(
        destFileInfo: FileSimpleInfo,
        srcFiles: List<FileSimpleInfo>,
        listDestinationChildren: suspend () -> Result<List<FileSimpleInfo>> = {
            getFileAndFolder(destFileInfo.path)
        },
        awaitCompletion: Boolean = false,
    ): List<Long> {
        if (srcFiles.isEmpty()) return emptyList()
        val fileAndFolders = listDestinationChildren().getOrDefault(emptyList())
        val fileOperationState = FileOperationState()
        pasteOperationPlanner.updateConflictOperations(
            srcFileInfos = srcFiles,
            destFileInfo = destFileInfo,
            fileAndFolders = fileAndFolders,
            fileOperationState = fileOperationState,
        )
        val selectedOperations = fileOperationState.files.map { operation ->
            operation.copy(type = FileOperationType.Replace)
        }
        val fileOperations = pasteOperationPlanner.buildPendingOperations(
            selectedOperations = selectedOperations,
            destFileInfo = destFileInfo,
            fileAndFolders = fileAndFolders,
        )
        return enqueuePasteOperations(
            fileOperations = fileOperations,
            taskType = TaskType.Copy,
            fallbackMessage = AppStrings.ui_file_copy_failed,
            execute = executeCopyTask,
            awaitCompletion = awaitCompletion,
        )
    }

    suspend fun pasteExternalFiles(
        target: ExternalFileImportTarget,
        srcFiles: List<FileSimpleInfo>,
        fileOperationState: FileOperationState,
        lease: ExternalFileResourceLease?,
    ): List<Long> {
        val fileOperations = resolvePasteOperations(
            destFileInfo = target.destination,
            srcFiles = srcFiles,
            fileOperationState = fileOperationState,
            listDestinationChildren = {
                getFileAndFolderForDesk(target.desk, target.capturedPath)
            },
            destinationUnavailableIsFailure = true,
        ) ?: return emptyList()
        return enqueuePasteOperations(
            fileOperations = fileOperations,
            taskType = TaskType.Copy,
            fallbackMessage = AppStrings.ui_file_copy_failed,
            execute = executeCopyTask,
            lease = lease,
        )
    }

    suspend fun pasteMoveFile(
        destPath: String,
        srcFiles: List<FileSimpleInfo>,
        fileOperationState: FileOperationState,
    ) {
        val destFileInfo = getFile(destPath).getOrNull() ?: return
        val fileOperations = resolvePasteOperations(destFileInfo, srcFiles, fileOperationState) ?: return
        enqueuePasteOperations(
            fileOperations = fileOperations,
            taskType = TaskType.Move,
            fallbackMessage = AppStrings.ui_file_move_failed,
            execute = executeMoveTask,
        )
    }

    private suspend fun resolvePasteOperations(
        destFileInfo: FileSimpleInfo,
        srcFiles: List<FileSimpleInfo>,
        fileOperationState: FileOperationState,
        listDestinationChildren: suspend () -> Result<List<FileSimpleInfo>> = {
            getFileAndFolder(destFileInfo.path)
        },
        destinationUnavailableIsFailure: Boolean = false,
    ): List<PendingFileOperation>? {
        val destinationChildren = listDestinationChildren()
        val fileAndFolders = if (destinationUnavailableIsFailure) {
            destinationChildren.getOrElse { throw ExternalFileTargetUnavailableException() }
        } else {
            destinationChildren.getOrDefault(emptyList())
        }

        fileOperationState.updateWarningOperationDialog(
            pasteOperationPlanner.updateConflictOperations(
                srcFileInfos = srcFiles,
                destFileInfo = destFileInfo,
                fileAndFolders = fileAndFolders,
                fileOperationState = fileOperationState,
            )
        )
        fileOperationState.isWarningOperationDialog.first { item -> !item }
        if (fileOperationState.files.isEmpty()) return null

        return pasteOperationPlanner.buildPendingOperations(
            selectedOperations = fileOperationState.files,
            destFileInfo = destFileInfo,
            fileAndFolders = fileAndFolders,
        )
    }

    private suspend fun enqueuePasteOperations(
        fileOperations: List<PendingFileOperation>,
        taskType: TaskType,
        fallbackMessage: String,
        execute: suspend (Task, FileSimpleInfo, FileSimpleInfo) -> Result<Boolean>,
        lease: ExternalFileResourceLease? = null,
        awaitCompletion: Boolean = false,
    ): List<Long> {
        val tasks = fileOperations.map { operation ->
            val taskValues = buildMap {
                put("path", operation.src.path)
                if (lease != null) {
                    put(EXTERNAL_FILE_LEASE_ID_TASK_VALUE, lease.id)
                    lease.rootPath?.let { rootPath -> put(EXTERNAL_FILE_LEASE_ROOT_TASK_VALUE, rootPath) }
                }
            }
            Triple(
                Task(
                    taskType = taskType,
                    status = StatusEnum.LOADING,
                    values = taskValues,
                    protocol = operation.src.protocol,
                    protocolId = operation.src.protocolId,
                ),
                operation,
                CompletableDeferred<Result<Boolean>>(),
            )
        }

        try {
            tasks.forEach { (task, _, _) -> taskState.addOrUpdate(task) }
            if (lease != null) {
                ExternalFileResourceLeaseRegistry.bindTasks(lease, tasks.map { (task, _, _) -> task.key })
            }
            tasks.forEach { (task, operation, completion) ->
                taskState.registerTaskHandler(task) {
                    var completedResult: Result<Boolean>? = null
                    try {
                        val replaceResult = deleteReplaceTargetIfNeeded(task, operation.replaceTarget)
                        if (replaceResult.isFailure || !replaceResult.getOrDefault(true)) {
                            val message = taskState.resolveTaskFailureMessage(
                                task = task,
                                preferredPath = operation.replaceTarget?.path ?: operation.dest.path,
                                error = replaceResult.exceptionOrNull(),
                                fallback = AppStrings.ui_file_replacement_failed,
                            )
                            taskState.updateStatus(task, StatusEnum.FAILURE)
                            taskState.putResult(task, operation.dest.path, message)
                            completedResult = Result.failure(
                                replaceResult.exceptionOrNull()
                                    ?: IllegalStateException(AppStrings.ui_file_replacement_failed),
                            )
                            return@registerTaskHandler
                        }

                        val result = execute(task, operation.src, operation.dest)
                        completedResult = result
                        if (result.isSuccess && result.getOrDefault(false)) {
                            finishSuccessfulTask(task)
                            updateFileAndFolder()
                        } else {
                            val message = taskState.resolveTaskFailureMessage(
                                task = task,
                                preferredPath = operation.dest.path,
                                error = result.exceptionOrNull(),
                                fallback = fallbackMessage,
                            )
                            taskState.updateStatus(task, StatusEnum.FAILURE)
                            taskState.putResult(task, operation.dest.path, message)
                        }
                    } catch (error: CancellationException) {
                        completedResult = Result.failure(error)
                        throw error
                    } catch (error: Throwable) {
                        completedResult = Result.failure(error)
                        throw error
                    } finally {
                        taskState.clearSignals(task.key)
                        if (!completion.isCompleted) {
                            completion.complete(
                                completedResult
                                    ?: Result.failure(IllegalStateException(fallbackMessage)),
                            )
                        }
                    }
                }
            }
        } catch (error: Throwable) {
            LogKit.e(AppStrings.ui_clipboard_paste_task_failed, error)
            tasks.forEach { (task, _, completion) ->
                taskState.delete(task)
                if (!completion.isCompleted) completion.complete(Result.failure(error))
            }
            lease?.let { item -> ExternalFileResourceLeaseRegistry.releaseProducer(item.id) }
            throw error
        }
        if (awaitCompletion) {
            val results = tasks.map { (_, _, completion) -> completion.await() }
            val failure = results.firstOrNull { result -> result.isFailure || !result.getOrDefault(false) }
            if (failure != null) {
                throw failure.exceptionOrNull() ?: IllegalStateException(fallbackMessage)
            }
        }
        return tasks.map { (task, _, _) -> task.key }
    }

    private suspend fun deleteReplaceTargetIfNeeded(
        task: Task,
        target: FileSimpleInfo?,
    ): Result<Boolean> {
        if (target == null) return Result.success(true)
        if (!taskState.awaitIfPaused(task.key) || taskState.isTaskCancelled(task.key)) {
            return Result.failure(CancellationException(AppStrings.message_task_cancelled))
        }

        taskState.putResult(task, target.path, AppStrings.ui_replacing_existing_project)
        return deleteReplaceTarget(target).onSuccess {
            taskState.removeResult(task, target.path)
        }.onFailure { failure ->
            taskState.putResult(task, target.path, failure.message.orEmpty().ifBlank { AppStrings.ui_failed_delete_old_target })
        }
    }

    private suspend fun deleteReplaceTarget(target: FileSimpleInfo): Result<Boolean> {
        return when (target.protocol) {
            FileProtocol.Local -> withContext(Dispatchers.Default) {
                runCatching {
                    if (target.isDirectory) {
                        PathUtils.deleteDirectory(FileAccessPermission.Allowed, target.path)
                        true
                    } else {
                        FileUtils.deleteFile(FileAccessPermission.Allowed, target.path)
                            .getOrElse { failure -> throw failure }
                    }
                }.fold(
                    onSuccess = { Result.success(it) },
                    onFailure = { failure -> Result.failure(failure) },
                )
            }

            FileProtocol.Device -> {
                val device = resolveDevice(currentDesk(), target.protocolId)
                    ?: return Result.failure(
                        DeviceEndpointUnavailableException(AppStrings.message_task_target_device_disconnected)
                    )
                if (target.isDirectory) {
                    device.paths.deleteDirectory(target.path)
                } else {
                    device.files.delete(listOf(target.path)).fold(
                        onSuccess = { results ->
                            results.firstOrNull() ?: Result.failure(Exception(AppStrings.ui_failed_delete_old_target))
                        },
                        onFailure = { failure -> Result.failure(failure) },
                    )
                }
            }

            FileProtocol.Network -> {
                val networkAccess = resolveNetworkAccess(currentDesk(), target.protocolId)
                    ?: return Result.failure(
                        TaskEndpointUnavailableException(AppStrings.message_task_target_network_disconnected)
                    )
                networkAccess.delete(target.path, target.isDirectory)
            }

            FileProtocol.Share -> Result.failure(Exception(AppStrings.ui_shared_target_does_not_support_replacement))
        }
    }
}
