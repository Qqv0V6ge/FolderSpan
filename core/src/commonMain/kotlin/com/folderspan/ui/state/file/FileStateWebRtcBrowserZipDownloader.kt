package com.folderspan.ui.state.file

import strings.AppStrings

import com.folderspan.PlatformType
import com.folderspan.data.StatusEnum
import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.main.device.Device
import com.folderspan.data.main.device.DeviceType
import com.folderspan.extensions.pathLevel
import com.folderspan.service.file.DeviceFileClient
import com.folderspan.service.operation.TraversalScanProgress
import com.folderspan.service.webrtc.controller.core.IncomingFileChunkWriter
import com.folderspan.service.webrtc.download.WebRtcBrowserDownloadRegistry
import com.folderspan.service.webrtc.download.WebRtcBrowserZipFileWriter
import com.folderspan.service.webrtc.download.webRtcBrowserZipExpectedEntryCount
import com.folderspan.ui.state.main.*
import kotlin.time.Clock

internal class FileStateWebRtcBrowserZipDownloader(
    private val taskState: TaskState,
    private val deviceForProtocolId: (String) -> Device?,
    private val collectDirectoryEntries: suspend (
        root: FileSimpleInfo,
        task: Task,
        onScanProgress: suspend (TraversalScanProgress) -> Unit,
    ) -> List<FileSimpleInfo>,
    private val ensureTaskRunning: suspend (Task) -> Unit,
    private val finishSuccessfulTask: (Task) -> Unit,
) {
    private fun findWebRtcDeviceForDownload(file: FileSimpleInfo): Device? {
        if (file.protocol != FileProtocol.Device || file.protocolId.isBlank()) return null
        return deviceForProtocolId(file.protocolId)
            ?.takeIf { device -> device.fileClient != null }
    }

    fun shouldUseBrowserZipDownload(file: FileSimpleInfo): Boolean =
        PlatformType == DeviceType.JS && file.isDirectory && findWebRtcDeviceForDownload(file) != null

    fun shouldUseBrowserZipDownloadForPaste(destFileInfo: FileSimpleInfo, srcFiles: List<FileSimpleInfo>): Boolean {
        if (PlatformType != DeviceType.JS || destFileInfo.protocol != FileProtocol.Local || srcFiles.isEmpty()) return false
        if (srcFiles.size == 1 && !srcFiles.single().isDirectory) return false
        val protocolId = srcFiles.first().protocolId
        return srcFiles.all { file ->
            file.protocol == FileProtocol.Device &&
                file.protocolId == protocolId &&
                findWebRtcDeviceForDownload(file) != null
        }
    }

    private fun zipDownloadFileName(file: FileSimpleInfo): String {
        val base = file.name.ifBlank {
            file.path.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\').ifBlank { "download" }
        }
        return if (base.endsWith(".zip", ignoreCase = true)) base else "$base.zip"
    }

    private fun webRtcZipRelativePath(root: FileSimpleInfo, entry: FileSimpleInfo, separator: String): String {
        val safeSeparator = separator.ifBlank { "/" }
        val normalizedRoot = root.path.trimEnd('/', '\\')
        val normalizedEntry = entry.path.trimEnd('/', '\\')
        val rootWithSeparator = if (normalizedRoot.endsWith(safeSeparator)) {
            normalizedRoot
        } else {
            normalizedRoot + safeSeparator
        }
        val relative = when {
            normalizedEntry == normalizedRoot -> ""
            normalizedEntry.startsWith(rootWithSeparator) -> normalizedEntry.removePrefix(rootWithSeparator)
            else -> entry.name
        }
        return relative
            .replace('\\', '/')
            .trim('/')
    }

    fun startFolderZipDownload(file: FileSimpleInfo, destDirectory: String) {
        val task = Task(
            taskType = TaskType.Copy,
            status = StatusEnum.LOADING,
            values = mapOf(
                "path" to file.path,
                TASK_WEBRTC_BROWSER_ZIP_DEST_KEY to zipDownloadFileName(file),
            ),
            protocol = file.protocol,
            protocolId = file.protocolId,
        )
        taskState.addOrUpdate(task)
        taskState.registerTaskHandler(task) {
            try {
                val result = downloadFolderAsBrowserZip(task, file)
                if (result.isSuccess && result.getOrDefault(false)) {
                    finishSuccessfulTask(task)
                } else {
                    val message = taskState.resolveTaskFailureMessage(
                        task = task,
                        preferredPath = destDirectory,
                        error = result.exceptionOrNull(),
                        fallback = AppStrings.ui_folder_download_failed,
                    )
                    taskState.updateStatus(task, StatusEnum.FAILURE)
                    taskState.putResult(task, destDirectory, message)
                }
            } finally {
                taskState.clearSignals(task.key)
            }
        }
    }

    fun startMultiZipDownload(files: List<FileSimpleInfo>, destDirectory: String) {
        if (files.isEmpty()) return
        val plan = buildWebRtcBrowserMultiZipPlan(files)
        val task = Task(
            taskType = TaskType.Copy,
            status = StatusEnum.LOADING,
            values = mapOf(
                "path" to files.joinToString(", ") { file -> file.path },
                TASK_WEBRTC_BROWSER_ZIP_DEST_KEY to plan.fileName,
            ),
            protocol = files.first().protocol,
            protocolId = files.first().protocolId,
        )
        taskState.addOrUpdate(task)
        taskState.registerTaskHandler(task) {
            try {
                val result = downloadSelectionAsBrowserZip(task, plan)
                if (result.isSuccess && result.getOrDefault(false)) {
                    finishSuccessfulTask(task)
                } else {
                    val message = taskState.resolveTaskFailureMessage(
                        task = task,
                        preferredPath = destDirectory,
                        error = result.exceptionOrNull(),
                        fallback = AppStrings.ui_file_download_failed,
                    )
                    taskState.updateStatus(task, StatusEnum.FAILURE)
                    taskState.putResult(task, destDirectory, message)
                }
            } finally {
                taskState.clearSignals(task.key)
            }
        }
    }

    private suspend fun downloadFolderAsBrowserZip(
        task: Task,
        root: FileSimpleInfo,
    ): Result<Boolean> {
        val device = findWebRtcDeviceForDownload(root)
            ?: return Result.failure(
                DeviceEndpointUnavailableException(AppStrings.message_task_source_device_disconnected)
            )
        val fileClient = device.fileClient
            ?: return Result.failure(
                DeviceEndpointUnavailableException(AppStrings.message_task_source_device_webrtc_disabled)
            )
        return try {
            taskState.putResult(task, root.path, AppStrings.ui_scanning_folder)
            val scanProgressPublisher = taskState.buildCopyScanProgressPublisher(task)
            val entries = collectDirectoryEntries(root, task) { progress ->
                    scanProgressPublisher(progress)
                }
            ensureTaskRunning(task)
            val separator = device.pathSeparator.ifBlank { "/" }
            val directories = entries
                .filter { entry -> entry.isDirectory }
                .sortedWith(compareBy<FileSimpleInfo> { entry -> entry.path.pathLevel() }.thenBy { entry -> entry.path })
            val files = entries
                .filterNot { entry -> entry.isDirectory }
                .sortedWith(compareBy<FileSimpleInfo> { entry -> entry.path.pathLevel() }.thenBy { entry -> entry.path })
            val zipName = zipDownloadFileName(root)
            val zipSession = WebRtcBrowserDownloadRegistry.createZipSession(
                fileName = zipName,
                rootName = root.name.ifBlank { zipName.removeSuffix(".zip") },
                fileCount = webRtcBrowserZipExpectedEntryCount(
                    directoryCount = directories.size,
                    fileCount = files.size,
                ),
            ) ?: return Result.failure(IllegalStateException(AppStrings.ui_current_browser_does_not_support_zip_download))
            var transferredFiles = 0
            val zipTotalBytes = webRtcZipTotalFileBytes(files)
            val transferChunkSize = fileClient.transferStatus().recommendedChunkBytes
            taskState.beginRuntimeByteMetrics(task, zipTotalBytes)
            taskState.putRuntimeParallelCount(task, 1)
            taskState.putValue(task, "progressCur", "0")
            taskState.putValue(task, "progressMax", files.size.coerceAtLeast(1).toString())
            try {
                val rootRegisterResult = zipSession.registerDirectory("")
                if (rootRegisterResult.isFailure || !rootRegisterResult.getOrDefault(false)) {
                    return rootRegisterResult
                }
                directories.forEach { directory ->
                    ensureTaskRunning(task)
                    val relativePath = webRtcZipRelativePath(root, directory, separator)
                    if (relativePath.isNotBlank()) {
                        val registerResult = zipSession.registerDirectory(relativePath)
                        if (registerResult.isFailure || !registerResult.getOrDefault(false)) {
                            return registerResult
                        }
                    }
                }
                files.forEach { source ->
                    ensureTaskRunning(task)
                    val relativePath = webRtcZipRelativePath(root, source, separator).ifBlank { source.name }
                    taskState.putResult(task, source.path, AppStrings.ui_packing)
                    taskState.startRuntimeByteItem(task, source.path, source.size.coerceAtLeast(0L))
                    val writer = WebRtcBrowserZipFileWriter(zipSession, relativePath)
                    val startMs = Clock.System.now().toEpochMilliseconds()
                    val rateSampler = TaskProgressRateSampler(initialAt = startMs)
                    val result = downloadSessionFile(
                        client = fileClient,
                        source = source,
                        writer = writer,
                        onProgress = { transferredBytes, totalBytes ->
                            val normalizedTotal = totalBytes.takeIf { bytes -> bytes > 0L }
                                ?: source.size.coerceAtLeast(0L)
                            val now = Clock.System.now().toEpochMilliseconds()
                            val rateSample = rateSampler.update(
                                completed = transferredBytes,
                                total = normalizedTotal,
                                now = now,
                                force = normalizedTotal in 1..transferredBytes,
                            )
                            taskState.putRuntimeByteProgress(task, source.path, transferredBytes)
                            taskState.putResult(
                                task,
                                source.path,
                                buildWebRtcTransferProgressText(
                                    transferredBytes = transferredBytes,
                                    totalBytes = normalizedTotal,
                                    chunkSize = transferChunkSize,
                                    rateSample = rateSample,
                                )
                            )
                        },
                    )
                    if (result.isFailure || !result.getOrDefault(false)) {
                        return result
                    }
                    taskState.finishRuntimeByteItem(task, source.path, source.size.coerceAtLeast(0L))
                    transferredFiles += 1
                    taskState.putValue(task, "progressCur", transferredFiles.toString())
                    taskState.removeResult(task, source.path)
                }
                taskState.putResult(task, root.path, AppStrings.ui_completing_zip_packaging)
                val finalizeResult = zipSession.finalize()
                if (finalizeResult.isFailure || !finalizeResult.getOrDefault(false)) {
                    return finalizeResult
                }
                taskState.removeResult(task, root.path)
                Result.success(true)
            } catch (error: Throwable) {
                zipSession.abort(error.message ?: AppStrings.message_task_cancelled)
                Result.failure(error)
            } finally {
                taskState.putRuntimeParallelCount(task, 0)
            }
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    private suspend fun downloadSelectionAsBrowserZip(
        task: Task,
        plan: WebRtcBrowserMultiZipPlan,
    ): Result<Boolean> {
        if (plan.roots.isEmpty()) return Result.success(true)
        val device = findWebRtcDeviceForDownload(plan.roots.first().source)
            ?: return Result.failure(
                DeviceEndpointUnavailableException(AppStrings.message_task_source_device_disconnected)
            )
        val fileClient = device.fileClient
            ?: return Result.failure(
                DeviceEndpointUnavailableException(AppStrings.message_task_source_device_webrtc_disabled)
            )
        return try {
            taskState.putResult(task, plan.fileName, AppStrings.ui_scanning_files)
            val separator = device.pathSeparator.ifBlank { "/" }
            val directoryEntriesByRoot = mutableMapOf<String, List<FileSimpleInfo>>()
            var scannedEntries = 0
            val scanProgressPublisher = taskState.buildCopyScanProgressPublisher(task)
            plan.roots.forEach { root ->
                ensureTaskRunning(task)
                if (root.source.isDirectory) {
                    val entries = collectDirectoryEntries(root.source, task) { progress ->
                        scanProgressPublisher(
                            progress.copy(
                                discoveredEntries = scannedEntries + progress.discoveredEntries,
                            )
                        )
                    }
                    directoryEntriesByRoot[root.source.path] = entries
                    scannedEntries += entries.size + 1
                } else {
                    scannedEntries += 1
                    taskState.putCopyScanProgress(task, scannedEntries, currentParallelism = 1)
                }
            }
            ensureTaskRunning(task)
            val directoryEntries = mutableListOf<String>()
            val fileEntries = mutableListOf<Pair<FileSimpleInfo, String>>()
            plan.roots.forEach { root ->
                val rootName = root.zipRootName
                if (root.source.isDirectory) {
                    directoryEntries += rootName
                    val entries = directoryEntriesByRoot[root.source.path].orEmpty()
                    entries
                        .filter { entry -> entry.isDirectory }
                        .sortedWith(compareBy<FileSimpleInfo> { entry -> entry.path.pathLevel() }.thenBy { entry -> entry.path })
                        .forEach { directory ->
                            val relativePath = webRtcZipRelativePath(root.source, directory, separator)
                            if (relativePath.isNotBlank()) {
                                directoryEntries += "$rootName/$relativePath"
                            }
                        }
                    entries
                        .filterNot { entry -> entry.isDirectory }
                        .sortedWith(compareBy<FileSimpleInfo> { entry -> entry.path.pathLevel() }.thenBy { entry -> entry.path })
                        .forEach { file ->
                            val relativePath = webRtcZipRelativePath(root.source, file, separator).ifBlank { file.name }
                            fileEntries += file to "$rootName/$relativePath"
                        }
                } else {
                    fileEntries += root.source to rootName
                }
            }
            val zipSession = WebRtcBrowserDownloadRegistry.createZipSession(
                fileName = plan.fileName,
                rootName = "",
                fileCount = webRtcBrowserZipExpectedEntryCount(
                    directoryCount = directoryEntries.size,
                    fileCount = fileEntries.size,
                ),
            ) ?: return Result.failure(IllegalStateException(AppStrings.ui_current_browser_does_not_support_zip_download))
            var transferredFiles = 0
            val zipTotalBytes = webRtcZipTotalFileBytes(fileEntries.map { (source, _) -> source })
            val transferChunkSize = fileClient.transferStatus().recommendedChunkBytes
            taskState.beginRuntimeByteMetrics(task, zipTotalBytes)
            taskState.putRuntimeParallelCount(task, 1)
            taskState.putValue(task, "progressCur", "0")
            taskState.putValue(task, "progressMax", fileEntries.size.coerceAtLeast(1).toString())
            try {
                directoryEntries.distinct().forEach { directoryPath ->
                    ensureTaskRunning(task)
                    val registerResult = zipSession.registerDirectory(directoryPath)
                    if (registerResult.isFailure || !registerResult.getOrDefault(false)) {
                        return registerResult
                    }
                }
                fileEntries.forEach { (source, relativePath) ->
                    ensureTaskRunning(task)
                    taskState.putResult(task, source.path, AppStrings.ui_packing)
                    taskState.startRuntimeByteItem(task, source.path, source.size.coerceAtLeast(0L))
                    val writer = WebRtcBrowserZipFileWriter(zipSession, relativePath)
                    val startMs = Clock.System.now().toEpochMilliseconds()
                    val rateSampler = TaskProgressRateSampler(initialAt = startMs)
                    val result = downloadSessionFile(
                        client = fileClient,
                        source = source,
                        writer = writer,
                        onProgress = { transferredBytes, totalBytes ->
                            val normalizedTotal = totalBytes.takeIf { bytes -> bytes > 0L }
                                ?: source.size.coerceAtLeast(0L)
                            val now = Clock.System.now().toEpochMilliseconds()
                            val rateSample = rateSampler.update(
                                completed = transferredBytes,
                                total = normalizedTotal,
                                now = now,
                                force = normalizedTotal in 1..transferredBytes,
                            )
                            taskState.putRuntimeByteProgress(task, source.path, transferredBytes)
                            taskState.putResult(
                                task,
                                source.path,
                                buildWebRtcTransferProgressText(
                                    transferredBytes = transferredBytes,
                                    totalBytes = normalizedTotal,
                                    chunkSize = transferChunkSize,
                                    rateSample = rateSample,
                                ),
                            )
                        },
                    )
                    if (result.isFailure || !result.getOrDefault(false)) {
                        return result
                    }
                    taskState.finishRuntimeByteItem(task, source.path, source.size.coerceAtLeast(0L))
                    transferredFiles += 1
                    taskState.putValue(task, "progressCur", transferredFiles.toString())
                    taskState.removeResult(task, source.path)
                }
                taskState.putResult(task, plan.fileName, AppStrings.ui_completing_zip_packaging)
                val finalizeResult = zipSession.finalize()
                if (finalizeResult.isFailure || !finalizeResult.getOrDefault(false)) {
                    return finalizeResult
                }
                taskState.removeResult(task, plan.fileName)
                Result.success(true)
            } catch (error: Throwable) {
                zipSession.abort(error.message ?: AppStrings.message_task_cancelled)
                Result.failure(error)
            } finally {
                taskState.putRuntimeParallelCount(task, 0)
            }
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    private suspend fun downloadSessionFile(
        client: DeviceFileClient,
        source: FileSimpleInfo,
        writer: IncomingFileChunkWriter,
        onProgress: suspend (transferredBytes: Long, totalBytes: Long) -> Unit,
    ): Result<Boolean> {
        val totalBytes = source.size.coerceAtLeast(0L)
        var offset = 0L
        return runCatching {
            val read = client.readStream(
                path = source.path,
                startOffset = 0L,
                endOffset = totalBytes,
            ) { chunk ->
                if (chunk.isEmpty()) return@readStream
                check(offset <= totalBytes - chunk.size.toLong()) {
                    AppStrings.ui_length_of_the_read_data_does_not_match_the_request_range
                }
                val written = writer.writeChunk(
                    path = source.path,
                    fileSize = totalBytes,
                    data = chunk,
                    offset = offset,
                ).getOrThrow()
                check(written) { AppStrings.ui_write_failed }
                offset += chunk.size
                onProgress(offset, totalBytes)
            }.getOrThrow()
            check(read) { AppStrings.ui_read_failed }
            check(offset == totalBytes) {
                AppStrings.ui_length_of_the_read_data_does_not_match_the_request_range
            }
            val committed = writer.commit(source.path, totalBytes).getOrThrow()
            check(committed) { AppStrings.ui_write_failed }
            true
        }.onFailure {
            writer.cleanup(source.path)
        }
    }
}
