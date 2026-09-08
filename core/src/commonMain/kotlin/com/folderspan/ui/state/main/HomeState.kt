package com.folderspan.ui.state.main

import strings.AppStrings

import androidx.compose.runtime.mutableStateListOf
import com.folderspan.data.StatusEnum
import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.main.Local
import com.folderspan.data.main.share.Share
import com.folderspan.data.main.share.ShareProtocol
import com.folderspan.extensions.replaceLast
import com.folderspan.ui.state.file.FileOperationState
import com.folderspan.ui.state.file.FileState
import com.folderspan.ui.state.settings.SettingsState
import com.folderspan.utils.FileAccessPermission
import com.folderspan.utils.FileUtils
import com.folderspan.utils.LogKit
import com.folderspan.utils.PathUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class HomeState : KoinComponent {
    private val fileState: FileState by inject()
    private val fileOperationState: FileOperationState by inject()
    private val settingsState: SettingsState by inject()
    private val deviceState: DeviceState by inject()
    private val taskState: TaskState by inject()

    private val mainScope = MainScope()

    private val _isCreateFolder: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val isCreateFolder: StateFlow<Boolean> = _isCreateFolder

    fun updateCreateFolder(value: Boolean) {
        _isCreateFolder.value = value
    }

    val checkedFileSimpleInfo = mutableStateListOf<FileSimpleInfo>()

    private val _autoHighlightPaths = MutableStateFlow<Set<String>>(emptySet())
    val autoHighlightPaths: StateFlow<Set<String>> = _autoHighlightPaths

    fun applyAutoHighlight(files: Collection<FileSimpleInfo>) {
        checkedFileSimpleInfo.clear()
        checkedFileSimpleInfo.addAll(files)
        _autoHighlightPaths.value = files.map { item -> item.path }.toSet()
    }

    fun syncAutoHighlightSelection(files: List<FileSimpleInfo>) {
        val paths = _autoHighlightPaths.value
        if (paths.isEmpty()) return
        checkedFileSimpleInfo.clear()
        checkedFileSimpleInfo.addAll(files.filter { item -> item.path in paths })
    }

    fun clearAutoHighlightIfNeeded(): Boolean {
        val autoPaths = _autoHighlightPaths.value
        if (autoPaths.isEmpty()) return false
        checkedFileSimpleInfo.removeAll { item -> item.path in autoPaths }
        _autoHighlightPaths.value = emptySet()
        return true
    }

    fun clearCheckedFiles() {
        checkedFileSimpleInfo.clear()
        _autoHighlightPaths.value = emptySet()
    }

    private val _isPasteCopyFile: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val isPasteCopyFile: StateFlow<Boolean> = _isPasteCopyFile

    fun copyFile() {
        _isPasteCopyFile.value = true
    }

    fun cancelCopyFile() {
        _isPasteCopyFile.value = false
        fileOperationState.files.clear()
        clearCheckedFiles()
    }

    private val _isPasteMoveFile: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val isPasteMoveFile: StateFlow<Boolean> = _isPasteMoveFile

    fun moveFile() {
        _isPasteMoveFile.value = true
    }

    fun cancelMoveFile() {
        _isPasteMoveFile.value = false
        fileOperationState.files.clear()
        clearCheckedFiles()
    }

    suspend fun pasteCopyFile(destFileInfo: FileSimpleInfo) {
        fileState.pasteCopyFile(destFileInfo, checkedFileSimpleInfo, fileOperationState)
        cancelCopyFile()
    }

    suspend fun pasteMoveFile(destPath: String) {
        fileState.pasteMoveFile(destPath, checkedFileSimpleInfo, fileOperationState)
        cancelMoveFile()
    }

    private val _isRenameFile: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val isRenameFile: StateFlow<Boolean> = _isRenameFile

    fun updateRenameFile(status: Boolean) {
        _isRenameFile.value = status
    }

    private val _isViewFile: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val isViewFile: StateFlow<Boolean> = _isViewFile

    fun updateViewFile(status: Boolean) {
        _isViewFile.value = status
    }

    private val _isRemoteOpenConfirmDialog: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val isRemoteOpenConfirmDialog: StateFlow<Boolean> = _isRemoteOpenConfirmDialog

    private val _remoteOpenFile: MutableStateFlow<FileSimpleInfo?> = MutableStateFlow(null)
    val remoteOpenFile: StateFlow<FileSimpleInfo?> = _remoteOpenFile

    private val _isDeviceDropUploadDialog: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val isDeviceDropUploadDialog: StateFlow<Boolean> = _isDeviceDropUploadDialog

    private val _deviceDropUploadRequest: MutableStateFlow<DeviceDropUploadRequest?> = MutableStateFlow(null)
    val deviceDropUploadRequest: StateFlow<DeviceDropUploadRequest?> = _deviceDropUploadRequest

    private val _fileInfo: MutableStateFlow<FileSimpleInfo?> = MutableStateFlow(null)
    val fileInfo: StateFlow<FileSimpleInfo?> = _fileInfo

    fun updateFileInfo(data: FileSimpleInfo?) {
        _fileInfo.value = data
    }

    suspend fun openPathWithHighlight(targetPath: String) {
        val info = fileState.getFile(targetPath).getOrNull() ?: return
        val parentPath = info.path.replaceLast(info.name, "")
        applyAutoHighlight(listOf(info))
        fileState.updatePath(parentPath.ifBlank { info.path })
    }

    fun openFile(file: FileSimpleInfo) {
        if (file.isDirectory) return
        if (
            fileState.deskType.value is Local ||
            (fileState.deskType.value is Share && (fileState.deskType.value as Share).protocol == ShareProtocol.System)
        ) {
            FileUtils.openFile(FileAccessPermission.Allowed, file.path)
            return
        }
        _remoteOpenFile.value = file
        if (settingsState.remoteOpenConfirmEnabled.value) {
            _isRemoteOpenConfirmDialog.value = true
        } else {
            _isRemoteOpenConfirmDialog.value = false
            startRemoteOpenDownload(file)
        }
    }

    fun confirmRemoteOpen(dontShowAgain: Boolean, proceed: Boolean) {
        if (dontShowAgain) {
            settingsState.setRemoteOpenConfirmEnabled(false)
        }
        _isRemoteOpenConfirmDialog.value = false
        if (proceed) {
            val target = _remoteOpenFile.value
            if (target != null) {
                startRemoteOpenDownload(target)
            } else {
                clearRemoteOpenRequest()
            }
        } else {
            clearRemoteOpenRequest()
        }
    }

    fun clearRemoteOpenRequest() {
        _isRemoteOpenConfirmDialog.value = false
        _remoteOpenFile.value = null
    }

    fun requestDeviceDropUpload(deviceId: String, targetPath: String, sourcePaths: List<String>) {
        if (sourcePaths.isEmpty()) return
        val resolvedPath = targetPath.ifBlank { fileState.rootPath.value.path }
        _deviceDropUploadRequest.value = DeviceDropUploadRequest(
            deviceId = deviceId,
            targetPath = resolvedPath,
            sourcePaths = sourcePaths
        )
        _isDeviceDropUploadDialog.value = true
    }

    fun confirmDeviceDropUpload(proceed: Boolean) {
        val request = _deviceDropUploadRequest.value
        _isDeviceDropUploadDialog.value = false
        _deviceDropUploadRequest.value = null
        if (!proceed || request == null) return
        mainScope.launch {
            startDeviceDropUpload(request)
        }
    }

    private fun startRemoteOpenDownload(file: FileSimpleInfo) {
        val configuredDirectory = settingsState.remoteOpenDownloadDirectory.value.trim()
        val targetDirectory = configuredDirectory.ifBlank {
            PathUtils.getCachePath()
        }
        clearRemoteOpenRequest()
        fileState.downloadRemoteFile(file, targetDirectory)
    }

    private suspend fun startDeviceDropUpload(request: DeviceDropUploadRequest) {
        val device = deviceState.devices.firstOrNull { item -> item.id == request.deviceId }
        if (device == null) {
            LogKit.w(AppStrings.ui_file_drag_drop_device_does_not_exist_arg0.format(arg0 = request.deviceId))
            return
        }
        val separator = device.pathSeparator.ifBlank { PathUtils.getPathSeparator() }
        val basePath = if (request.targetPath.endsWith(separator)) {
            request.targetPath
        } else {
            request.targetPath + separator
        }
        val sourceFiles = withContext(Dispatchers.Default) {
            request.sourcePaths.mapNotNull { path ->
                FileUtils.getFile(FileAccessPermission.Allowed, path).getOrNull()?.withCopy(
                    protocol = FileProtocol.Local,
                    protocolId = ""
                )
            }
        }
        if (sourceFiles.isEmpty()) return
        sourceFiles.forEach { src ->
            val destPath = basePath + src.name
            val dest = FileSimpleInfo.nullFileSimpleInfo().copy(
                name = src.name,
                description = src.description,
                isDirectory = src.isDirectory,
                isHidden = src.isHidden,
                path = destPath,
                mineType = src.mineType,
                size = src.size,
                createdDate = src.createdDate,
                updatedDate = src.updatedDate,
                protocol = FileProtocol.Device,
                protocolId = device.id,
            )
            val task = Task(
                taskType = TaskType.Copy,
                status = StatusEnum.LOADING,
                values = mapOf("path" to src.path),
                protocol = src.protocol,
                protocolId = src.protocolId,
            )
            taskState.addOrUpdate(task)
            taskState.registerTaskHandler(task) {
                try {
                    val result = fileState.executeCopyTask(task, src, dest)
                    if (result.isSuccess && result.getOrDefault(false)) {
                        taskState.delete(task)
                        fileState.updateFileAndFolder()
                    } else {
                        val message = taskState.resolveTaskFailureMessage(
                            task = task,
                            preferredPath = dest.path,
                            error = result.exceptionOrNull(),
                            fallback = AppStrings.ui_file_copy_failed,
                        )
                        taskState.putResult(task, dest.path, message)
                        taskState.updateStatus(task, StatusEnum.FAILURE)
                    }
                } finally {
                    taskState.clearSignals(task.key)
                }
            }
        }
    }
}

data class DeviceDropUploadRequest(
    val deviceId: String,
    val targetPath: String,
    val sourcePaths: List<String>
)
