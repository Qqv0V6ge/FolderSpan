package com.folderspan.ui.state.file

import strings.AppStrings

import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.file.ShareHistoryInput
import com.folderspan.data.main.DiskBase
import com.folderspan.data.main.Local
import com.folderspan.data.main.device.Device
import com.folderspan.data.main.device.DeviceType
import com.folderspan.data.main.network.Network
import com.folderspan.data.main.network.NetworkAccess
import com.folderspan.data.main.network.buildProtocolId
import com.folderspan.data.main.share.SYSTEM_SHARE_DESK_ID
import com.folderspan.data.main.share.Share
import com.folderspan.extensions.*
import com.folderspan.ignore.ResolvedIgnoreMatcher
import com.folderspan.ignore.filterIgnoredOperationEntries
import com.folderspan.service.data.DeviceTransportType
import com.folderspan.service.file.*
import com.folderspan.service.http.archive.FolderSpanArchiveEntryRequest
import com.folderspan.service.http.archive.selectSmallFileArchiveBatches
import com.folderspan.service.http.client.*
import com.folderspan.service.http.client.HttpRouteClientManager.Companion.DEVICE_DIRECT_MAX_LENGTH
import com.folderspan.service.operation.*
import com.folderspan.ui.state.main.*
import kotlinx.coroutines.*

// 每个磁盘的稳定键（不包含敏感信息），用于会话内路径记忆。
internal fun buildDiskPathKey(desk: DiskBase): String = when (desk) {
    is Local -> "local"
    is Device -> "device:${desk.id}"
    is Share -> "share:${desk.protocol}:${desk.id}"
    is Network -> desk.buildProtocolId()
    else -> "disk:${desk.name}"
}

data class FileScrollLocation(
    val diskKey: String,
    val path: String,
)

internal fun buildFileScrollLocation(desk: DiskBase, path: String): FileScrollLocation {
    val separator = desk.pathSeparator.ifBlank { "/" }
    var normalizedPath = path
    while (normalizedPath.length > separator.length && normalizedPath.endsWith(separator)) {
        normalizedPath = normalizedPath.dropLast(separator.length)
    }
    return FileScrollLocation(
        diskKey = buildDiskPathKey(desk),
        path = normalizedPath.ifBlank { separator },
    )
}

// 优先使用显式覆盖路径，其次使用记忆路径。
internal fun resolveRememberedPath(pathOverride: String?, rememberedPath: String?): String? {
    val overridePath = pathOverride?.takeIf { item -> item.isNotBlank() }
    return overridePath ?: rememberedPath?.takeIf { item -> item.isNotBlank() }
}

internal fun resolveSameDeskPathOverride(currentPath: String, pathOverride: String?): String? {
    val overridePath = pathOverride?.takeIf { item -> item.isNotBlank() } ?: return null
    return overridePath.takeIf { item -> item != currentPath }
}

internal fun launchFileTaskControlMonitor(block: suspend CoroutineScope.() -> Unit): Job {
    return CoroutineScope(SupervisorJob() + Dispatchers.Main).launch(block = block)
}

internal fun resolveFavoriteContext(desk: DiskBase): Pair<FileProtocol, String> {
    return when (desk) {
        is Local -> FileProtocol.Local to ""
        is Device -> FileProtocol.Device to desk.id
        is Share -> FileProtocol.Share to desk.id
        is NetworkAccess -> FileProtocol.Network to desk.protocolId
        else -> FileProtocol.Local to ""
    }
}

internal suspend fun recordTransferDestinationIfNeeded(
    taskType: TaskType,
    destination: FileSimpleInfo,
    result: Result<Boolean>,
    isTaskCancelled: Boolean,
    record: suspend (FileSimpleInfo) -> Unit,
) {
    if (taskType != TaskType.Copy) return
    if (isTaskCancelled) return
    if (result.isFailure || !result.getOrDefault(false)) return
    if (!supportsRecentTracking(destination)) return
    record(destination)
}

