@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.folderspan.open

import com.folderspan.utils.FileAccessPermission
import strings.AppStrings

import com.folderspan.data.StatusEnum
import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.main.Local
import com.folderspan.data.main.device.Device
import com.folderspan.data.main.network.NetworkAccess
import com.folderspan.di.initKoin
import com.folderspan.ui.state.file.FileState
import com.folderspan.ui.state.file.FileDropSessionRegistry
import com.folderspan.ui.state.file.ExternalFileResourceLease
import com.folderspan.ui.state.file.ExternalFileResourceLeaseRegistry
import com.folderspan.ui.state.file.PreparedExternalFileBatch
import com.folderspan.ui.state.file.buildPreparedExternalFileBatch
import com.folderspan.ui.state.file.newExternalFileLeaseId
import com.folderspan.ui.state.file.ShareListDropRegistry
import com.folderspan.ui.state.file.ShareListDropResourceRegistry
import com.folderspan.ui.state.file.normalizeShareListDropFiles
import com.folderspan.ui.state.main.Task
import com.folderspan.ui.state.main.TaskType
import com.folderspan.utils.FileUtils
import com.folderspan.utils.LogKit
import com.folderspan.utils.PathUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toPath
import org.koin.mp.KoinPlatformTools
import platform.Foundation.NSArray
import platform.Foundation.NSURL

private val dropImportScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
private val pendingDropCopies = mutableMapOf<String, CompletableDeferred<PreparedExternalFileBatch>>()
private const val IOS_DROP_IMPORT_STAGING_DIRECTORY = "ios-drop-import"
private const val OPEN_IN_PLACE_ROOT = "open://"

fun moveIosFileDrop(horizontal: Float, vertical: Float) {
    FileDropSessionRegistry.moveExternal(horizontal, vertical)
}

fun endIosFileDrop() {
    FileDropSessionRegistry.end()
}

fun beginIosFileDrop(horizontal: Float, vertical: Float): String? {
    FileDropSessionRegistry.moveExternal(horizontal, vertical)
    val endpoint = FileDropSessionRegistry.captureEndpoint()
    initKoin()
    val fileState = KoinPlatformTools.defaultContext().get().get<FileState>()
    val destination = endpoint?.let(fileState::captureFileDropDestination)
    FileDropSessionRegistry.end()
    if (destination == null) return null
    val batch = CompletableDeferred<PreparedExternalFileBatch>()
    val requestId = fileState.dropCopyCoordinator.submit(destination) { batch.await() }
    pendingDropCopies[requestId] = batch
    return requestId
}

fun completeIosFileDrop(urls: NSArray, requestId: String) {
    val pending = pendingDropCopies.remove(requestId)
    val paths = buildList {
        repeat(urls.count.toInt()) { index ->
            (urls.objectAtIndex(index.toULong()) as? NSURL)?.path?.let(::add)
        }
    }.distinct()
    val fileState = KoinPlatformTools.defaultContext().get().get<FileState>()
    if (pending == null || fileState.dropCopyCoordinator.requests.value.none { it.id == requestId }) {
        paths.forEach(::cleanupStagedDropSource)
        return
    }
    dropImportScope.launch {
        val lease = ExternalFileResourceLease(newExternalFileLeaseId())
        ExternalFileResourceLeaseRegistry.register(lease) { paths.forEach(::cleanupStagedDropSource) }
        try {
            val files = withContext(Dispatchers.Default) {
                paths.mapNotNull { path ->
                    FileUtils.getFile(FileAccessPermission.Allowed, path).getOrNull()
                        ?.copy(protocol = FileProtocol.Local, protocolId = "")
                }
            }
            if (fileState.dropCopyCoordinator.requests.value.none { it.id == requestId }) {
                ExternalFileResourceLeaseRegistry.releaseProducer(lease.id)
            } else {
                pending.complete(buildPreparedExternalFileBatch(files, lease = lease))
            }
        } catch (error: Throwable) {
            ExternalFileResourceLeaseRegistry.releaseProducer(lease.id)
            pending.completeExceptionally(error)
        }
    }
}

private data class DropImportTarget(
    val protocol: FileProtocol,
    val protocolId: String,
    val separator: String,
    val targetPath: String,
)

fun handleIosDroppedFile(url: NSURL?) {
    handleIosDroppedSources(listOfNotNull(url))
}

