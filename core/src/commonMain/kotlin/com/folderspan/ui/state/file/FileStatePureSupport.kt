package com.folderspan.ui.state.file

import strings.AppStrings

import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.extensions.formatDuration
import com.folderspan.extensions.formatPercent
import com.folderspan.extensions.formatSpeed
import com.folderspan.service.file.calculateDeviceTransportChunkCount
import com.folderspan.service.http.client.HttpRouteClientManager.Companion.DEVICE_DIRECT_MAX_LENGTH
import com.folderspan.service.operation.TraversalEndpointKind
import com.folderspan.ui.state.main.*

internal fun FileProtocol.toTraversalEndpointKind(): TraversalEndpointKind = when (this) {
    FileProtocol.Local -> TraversalEndpointKind.Local
    FileProtocol.Device -> TraversalEndpointKind.Device
    FileProtocol.Share -> TraversalEndpointKind.Share
    FileProtocol.Network -> TraversalEndpointKind.Network
}

internal fun TaskRuntimeQueueEntry.copyEndpointRefs(): List<TaskRuntimeEndpointRef> {
    return listOf(src, dest).filter { ref -> ref.path.isNotBlank() || ref.protocolId.isNotBlank() }
}

internal fun TaskRuntimeQueueEntry.deleteEndpointRefs(): List<TaskRuntimeEndpointRef> {
    return listOf(src).filter { ref -> ref.path.isNotBlank() || ref.protocolId.isNotBlank() }
}

internal fun FilePropertySummary.hasContent(): Boolean {
    return totalSize > 0L || fileCount > 0 || folderCount > 0
}

internal fun FileSimpleInfo.toSelectedFilePropertySummary(): FilePropertySummary {
    return if (isDirectory) {
        FilePropertySummary(folderCount = 1)
    } else {
        FilePropertySummary(
            totalSize = size.coerceAtLeast(0L),
            fileCount = 1,
        )
    }
}

internal fun Iterable<FileSimpleInfo>.toSelectedFilePropertySummary(): FilePropertySummary {
    var totalSize = 0L
    var fileCount = 0
    var folderCount = 0
    forEach { file ->
        if (file.isDirectory) {
            folderCount++
        } else {
            fileCount++
            totalSize += file.size.coerceAtLeast(0L)
        }
    }
    return FilePropertySummary(
        totalSize = totalSize,
        fileCount = fileCount,
        folderCount = folderCount,
    )
}

internal fun Iterable<FileSimpleInfo>.toFilePropertySummary(): FilePropertySummary {
    var totalSize = 0L
    var fileCount = 0
    var folderCount = 0
    forEach { file ->
        if (file.isDirectory) {
            folderCount++
        } else {
            fileCount++
            totalSize += file.size.coerceAtLeast(0L)
        }
    }
    return FilePropertySummary(
        totalSize = totalSize,
        fileCount = fileCount,
        folderCount = folderCount,
    )
}

internal val TaskRuntimeQueueEntry.srcPath: String
    get() = src.path

internal val TaskRuntimeQueueEntry.srcProtocol: FileProtocol
    get() = src.protocol

internal val TaskRuntimeQueueEntry.srcProtocolId: String
    get() = src.protocolId

internal val TaskRuntimeQueueEntry.destPath: String
    get() = dest.path

internal val TaskRuntimeQueueEntry.destProtocol: FileProtocol
    get() = dest.protocol

internal val TaskRuntimeQueueEntry.destProtocolId: String
    get() = dest.protocolId

internal fun TaskRuntimeQueueEntry.displayPath(): String {
    return when (kind) {
        TaskRuntimeEntryKind.DIRECTORY_CREATE,
        TaskRuntimeEntryKind.EMPTY_FILE_CREATE,
        TaskRuntimeEntryKind.FILE_COPY -> destPath.ifBlank { srcPath }

        TaskRuntimeEntryKind.SOURCE_DELETE,
        TaskRuntimeEntryKind.TARGET_DELETE -> srcPath.ifBlank { destPath }
    }
}

internal fun TaskRuntimeQueueEntry.toSourceFileSimpleInfo(): FileSimpleInfo {
    return buildRetryFileSimpleInfo(
        path = srcPath,
        protocol = srcProtocol,
        protocolId = srcProtocolId,
        isDirectory = isDirectory,
        size = size,
    )
}

internal fun TaskRuntimeQueueEntry.toTargetFileSimpleInfo(): FileSimpleInfo {
    return buildRetryFileSimpleInfo(
        path = destPath,
        protocol = destProtocol,
        protocolId = destProtocolId,
        isDirectory = isDirectory,
        size = size,
    )
}

internal fun TaskRuntimeQueueEntry.toCopyRetryEntry(taskType: TaskType): TaskRetryEntry {
    return buildCopyRetryEntry(
        taskType = taskType,
        src = toSourceFileSimpleInfo(),
        dest = toTargetFileSimpleInfo(),
    )
}

internal fun TaskRuntimeQueueEntry.toDeleteRetryEntry(taskType: TaskType): TaskRetryEntry {
    val retryStage = when (stage) {
        TaskRuntimeStage.COPY -> TaskRetryStage.COPY
        TaskRuntimeStage.DELETE_SOURCE -> TaskRetryStage.DELETE_SOURCE
        TaskRuntimeStage.DELETE -> TaskRetryStage.DELETE
    }
    return buildDeleteRetryEntry(
        taskType = taskType,
        target = toSourceFileSimpleInfo(),
        stage = retryStage,
    )
}

internal fun totalRelayBlocks(totalBytes: Long, chunkSize: Int = DEVICE_DIRECT_MAX_LENGTH): Int {
    return calculateDeviceTransportChunkCount(totalBytes, chunkSize)
}

internal fun buildWebRtcTransferProgressText(
    transferredBytes: Long,
    totalBytes: Long,
    chunkSize: Int = DEVICE_DIRECT_MAX_LENGTH,
    rateSample: TaskProgressRateSample,
): String {
    val safeChunkSize = chunkSize.coerceAtLeast(1)
    val totalBlocks = totalRelayBlocks(totalBytes, safeChunkSize)
    val doneBlocks = if (transferredBytes <= 0L) {
        0L
    } else {
        ((transferredBytes + safeChunkSize - 1L) / safeChunkSize)
            .coerceIn(0L, totalBlocks.toLong())
    }
    return AppStrings.ui_transfer_progress_arg0.format(arg0 = transferredBytes.formatPercent(totalBytes)) +
        AppStrings.ui_arg0_arg1_speed_arg2.format(
            arg0 = doneBlocks.toString(),
            arg1 = totalBlocks.toString(),
            arg2 = rateSample.speedPerSecond.formatSpeed(),
        ) +
        AppStrings.ui_remaining_arg0.format(arg0 = rateSample.etaMs.formatDuration())
}

internal fun webRtcZipTotalFileBytes(files: Iterable<FileSimpleInfo>): Long {
    return files
        .filterNot { file -> file.isDirectory }
        .sumOf { file -> file.size.coerceAtLeast(0L) }
}