internal fun buildIncomingShareHistoryInput(
    source: FileSimpleInfo,
    destination: FileSimpleInfo,
    sourceShare: Share?,
    result: Result<Boolean>,
): ShareHistoryInput? {
    if (
        source.protocol != FileProtocol.Share ||
        source.protocolId == SYSTEM_SHARE_DESK_ID ||
        destination.protocol != FileProtocol.Local ||
        result.exceptionOrNull() is CancellationException
    ) {
        return null
    }
    val success = result.getOrNull() == true
    return ShareHistoryInput(
        fileName = source.name,
        filePath = source.path,
        fileSize = source.size,
        isDirectory = source.isDirectory,
        sourceDeviceId = source.protocolId,
        sourceDeviceName = sourceShare?.name ?: source.protocolId.ifBlank { AppStrings.ui_unknown_device },
        sourceDeviceType = sourceShare?.type ?: DeviceType.JS,
        targetDeviceId = "",
        targetDeviceName = AppStrings.ui_me,
        targetDeviceType = DeviceType.JS,
        isOutgoing = false,
        status = if (success) FileShareStatus.COMPLETED else FileShareStatus.ERROR,
        errorMessage = if (success) "" else result.exceptionOrNull().toTaskFailureMessage(AppStrings.ui_copy_failed),
        savePath = destination.path,
    )
}

internal data class WebRtcBrowserMultiZipRoot(
    val source: FileSimpleInfo,
    val zipRootName: String,
)

internal data class WebRtcBrowserMultiZipPlan(
    val fileName: String,
    val roots: List<WebRtcBrowserMultiZipRoot>,
)

internal fun buildWebRtcBrowserMultiZipPlan(sources: List<FileSimpleInfo>): WebRtcBrowserMultiZipPlan {
    val usedNames = mutableSetOf<String>()
    val roots = sources.map { source ->
        WebRtcBrowserMultiZipRoot(
            source = source,
            zipRootName = uniqueZipRootName(source.name.ifBlank { "item" }, usedNames),
        )
    }
    return WebRtcBrowserMultiZipPlan(fileName = "downloads.zip", roots = roots)
}

internal fun uniqueZipRootName(rawName: String, usedNames: MutableSet<String>): String {
    val normalized = rawName.replace('\\', '/').substringAfterLast('/').trim('/').ifBlank { "item" }
    if (normalized !in usedNames) {
        usedNames += normalized
        return normalized
    }
    val dotIndex = normalized.lastIndexOf('.')
    val (baseName, extension) = if (dotIndex > 0 && dotIndex < normalized.lastIndex) {
        normalized.substring(0, dotIndex) to normalized.substring(dotIndex)
    } else {
        normalized to ""
    }
    var index = 2
    while (true) {
        val candidate = "$baseName($index)$extension"
        if (candidate !in usedNames) {
            usedNames += candidate
            return candidate
        }
        index++
    }
}

fun Device.usesWebRtcFileTransport(): Boolean {
    return transportType == DeviceTransportType.WebRtc
}

internal fun resolveDeviceRelayPipelineConfig(device: Device): DeviceTransportPipelineConfig {
        val transferStatus = (device.fileClient ?: device.host.values.firstOrNull()?.fileRouteClient)
            ?.transferStatus()
            ?.clamped()
            ?.takeIf { status -> status.sampledAtMillis > 0L }
        val runtimePlan = HttpTransferRuntimeTuning.plan(
            maxChunkBytes = DEVICE_DIRECT_MAX_LENGTH,
            maxParallelRequests = HttpRouteClientManager.DEVICE_DIRECT_MAX_PARALLEL_REQUESTS,
        )
        val chunkSize = minOf(
            transferStatus?.recommendedChunkBytes?.coerceIn(1, DEVICE_DIRECT_MAX_LENGTH)
                ?: DEVICE_DIRECT_MAX_LENGTH,
            runtimePlan.recommendedChunkBytes.coerceIn(1, DEVICE_DIRECT_MAX_LENGTH),
        )
        val memoryBound = (HttpRouteClientManager.DEVICE_DIRECT_MAX_IN_FLIGHT_BYTES / chunkSize.toLong())
            .coerceAtLeast(1L)
            .coerceAtMost(HttpRouteClientManager.DEVICE_DIRECT_MAX_PARALLEL_REQUESTS.toLong())
            .toInt()
        val parallelism = minOf(
            transferStatus?.recommendedParallelRequests?.coerceAtLeast(1)
                ?: HttpRouteClientManager.DEVICE_DIRECT_MAX_PARALLEL_REQUESTS,
            memoryBound,
            runtimePlan.recommendedParallelRequests,
        )
        return DeviceTransportPipelineConfig(
            chunkSize = chunkSize,
            readParallelism = parallelism,
            writeParallelism = sameTargetFileWriteParallelism(parallelism),
            queueDepth = minOf(
                HttpRouteClientManager.DEVICE_DIRECT_PREFETCH_QUEUE_DEPTH,
                memoryBound,
                runtimePlan.recommendedParallelRequests,
            ).coerceAtLeast(1),
        )
}

