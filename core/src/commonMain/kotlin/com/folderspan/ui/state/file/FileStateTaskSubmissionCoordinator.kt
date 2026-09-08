package com.folderspan.ui.state.file

import strings.AppStrings

import com.folderspan.data.StatusEnum
import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.ui.state.main.Task
import com.folderspan.ui.state.main.TaskState
import com.folderspan.ui.state.main.TaskType
import com.folderspan.ui.state.main.resolveTaskFailureMessage
import com.folderspan.utils.FileAccessPermission
import com.folderspan.utils.FileUtils
import com.folderspan.utils.LogKit
import com.folderspan.utils.PathUtils

internal class FileStateTaskSubmissionCoordinator(
    private val taskState: TaskState,
    private val webRtcBrowserZipDownloader: FileStateWebRtcBrowserZipDownloader,
    private val executeCopyTask: suspend (Task, FileSimpleInfo, FileSimpleInfo) -> Result<Boolean>,
    private val executeDeleteTask: suspend (Task, FileSimpleInfo) -> Result<Boolean>,
    private val finishSuccessfulTask: (Task) -> Unit,
    private val updateFileAndFolder: suspend () -> Unit,
) {
    fun deleteFile(task: Task, deleteFileSimpleInfo: FileSimpleInfo) {
        LogKit.i(AppStrings.ui_start_deleting_files_arg0.format(arg0 = deleteFileSimpleInfo.path))
        taskState.addOrUpdate(task)
        taskState.registerTaskHandler(task) {
            try {
                val result = executeDeleteTask(task, deleteFileSimpleInfo)
                if (result.isSuccess && result.getOrDefault(false)) {
                    finishSuccessfulTask(task)
                } else {
                    val message = taskState.resolveTaskFailureMessage(
                        task = task,
                        preferredPath = deleteFileSimpleInfo.path,
                        error = result.exceptionOrNull(),
                        fallback = AppStrings.ui_delete_failed,
                    )
                    taskState.putResult(task, deleteFileSimpleInfo.path, message)
                    taskState.updateStatus(task, StatusEnum.FAILURE)
                }
                updateFileAndFolder()
            } finally {
                taskState.clearSignals(task.key)
            }
        }
    }

    fun enqueueCopyFile(
        task: Task,
        srcFileSimpleInfo: FileSimpleInfo,
        destFileSimpleInfo: FileSimpleInfo,
    ) {
        LogKit.i(AppStrings.ui_start_copying_files_arg0_arg1.format(arg0 = srcFileSimpleInfo.path, arg1 = destFileSimpleInfo.path))
        taskState.addOrUpdate(task)
        taskState.registerTaskHandler(task) {
            try {
                val result = executeCopyTask(task, srcFileSimpleInfo, destFileSimpleInfo)
                if (result.isSuccess && result.getOrDefault(false)) {
                    finishSuccessfulTask(task)
                    updateFileAndFolder()
                } else {
                    val message = taskState.resolveTaskFailureMessage(
                        task = task,
                        preferredPath = destFileSimpleInfo.path,
                        error = result.exceptionOrNull(),
                        fallback = AppStrings.ui_file_copy_failed,
                    )
                    taskState.updateStatus(task, StatusEnum.FAILURE)
                    taskState.putResult(task, destFileSimpleInfo.path, message)
                }
            } finally {
                taskState.clearSignals(task.key)
            }
        }
    }

    fun downloadRemoteFile(file: FileSimpleInfo, destDirectory: String) {
        if (webRtcBrowserZipDownloader.shouldUseBrowserZipDownload(file)) {
            webRtcBrowserZipDownloader.startFolderZipDownload(file, destDirectory)
            return
        }
        val separator = PathUtils.getPathSeparator()
        val targetDirectory = if (destDirectory.endsWith(separator)) destDirectory else destDirectory + separator
        val targetPath = targetDirectory + file.name
        PathUtils.createDirectoryIfNotExists(FileAccessPermission.Allowed, destDirectory)

        val destFileInfo = FileSimpleInfo.nullFileSimpleInfo().copy(
            name = file.name,
            description = file.description,
            isDirectory = file.isDirectory,
            isHidden = file.isHidden,
            path = targetPath,
            mineType = file.mineType,
            size = file.size,
            createdDate = file.createdDate,
            updatedDate = file.updatedDate,
            protocol = FileProtocol.Local,
            protocolId = "",
        )
        val task = Task(
            taskType = TaskType.Copy,
            status = StatusEnum.LOADING,
            values = mapOf("path" to file.path),
            protocol = file.protocol,
            protocolId = file.protocolId,
        )
        taskState.addOrUpdate(task)
        taskState.registerTaskHandler(task) {
            try {
                val result = executeCopyTask(task, file, destFileInfo)
                if (result.isSuccess && result.getOrDefault(false)) {
                    val hasFailedEntries = taskState.hasFailedRetryEntries(task)
                    finishSuccessfulTask(task)
                    if (!hasFailedEntries) {
                        FileUtils.openFile(FileAccessPermission.Allowed, targetPath)
                    }
                } else {
                    val message = taskState.resolveTaskFailureMessage(
                        task = task,
                        preferredPath = destFileInfo.path,
                        error = result.exceptionOrNull(),
                        fallback = AppStrings.ui_file_download_failed,
                    )
                    taskState.updateStatus(task, StatusEnum.FAILURE)
                    taskState.putResult(task, destFileInfo.path, message)
                }
            } finally {
                taskState.clearSignals(task.key)
            }
        }
    }
}
