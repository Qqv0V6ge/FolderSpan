package com.folderspan.utils

import strings.AppStrings

import androidx.compose.ui.awt.ComposeWindow
import com.folderspan.data.StatusEnum
import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.main.DiskBase
import com.folderspan.data.main.device.Device
import com.folderspan.data.main.Local
import com.folderspan.data.main.network.NetworkAccess
import com.folderspan.ui.state.file.FileState
import com.folderspan.ui.state.file.ShareListDropRegistry
import com.folderspan.ui.state.file.normalizeShareListDropFiles
import com.folderspan.ui.state.main.HomeState
import com.folderspan.ui.state.main.Task
import com.folderspan.ui.state.main.TaskType
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDragEvent
import java.awt.dnd.DropTargetDropEvent
import java.io.File
import javax.swing.SwingUtilities
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 负责为 Compose Desktop 窗口安装文件和文本拖拽处理逻辑。
 *
 * 文本交给“从剪贴板打开”流程处理；文件夹或文件按以下规则处理：
 * - 本地磁盘自动切换到目标目录（文件则使用其父目录）。
 * - 远程磁盘若支持粘贴权限，则将拖拽内容复制到当前目录。
 */
object DesktopFileDropHandler {

    /**
     * 为指定的 ComposeWindow 注册拖拽监听器。
     *
     * @param window 需要支持拖拽的窗口。
     * @param fileState 用于更新路径的文件状态实例。
     * @return 在不再需要监听器时调用的清理函数。
     */
    fun install(
        window: ComposeWindow,
        fileState: FileState,
        homeState: HomeState,
        onTextDrop: (String) -> Boolean,
    ): () -> Unit {
        val dropListener = object : DropTargetAdapter() {
            override fun dragEnter(dtde: DropTargetDragEvent) {
                if (supportsDesktopDrop(dtde.currentDataFlavors)) {
                    dtde.acceptDrag(DnDConstants.ACTION_COPY)
                    LogKit.d(AppStrings.ui_file_drag_dragenter_accepted)
                } else {
                    LogKit.d(AppStrings.ui_file_drag_dragenter_rejected_non_file_type)
                    dtde.rejectDrag()
                }
            }

            override fun dragOver(dtde: DropTargetDragEvent) {
                if (supportsDesktopDrop(dtde.currentDataFlavors)) {
                    dtde.acceptDrag(DnDConstants.ACTION_COPY)
                } else {
                    dtde.rejectDrag()
                }
            }

            override fun drop(dtde: DropTargetDropEvent) {
                if (!supportsDesktopDrop(dtde.currentDataFlavors)) {
                    LogKit.d(AppStrings.ui_file_drag_drop_drop_rejected_non_file_type)
                    dtde.rejectDrop()
                    return
                }

                dtde.acceptDrop(DnDConstants.ACTION_COPY)
                val success = runCatching {
                    val transferable = dtde.transferable
                    if (!transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                        val text = readDesktopDropText(transferable) ?: return@runCatching false
                        return@runCatching onTextDrop(text)
                    }
                    val files = transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>
                        ?: return@runCatching false
                    val droppedFiles = files.filterIsInstance<File>()
                    val preparedBatch = prepareDesktopExternalFiles(droppedFiles)
                    val preparedFiles = preparedBatch.files
                    val target = preparedFiles.firstOrNull() ?: return@runCatching false

                    if (ShareListDropRegistry.hasReceiver()) {
                        val shareFiles = normalizeShareListDropFiles(
                            preparedFiles
                        )
                        if (shareFiles.isEmpty()) {
                            LogKit.w(AppStrings.ui_share_drop_no_importable_content)
                            return@runCatching false
                        }
                        if (ShareListDropRegistry.deliver(shareFiles)) {
                            return@runCatching true
                        }
                    }

                    fileState.mainScope.launch {
                        when (val desk = fileState.deskType.value) {
                            is Local -> {
                                val targetPath = if (target.isDirectory) {
                                    target.path
                                } else {
                                    File(target.path).parentFile?.absolutePath
                                }

                                if (targetPath.isNullOrBlank()) {
                                    LogKit.d(AppStrings.ui_file_drag_drop_unable_identify_drag_target_arg0.format(arg0 = target.name))
                                    return@launch
                                }

                                LogKit.i(AppStrings.ui_file_drag_drop_open_path_arg0.format(arg0 = targetPath))
                                fileState.updatePath(targetPath)
                            }

                            is Device -> {
                                val sourcePaths = preparedFiles.map { item -> item.path }
                                homeState.requestDeviceDropUpload(desk.id, fileState.path.value, sourcePaths)
                            }

                            else -> {
                                val permission = desk.menuPermission
                                if (permission?.paste == true) {
                                    val targetPath = fileState.path.value
                                        .ifBlank { fileState.rootPath.value.path }
                                    val sourcePaths = preparedFiles.map { item -> item.path }
                                    val dropTarget = resolveDropTarget(desk)
                                    if (dropTarget == null) {
                                        LogKit.d(AppStrings.ui_desktop_file_drag_drop_unable_identify_drag_target_arg0.format(arg0 = (desk::class.simpleName).toString()))
                                        return@launch
                                    }
                                    startDropUpload(fileState, dropTarget, targetPath, sourcePaths)
                                } else {
                                    LogKit.d(AppStrings.ui_file_drag_drop_current_disk_type_does_not_support.format(arg0 = (desk::class.simpleName).toString()))
                                }
                            }
                        }
                    }
                    true
                }.onFailure { item ->
                    LogKit.e(AppStrings.ui_file_drag_failed, item)
                }.getOrDefault(false)

                dtde.dropComplete(success)
            }
        }

        val rootDropTarget = DropTarget(window.contentPane, DnDConstants.ACTION_COPY, dropListener, true)
        val childDropTargets = mutableListOf<DropTarget>()

        fun registerChildTargets() {
            window.contentPane.components.forEach { component ->
                if (childDropTargets.none { item ->  item.component == component }) {
                    childDropTargets += DropTarget(component, DnDConstants.ACTION_COPY, dropListener, true)
                }
            }
        }

        registerChildTargets()
        SwingUtilities.invokeLater { registerChildTargets() }

        return {
            rootDropTarget.removeDropTargetListener(dropListener)
            window.contentPane.dropTarget = null
            childDropTargets.forEach { target ->
                runCatching { target.removeDropTargetListener(dropListener) }
            }
        }
    }
}