internal data class PendingRuntimeQueue(
    val stage: TaskRuntimeStage,
    val category: TaskRuntimeQueueCategory,
)

internal const val FILE_PROPERTY_SCAN_ISSUE_LIMIT = 20
internal const val FILE_PROPERTY_TRAVERSAL_MAX_PARALLELISM = 8

data class FilePropertyScanIssue(
    val path: String,
    val message: String,
)

data class FilePropertySummary(
    val totalSize: Long = 0L,
    val fileCount: Int = 0,
    val folderCount: Int = 0,
    val skippedItemCount: Int = 0,
    val scanIssues: List<FilePropertyScanIssue> = emptyList(),
) {
    operator fun plus(other: FilePropertySummary): FilePropertySummary {
        return FilePropertySummary(
            totalSize = totalSize + other.totalSize,
            fileCount = fileCount + other.fileCount,
            folderCount = folderCount + other.folderCount,
            skippedItemCount = skippedItemCount + other.skippedItemCount,
            scanIssues = (scanIssues + other.scanIssues).take(FILE_PROPERTY_SCAN_ISSUE_LIMIT),
        )
    }

    fun withScanIssues(
        issueCount: Int,
        issues: List<FilePropertyScanIssue>,
    ): FilePropertySummary {
        val safeIssueCount = maxOf(issueCount, issues.size)
        if (safeIssueCount == 0) return this
        val remainingDetailSlots = (FILE_PROPERTY_SCAN_ISSUE_LIMIT - scanIssues.size).coerceAtLeast(0)
        return copy(
            skippedItemCount = skippedItemCount + safeIssueCount,
            scanIssues = scanIssues + issues.take(remainingDetailSlots),
        )
    }
}

internal val recoverableTransferSourceProtocols = setOf(
    FileProtocol.Local,
    FileProtocol.Device,
    FileProtocol.Share,
)
internal val recoverableTransferDestinationProtocols = setOf(FileProtocol.Local, FileProtocol.Device)

internal const val TASK_RUNTIME_STATE_FLUSH_INTERVAL = 32
internal const val TASK_RUNTIME_STATE_FLUSH_MAX_DELAY_MILLIS = 250L
internal const val TASK_RUNTIME_ADAPTIVE_COPY_QUEUE_BATCH_SIZE = 512
internal const val TASK_RUNTIME_ADAPTIVE_DELETE_QUEUE_BATCH_SIZE = 512
internal const val IGNORE_FILE_OPERATION_MAX_BYTES = 256 * 1024
internal const val TASK_IGNORED_SKIP_COUNT_VALUE_KEY = "__ignored_skip_count"
internal const val TASK_SMALL_FILE_METRIC_MAX_AVERAGE_BYTES = 256L * 1024L
internal const val TASK_WEBRTC_BROWSER_ZIP_DEST_KEY = "__webrtc_browser_zip_dest"

internal data class RuntimeSmallFileArchiveBatch(
    val queuedEntries: List<TaskRuntimeQueuedEntry>,
    val requestEntries: List<FolderSpanArchiveEntryRequest>,
)

internal data class RuntimeSmallFileArchiveSelection(
    val batches: List<RuntimeSmallFileArchiveBatch>,
    val remainingEntries: List<TaskRuntimeQueuedEntry>,
)