fun handleIosDroppedFiles(urls: NSArray) {
    val sourceUrls = buildList {
        repeat(urls.count.toInt()) { index ->
            (urls.objectAtIndex(index.toULong()) as? NSURL)?.let(::add)
        }
    }
    handleIosDroppedSources(sourceUrls)
}

private fun handleIosDroppedSources(urls: List<NSURL>) {
    val sourcePaths = urls.mapNotNull { sourceUrl ->
        sourceUrl.path?.trim()?.takeIf { path -> path.isNotEmpty() }
    }.distinct()
    if (sourcePaths.isEmpty()) return

    val deliverToShareList = ShareListDropRegistry.hasReceiver()

    initKoin()
    val koin = KoinPlatformTools.defaultContext().get()
    val fileState = koin.get<FileState>()

    dropImportScope.launch {
        val sourceResults = withContext(Dispatchers.Default) {
            sourcePaths.map { sourcePath ->
                sourcePath to FileUtils.getFile(FileAccessPermission.Allowed, sourcePath)
                    .getOrNull()
                    ?.withCopy(
                        protocol = FileProtocol.Local,
                        protocolId = "",
                    )
            }
        }

        sourceResults.filter { (_, sourceFile) -> sourceFile == null }.forEach { (sourcePath, _) ->
            LogKit.e(AppStrings.ui_ios_drag_drop_import_failed_unable_read_temporary_file.format(arg0 = sourcePath))
            cleanupStagedDropSource(sourcePath)
        }
        val sourceFiles = sourceResults.mapNotNull { (_, sourceFile) -> sourceFile }
        if (sourceFiles.isEmpty()) {
            LogKit.w(AppStrings.ui_share_drop_no_importable_content)
            return@launch
        }

        if (deliverToShareList) {
            val normalizedFiles = normalizeShareListDropFiles(sourceFiles)
            if (ShareListDropRegistry.deliver(normalizedFiles)) {
                normalizedFiles.forEach { file ->
                    ShareListDropResourceRegistry.register(listOf(file.path)) {
                        cleanupStagedDropSource(file.path)
                    }
                }
            } else {
                normalizedFiles.forEach { file -> cleanupStagedDropSource(file.path) }
            }
            return@launch
        }

        val target = resolveDropImportTarget(fileState)
        sourceFiles.forEach { sourceFile ->
            enqueueDropImport(fileState, sourceFile, target)
        }
    }
}

fun releaseIosDropImportResources() {
    pendingDropCopies.values.forEach { it.cancel() }
    pendingDropCopies.clear()
    ShareListDropResourceRegistry.releaseAll()
}

fun releaseIosPreparedExternalSource(path: String) {
    cleanupStagedDropSource(path)
}

private suspend fun resolveDropImportTarget(fileState: FileState): DropImportTarget {
    val fallbackLocalPath = PathUtils.getHomePath().ifBlank { PathUtils.getAppPath() }

    return when (val desk = fileState.deskType.value) {
        is Local -> {
            val targetPath = resolveCurrentTargetPath(
                fileState = fileState,
                separator = desk.pathSeparator,
                fallbackPath = fallbackLocalPath,
                allowOpenInPlaceRoot = false,
            )
            if (targetPath != fileState.path.value && targetPath == fallbackLocalPath) {
                fileState.updatePath(targetPath)
            }
            DropImportTarget(
                protocol = FileProtocol.Local,
                protocolId = "",
                separator = desk.pathSeparator,
                targetPath = targetPath,
            )
        }

        is Device -> DropImportTarget(
            protocol = FileProtocol.Device,
            protocolId = desk.id,
            separator = desk.pathSeparator,
            targetPath = resolveCurrentTargetPath(
                fileState = fileState,
                separator = desk.pathSeparator,
                fallbackPath = desk.pathSeparator,
            ),
        )

        is NetworkAccess -> DropImportTarget(
            protocol = FileProtocol.Network,
            protocolId = desk.protocolId,
            separator = desk.pathSeparator,
            targetPath = resolveCurrentTargetPath(
                fileState = fileState,
                separator = desk.pathSeparator,
                fallbackPath = desk.pathSeparator,
            ),
        )

        else -> {
            fileState.updateDesk(
                protocol = FileProtocol.Local,
                type = Local(),
                pathOverride = fallbackLocalPath,
            )
            DropImportTarget(
                protocol = FileProtocol.Local,
                protocolId = "",
                separator = PathUtils.getPathSeparator(),
                targetPath = fallbackLocalPath,
            )
        }
    }
}