private data class DropTargetInfo(
    val protocol: FileProtocol,
    val protocolId: String,
    val separator: String,
)

private fun resolveDropTarget(desk: DiskBase): DropTargetInfo? {
    return when (desk) {
        is Device -> DropTargetInfo(
            protocol = FileProtocol.Device,
            protocolId = desk.id,
            separator = desk.pathSeparator
        )

        is NetworkAccess -> DropTargetInfo(
            protocol = FileProtocol.Network,
            protocolId = desk.protocolId,
            separator = desk.pathSeparator
        )

        is Local -> DropTargetInfo(
            protocol = FileProtocol.Local,
            protocolId = "",
            separator = desk.pathSeparator
        )

        else -> null
    }
}

private suspend fun startDropUpload(
    fileState: FileState,
    target: DropTargetInfo,
    targetPath: String,
    sourcePaths: List<String>,
) {
    if (sourcePaths.isEmpty()) return
    val separator = target.separator.ifBlank { PathUtils.getPathSeparator() }
    val basePath = if (targetPath.endsWith(separator)) {
        targetPath
    } else {
        targetPath + separator
    }
    val sourceFiles = withContext(Dispatchers.Default) {
        sourcePaths.mapNotNull { path ->
            FileUtils.getFile(FileAccessPermission.Allowed, path).getOrNull()?.withCopy(
                protocol = FileProtocol.Local,
                protocolId = ""
            )
        }
    }
    if (sourceFiles.isEmpty()) return

    val taskState = fileState.taskState
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
            protocol = target.protocol,
            protocolId = target.protocolId,
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
                val result = fileState.copyTo(task, src, dest)
                if (result.isSuccess && result.getOrDefault(false)) {
                    taskState.delete(task)
                    fileState.updateFileAndFolder()
                } else {
                    val message = when (val exception = result.exceptionOrNull()) {
                        is CancellationException -> AppStrings.message_task_cancelled
                        else -> exception?.message ?: AppStrings.ui_file_copy_failed
                    }
                    taskState.putResult(task, dest.path, message)
                    taskState.updateStatus(task, StatusEnum.FAILURE)
                }
            } finally {
                taskState.clearSignals(task.key)
            }
        }
    }
}

internal fun supportsDesktopDrop(flavors: Array<DataFlavor>): Boolean =
    DataFlavor.javaFileListFlavor in flavors || DataFlavor.selectBestTextFlavor(flavors) != null

internal fun readDesktopDropText(transferable: Transferable): String? {
    val flavor = DataFlavor.selectBestTextFlavor(transferable.transferDataFlavors) ?: return null
    return flavor.getReaderForText(transferable).use { it.readText() }.takeIf { it.isNotBlank() }
}