internal data class TaskRuntimeQueueEntriesByCategory(
    val directories: List<TaskRuntimeQueueEntry> = emptyList(),
    val emptyFiles: List<TaskRuntimeQueueEntry> = emptyList(),
    val files: List<TaskRuntimeQueueEntry> = emptyList(),
) {
    val byCategory: Map<TaskRuntimeQueueCategory, List<TaskRuntimeQueueEntry>> = linkedMapOf(
        TaskRuntimeQueueCategory.DIRECTORIES to directories,
        TaskRuntimeQueueCategory.EMPTY_FILES to emptyFiles,
        TaskRuntimeQueueCategory.FILES to files,
    )

    val totalSize: Int = directories.size + emptyFiles.size + files.size
    val totalFileBytes: Long = files
        .filter { entry -> entry.kind == TaskRuntimeEntryKind.FILE_COPY }
        .sumOf { entry -> entry.size.coerceAtLeast(0L) }
}

internal fun shouldUseCopyByteRuntimeMetrics(entries: TaskRuntimeQueueEntriesByCategory?): Boolean {
    entries ?: return false
    val fileCopyCount = entries.files.count { entry -> entry.kind == TaskRuntimeEntryKind.FILE_COPY }
    if (fileCopyCount <= 0) return false
    val totalFileBytes = entries.totalFileBytes
    if (totalFileBytes <= 0L) return false
    val averageFileBytes = totalFileBytes / fileCopyCount
    return entries.totalSize <= 1 || averageFileBytes > TASK_SMALL_FILE_METRIC_MAX_AVERAGE_BYTES
}

internal fun selectRuntimeSmallFileArchiveBatches(
    queuedEntries: List<TaskRuntimeQueuedEntry>,
    destinationRootPath: String,
    destinationSeparator: String,
): RuntimeSmallFileArchiveSelection {
    val archiveCandidates = queuedEntries.filter { queuedEntry ->
        queuedEntry.entry.kind == TaskRuntimeEntryKind.FILE_COPY && queuedEntry.entry.size > 0L
    }
    val nonArchiveEntries = queuedEntries.filterNot { queuedEntry ->
        queuedEntry.entry.kind == TaskRuntimeEntryKind.FILE_COPY && queuedEntry.entry.size > 0L
    }
    if (archiveCandidates.isEmpty()) {
        return RuntimeSmallFileArchiveSelection(batches = emptyList(), remainingEntries = queuedEntries)
    }
    val safeSeparator = destinationSeparator.ifBlank { "/" }
    val trimmedRoot = destinationRootPath.trim()
    val rootPrefix = when {
        trimmedRoot == "/" || trimmedRoot == "\\" -> safeSeparator
        else -> trimmedRoot.trimEnd('/', '\\')
    }
    if (rootPrefix.isBlank()) {
        return RuntimeSmallFileArchiveSelection(batches = emptyList(), remainingEntries = queuedEntries)
    }

    fun relativePathFor(entry: TaskRuntimeQueuedEntry): String {
        val destinationPath = entry.entry.dest.path
        if (destinationPath == rootPrefix) return ""
        val rootWithSeparator = if (rootPrefix.endsWith(safeSeparator)) rootPrefix else rootPrefix + safeSeparator
        if (!destinationPath.startsWith(rootWithSeparator)) return ""
        return destinationPath
            .removePrefix(rootWithSeparator)
            .replace('\\', '/')
    }

    val archiveSelection = selectSmallFileArchiveBatches(
        items = archiveCandidates,
        sourcePath = { queuedEntry -> queuedEntry.entry.src.path },
        relativePath = { queuedEntry -> relativePathFor(queuedEntry) },
        size = { queuedEntry -> queuedEntry.entry.size },
    )
    return RuntimeSmallFileArchiveSelection(
        batches = archiveSelection.batches.map { batch ->
            RuntimeSmallFileArchiveBatch(
                queuedEntries = batch.items,
                requestEntries = batch.requestEntries,
            )
        },
        remainingEntries = nonArchiveEntries + archiveSelection.remainingItems,
    )
}

internal data class PendingFileOperation(
    val src: FileSimpleInfo,
    val dest: FileSimpleInfo,
    val replaceTarget: FileSimpleInfo? = null,
)

internal data class CopyQueueBuildResult(
    val entries: TaskRuntimeQueueEntriesByCategory,
    val protectedSourceDirectories: Set<String> = emptySet(),
    val skippedCount: Int = 0,
    val rejectedCount: Int = 0,
    val sourceSeparator: String = "/",
)