private fun resolveCurrentTargetPath(
    fileState: FileState,
    separator: String,
    fallbackPath: String,
    allowOpenInPlaceRoot: Boolean = true,
): String {
    val currentPath = fileState.path.value.trim()
    if (currentPath.isNotEmpty() && (allowOpenInPlaceRoot || currentPath != OPEN_IN_PLACE_ROOT)) {
        return currentPath
    }

    val rootPath = fileState.rootPath.value.path.trim()
    if (rootPath.isNotEmpty() && (allowOpenInPlaceRoot || rootPath != OPEN_IN_PLACE_ROOT)) {
        return rootPath
    }

    return fallbackPath.ifBlank { separator.ifBlank { PathUtils.getPathSeparator() } }
}

private fun buildDestinationPath(basePath: String, separator: String, name: String): String {
    val resolvedSeparator = separator.ifBlank { PathUtils.getPathSeparator() }
    return if (basePath.endsWith(resolvedSeparator)) {
        basePath + name
    } else {
        basePath + resolvedSeparator + name
    }
}

private fun enqueueDropImport(
    fileState: FileState,
    sourceFile: FileSimpleInfo,
    target: DropImportTarget,
) {
    val destinationPath = buildDestinationPath(target.targetPath, target.separator, sourceFile.name)
    val destinationFile = FileSimpleInfo.nullFileSimpleInfo().copy(
        name = sourceFile.name,
        description = sourceFile.description,
        isDirectory = sourceFile.isDirectory,
        isHidden = sourceFile.isHidden,
        path = destinationPath,
        mineType = sourceFile.mineType,
        size = sourceFile.size,
        createdDate = sourceFile.createdDate,
        updatedDate = sourceFile.updatedDate,
        protocol = target.protocol,
        protocolId = target.protocolId,
    )

    val taskState = fileState.taskState
    val task = Task(
        taskType = TaskType.Copy,
        status = StatusEnum.LOADING,
        values = mapOf("path" to sourceFile.path),
        protocol = sourceFile.protocol,
        protocolId = sourceFile.protocolId,
    )

    taskState.addOrUpdate(task)
    taskState.registerTaskHandler(task) {
        try {
            val result = fileState.copyTo(task, sourceFile, destinationFile)
            if (result.isSuccess && result.getOrDefault(false)) {
                taskState.delete(task)
                fileState.updateFileAndFolder()
            } else {
                val message = when (val error = result.exceptionOrNull()) {
                    is CancellationException -> AppStrings.message_task_cancelled
                    else -> error?.message ?: AppStrings.ui_file_import_failed
                }
                taskState.putResult(task, destinationFile.path, message)
                taskState.updateStatus(task, StatusEnum.FAILURE)
            }
        } finally {
            cleanupStagedDropSource(sourceFile.path)
            taskState.clearSignals(task.key)
        }
    }
}

private fun cleanupStagedDropSource(path: String) {
    val normalizedPath = path.trim()
    val stagingRoot = buildStagingRootPrefix()
    if (normalizedPath.isEmpty() || stagingRoot.isEmpty() || !normalizedPath.startsWith(stagingRoot)) {
        return
    }

    runCatching {
        FileSystem.SYSTEM.deleteRecursively(normalizedPath.toPath())
    }.onFailure { error ->
        LogKit.w(AppStrings.ui_ios_drag_import_cleanup_failed_arg0.format(arg0 = (error.message).toString()), error)
    }

    val separator = PathUtils.getPathSeparator()
    val parentPath = normalizedPath.substringBeforeLast(separator, "")
    if (parentPath.isNotEmpty() && parentPath.startsWith(stagingRoot)) {
        runCatching {
            FileSystem.SYSTEM.deleteRecursively(parentPath.toPath())
        }
    }
}

private fun buildStagingRootPrefix(): String {
    val cachePath = PathUtils.getCachePath().trimEnd('/')
    if (cachePath.isEmpty()) return ""
    return "$cachePath/$IOS_DROP_IMPORT_STAGING_DIRECTORY/"
}