internal fun buildCopyQueueEntriesForOperation(
    src: FileSimpleInfo,
    dest: FileSimpleInfo,
    sourceSeparator: String,
    ignoreMatcher: ResolvedIgnoreMatcher?,
    children: List<FileSimpleInfo> = emptyList(),
): CopyQueueBuildResult {
    if (ignoreMatcher?.matchesOperationSkip(src.path, src.isDirectory) == true) {
        return CopyQueueBuildResult(
            entries = TaskRuntimeQueueEntriesByCategory(),
            skippedCount = 1,
            sourceSeparator = sourceSeparator,
        )
    }

    if (!src.isDirectory) {
        val entry = buildTaskRuntimeCopyQueueEntry(
            order = 0,
            src = src,
            dest = dest,
        )
        return CopyQueueBuildResult(
            entries = if (src.size == 0L) {
                TaskRuntimeQueueEntriesByCategory(emptyFiles = listOf(entry))
            } else {
                TaskRuntimeQueueEntriesByCategory(files = listOf(entry))
            },
            sourceSeparator = sourceSeparator,
        )
    }

    val filteredChildren = filterIgnoredOperationEntries(
        root = src,
        entries = children,
        matcher = ignoreMatcher,
        separator = sourceSeparator,
    )
    val directories = (listOf(src) + filteredChildren.entries.filter { entry -> entry.isDirectory })
        .sortedWith(
            compareBy<FileSimpleInfo> { entry -> entry.path.pathLevel() }
                .thenBy { entry -> entry.path }
        )
        .mapIndexed { index, entry ->
            buildTaskRuntimeCopyQueueEntry(index, entry, entry.toCopyDestination(src, dest))
        }
    val files = filteredChildren.entries
        .filter { entry -> !entry.isDirectory && entry.size != 0L }
        .sortedWith(
            compareBy<FileSimpleInfo> { entry -> entry.path.pathLevel() }
                .thenBy { entry -> entry.path }
        )
        .mapIndexed { index, entry ->
            buildTaskRuntimeCopyQueueEntry(index, entry, entry.toCopyDestination(src, dest))
        }
    val emptyFiles = filteredChildren.entries
        .filter { entry -> !entry.isDirectory && entry.size == 0L }
        .sortedWith(
            compareBy<FileSimpleInfo> { entry -> entry.path.pathLevel() }
                .thenBy { entry -> entry.path }
        )
        .mapIndexed { index, entry ->
            buildTaskRuntimeCopyQueueEntry(index, entry, entry.toCopyDestination(src, dest))
        }
    return CopyQueueBuildResult(
        entries = TaskRuntimeQueueEntriesByCategory(
            directories = directories,
            emptyFiles = emptyFiles,
            files = files,
        ),
        protectedSourceDirectories = filteredChildren.protectedSourceDirectories,
        skippedCount = filteredChildren.skippedCount,
        sourceSeparator = sourceSeparator,
    )
}

internal fun buildDeleteQueueEntriesFromCopyPlan(
    copyPlan: CopyQueueBuildResult,
    stage: TaskRuntimeStage,
): TaskRuntimeQueueEntriesByCategory {
    val sourceEntries = (copyPlan.entries.files + copyPlan.entries.emptyFiles + copyPlan.entries.directories)
        .map { entry -> entry.toOperationSourceFileSimpleInfo() }
    val files = sourceEntries
        .filterNot { entry -> entry.isDirectory }
        .sortedWith(
            compareByDescending<FileSimpleInfo> { entry -> entry.path.pathLevel() }
                .thenBy { entry -> entry.path }
        )
        .mapIndexed { index, entry ->
            buildTaskRuntimeDeleteQueueEntry(
                order = index,
                stage = stage,
                target = entry,
            )
        }
    val directories = sourceEntries
        .filter { entry -> entry.isDirectory }
        .filterNot { entry ->
            normalizeQueuePath(entry.path, copyPlan.sourceSeparator) in copyPlan.protectedSourceDirectories
        }
        .sortedWith(
            compareByDescending<FileSimpleInfo> { entry -> entry.path.pathLevel() }
                .thenBy { entry -> entry.path }
        )
        .mapIndexed { index, entry ->
            buildTaskRuntimeDeleteQueueEntry(
                order = index,
                stage = stage,
                target = entry,
            )
        }
    return TaskRuntimeQueueEntriesByCategory(directories = directories, files = files)
}

internal fun buildTaskRuntimeCopyQueueEntry(
    order: Int,
    src: FileSimpleInfo,
    dest: FileSimpleInfo,
): TaskRuntimeQueueEntry {
    return TaskRuntimeQueueEntry(
        entryId = buildTaskRetryEntryKey(
            stage = TaskRetryStage.COPY,
            srcPath = src.path,
            srcProtocol = src.protocol,
            srcProtocolId = src.protocolId,
            destPath = dest.path,
            destProtocol = dest.protocol,
            destProtocolId = dest.protocolId,
        ),
        stage = TaskRuntimeStage.COPY,
        kind = if (src.isDirectory) {
            TaskRuntimeEntryKind.DIRECTORY_CREATE
        } else if (src.size == 0L) {
            TaskRuntimeEntryKind.EMPTY_FILE_CREATE
        } else {
            TaskRuntimeEntryKind.FILE_COPY
        },
        order = order,
        src = src.toEndpointRef(),
        dest = dest.toEndpointRef(),
        isDirectory = src.isDirectory,
        size = src.size,
    )
}

internal fun buildTaskRuntimeDeleteQueueEntry(
    order: Int,
    stage: TaskRuntimeStage,
    target: FileSimpleInfo,
): TaskRuntimeQueueEntry {
    val retryStage = when (stage) {
        TaskRuntimeStage.COPY -> TaskRetryStage.COPY
        TaskRuntimeStage.DELETE_SOURCE -> TaskRetryStage.DELETE_SOURCE
        TaskRuntimeStage.DELETE -> TaskRetryStage.DELETE
    }
    return TaskRuntimeQueueEntry(
        entryId = buildTaskRetryEntryKey(
            stage = retryStage,
            srcPath = target.path,
            srcProtocol = target.protocol,
            srcProtocolId = target.protocolId,
            destPath = "",
            destProtocol = FileProtocol.Local,
            destProtocolId = "",
        ),
        stage = stage,
        kind = when (stage) {
            TaskRuntimeStage.DELETE_SOURCE -> TaskRuntimeEntryKind.SOURCE_DELETE
            TaskRuntimeStage.DELETE -> TaskRuntimeEntryKind.TARGET_DELETE
            TaskRuntimeStage.COPY -> TaskRuntimeEntryKind.FILE_COPY
        },
        order = order,
        src = target.toEndpointRef(),
        isDirectory = target.isDirectory,
        size = target.size,
    )
}

internal fun TaskRuntimeQueueEntry.toOperationSourceFileSimpleInfo(): FileSimpleInfo {
    return buildRetryFileSimpleInfo(
        path = src.path,
        protocol = src.protocol,
        protocolId = src.protocolId,
        isDirectory = isDirectory,
        size = size,
    )
}

internal fun normalizeQueuePath(path: String, separator: String): String {
    val normalizedSeparator = separator.ifBlank { "/" }
    var normalized = path.trim()
    normalized = if (normalizedSeparator == "/") {
        normalized.replace('\\', '/')
    } else {
        normalized.replace("/", normalizedSeparator)
    }
    while (normalized.length > normalizedSeparator.length && normalized.endsWith(normalizedSeparator)) {
        normalized = normalized.dropLast(normalizedSeparator.length)
    }
    return normalized.ifBlank { normalizedSeparator }
}

internal fun TaskRuntimeStage.orderedQueueCategories(): List<TaskRuntimeQueueCategory> {
    return when (this) {
        TaskRuntimeStage.COPY -> listOf(
            TaskRuntimeQueueCategory.DIRECTORIES,
            TaskRuntimeQueueCategory.EMPTY_FILES,
            TaskRuntimeQueueCategory.FILES,
        )

        TaskRuntimeStage.DELETE_SOURCE,
        TaskRuntimeStage.DELETE -> listOf(
            TaskRuntimeQueueCategory.FILES,
            TaskRuntimeQueueCategory.DIRECTORIES,
        )
    }
}


data class FileScrollPosition(
    val index: Int,
    val offset: Int
)
