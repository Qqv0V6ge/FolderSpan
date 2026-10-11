package com.folderspan.ui.state.main

import strings.AppStrings

import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable
import kotlin.time.Clock

enum class SyncEndpointType {
    Local,
    Device,
    Network,
}

enum class SyncConflictPolicy {
    Replace,
    Skip,
    Rename,
}

enum class SyncScheduleType {
    Manual,
    Interval,
}

enum class SyncRunStatus {
    Idle,
    Queued,
    Running,
    Success,
    PartialSuccess,
    Failure,
    Canceled,
}

internal const val RUN_HISTORY_LIMIT = 30
internal const val RUN_ITEM_LIMIT = 200

internal fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()

internal fun parseSyncFilterPatterns(filterRaw: String): List<String> {
    return filterRaw
        .split(',', ';', '\n', '\r')
        .map { item -> item.trim() }
        .filter { item -> item.isNotBlank() }
}

internal fun wildcardPatternToRegex(pattern: String): Regex {
    val body = buildString {
        for (char in pattern) {
            when (char) {
                '*' -> append(".*")
                '?' -> append('.')
                '.', '(', ')', '[', ']', '{', '}', '+', '^', '$', '|', '\\' -> {
                    append('\\')
                    append(char)
                }

                else -> append(char)
            }
        }
    }
    return Regex("^$body$", RegexOption.IGNORE_CASE)
}

internal fun syncFilterMatches(relativePath: String, fileName: String, patterns: List<String>): Boolean {
    if (patterns.isEmpty()) return false
    val normalizedRelative = relativePath.replace('\\', '/').trimStart('/')
    return patterns.any { pattern ->
        val regex = wildcardPatternToRegex(pattern)
        regex.matches(fileName) || regex.matches(normalizedRelative)
    }
}

internal fun syncFileChanged(source: FileSimpleInfo, target: FileSimpleInfo): Boolean {
    if (source.isDirectory != target.isDirectory) return true
    if (source.isDirectory) return false
    if (source.size != target.size) return true

    val sourceUpdated = source.updatedDate
    val targetUpdated = target.updatedDate
    return sourceUpdated > 0L && targetUpdated > 0L && sourceUpdated > targetUpdated
}

internal fun splitFileNameAndExt(fileName: String): Pair<String, String> {
    val dot = fileName.lastIndexOf('.')
    if (dot <= 0 || dot >= fileName.length - 1) {
        return fileName to ""
    }
    return fileName.substring(0, dot) to fileName.substring(dot)
}

internal fun isRootPath(path: String, separator: String): Boolean {
    val normalized = if (separator == "/") {
        path.replace('\\', '/')
    } else {
        path.replace('/', '\\')
    }

    if (normalized == separator) return true
    if (separator == "\\") {
        return Regex("^[A-Za-z]:\\\\?$").matches(normalized)
    }
    return false
}

internal fun normalizePathForCompare(path: String, separator: String): String {
    var normalized = path.trim()
    if (normalized.isBlank()) return separator
    normalized = if (separator == "/") {
        normalized.replace('\\', '/')
    } else {
        normalized.replace('/', '\\')
    }

    while (normalized.length > separator.length && normalized.endsWith(separator) && !isRootPath(normalized, separator)) {
        normalized = normalized.dropLast(separator.length)
    }
    return normalized
}

internal fun parentPathOf(path: String, separator: String): String {
    val normalized = normalizePathForCompare(path, separator)
    if (isRootPath(normalized, separator)) return normalized

    val trimmed = normalized.trimEnd('/', '\\')
    val slashIndex = maxOf(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'))

    if (slashIndex < 0) return separator
    if (slashIndex == 0) return trimmed.substring(0, 1)
    if (separator == "\\" && slashIndex == 2 && trimmed.getOrNull(1) == ':') {
        return trimmed.substring(0, 3)
    }

    return trimmed.substring(0, slashIndex)
}

internal fun fileNameOf(path: String, separator: String): String {
    val normalized = normalizePathForCompare(path, separator).trimEnd('/', '\\')
    if (isRootPath(normalized, separator)) return normalized

    val slashIndex = maxOf(normalized.lastIndexOf('/'), normalized.lastIndexOf('\\'))
    return if (slashIndex >= 0) normalized.substring(slashIndex + 1) else normalized
}

internal fun joinPath(parent: String, child: String, separator: String): String {
    val normalizedParent = normalizePathForCompare(parent, separator)
    val normalizedChild = if (separator == "/") {
        child.replace('\\', '/').trimStart('/')
    } else {
        child.replace('/', '\\').trimStart('\\')
    }

    if (normalizedChild.isBlank()) return normalizedParent
    if (isRootPath(normalizedParent, separator)) {
        return normalizedParent + normalizedChild
    }

    return normalizedParent.trimEnd('/', '\\') + separator + normalizedChild
}

internal fun buildSyncRenamePath(
    path: String,
    isDirectory: Boolean,
    occupied: Set<String>,
    separator: String,
): String {
    val normalizedOccupied = occupied.mapTo(mutableSetOf()) { item ->
        normalizePathForCompare(item, separator)
    }
    var candidate = normalizePathForCompare(path, separator)
    var index = 1
    while (normalizedOccupied.contains(normalizePathForCompare(candidate, separator))) {
        val parent = parentPathOf(path, separator)
        val fileName = fileNameOf(path, separator)
        val (base, ext) = if (isDirectory) {
            fileName to ""
        } else {
            splitFileNameAndExt(fileName)
        }
        candidate = joinPath(parent, "$base($index)$ext", separator)
        index++
    }
    return candidate
}

internal fun syncTargetInsideSourcePath(
    sourcePath: String,
    targetPath: String,
    separator: String,
): Boolean {
    val normalizedSource = normalizePathForCompare(sourcePath, separator)
    val normalizedTarget = normalizePathForCompare(targetPath, separator)
    if (normalizedSource == normalizedTarget) return false
    val prefix = if (isRootPath(normalizedSource, separator)) {
        normalizedSource
    } else {
        normalizedSource + separator
    }
    return normalizedTarget.startsWith(prefix)
}

internal fun sanitizeSyncMessage(rawMessage: String?): String {
    if (rawMessage.isNullOrBlank()) return ""

    var message: String = rawMessage
    val sensitivePatterns = listOf(
        Regex("(?i)(password\\s*[:=]\\s*)([^\\s,;]+)"),
        Regex("(?i)(token\\s*[:=]\\s*)([^\\s,;]+)"),
        Regex("(?i)(authorization\\s*[:=]\\s*)([^\\s,;]+)"),
    )

    sensitivePatterns.forEach { pattern ->
        message = pattern.replace(message) { match ->
            val prefix = match.groupValues.getOrElse(1) { "" }
            "$prefix***"
        }
    }

    return message
}

internal fun toProtocol(type: SyncEndpointType): FileProtocol {
    return when (type) {
        SyncEndpointType.Local -> FileProtocol.Local
        SyncEndpointType.Device -> FileProtocol.Device
        SyncEndpointType.Network -> FileProtocol.Network
    }
}

internal fun isNotFoundMessage(message: String?): Boolean {
    if (message.isNullOrBlank()) return false
    val text = message.lowercase()
    return text.contains("not found") ||
            text.contains("no such") ||
            text.contains("does not exist") ||
            text.contains(AppStrings.ui_there_is_no_such_thing) ||
            text.contains(AppStrings.ui_not_found) ||
            text.contains("enoent") ||
            text.contains("404")
}

internal fun syncTargetEndpointReadinessError(
    type: SyncEndpointType,
    exists: Boolean,
    online: Boolean,
    connected: Boolean,
): String? {
    if (type == SyncEndpointType.Local) return null

    val label = when (type) {
        SyncEndpointType.Local -> return null
        SyncEndpointType.Device -> AppStrings.notification_category_device
        SyncEndpointType.Network -> AppStrings.ui_network_disk
    }

    if (!exists) return AppStrings.ui_target_arg0_does_not_exist.format(arg0 = label)
    if (!online) return AppStrings.ui_target_arg0_not_online.format(arg0 = label)
    if (!connected) return AppStrings.ui_target_arg0_not_connected.format(arg0 = label)
    return null
}

internal fun syncTargetEndpointMissingError(
    type: SyncEndpointType,
    exists: Boolean,
): String? {
    return when (type) {
        SyncEndpointType.Local -> null
        SyncEndpointType.Device -> if (exists) null else AppStrings.ui_target_device_does_not_exist
        SyncEndpointType.Network -> if (exists) null else AppStrings.ui_target_network_disk_does_not_exist
    }
}

internal fun syncSkippedMessage(reason: String): String {
    val normalized = reason.trim()
    return if (normalized.isBlank()) AppStrings.ui_skipped else AppStrings.ui_skipped_arg0.format(arg0 = normalized)
}

@Serializable
data class SyncTask(
    @EncodeDefault val id: Long,
    @EncodeDefault val name: String,
    @EncodeDefault val enabled: Boolean = true,
    @EncodeDefault val sourceType: SyncEndpointType,
    @EncodeDefault val sourceRef: String = "",
    @EncodeDefault val sourcePath: String,
    @EncodeDefault val targetType: SyncEndpointType,
    @EncodeDefault val targetRef: String = "",
    @EncodeDefault val targetPath: String,
    @EncodeDefault val includeSubdirectories: Boolean = true,
    @EncodeDefault val includeEmptyDirectories: Boolean = true,
    @EncodeDefault val filterRaw: String = "",
    @EncodeDefault val useIgnoreFiles: Boolean = false,
    @EncodeDefault val ignoreFileNames: List<String> = emptyList(),
    @EncodeDefault val conflictPolicy: SyncConflictPolicy = SyncConflictPolicy.Replace,
    @EncodeDefault val scheduleType: SyncScheduleType = SyncScheduleType.Manual,
    @EncodeDefault val intervalMinutes: Long = 0,
    @EncodeDefault val lastRunAt: Long = 0,
    @EncodeDefault val nextRunAt: Long = 0,
    @EncodeDefault val lastStatus: SyncRunStatus = SyncRunStatus.Idle,
    @EncodeDefault val lastMessage: String = "",
    @EncodeDefault val createdAt: Long = nowMillis(),
    @EncodeDefault val updatedAt: Long = nowMillis(),
)

@Serializable
data class SyncRunItem(
    @EncodeDefault val path: String,
    @EncodeDefault val success: Boolean,
    @EncodeDefault val message: String = "",
)

@Serializable
data class SyncRunRecord(
    @EncodeDefault val runId: Long,
    @EncodeDefault val taskId: Long,
    @EncodeDefault val trigger: String,
    @EncodeDefault val startedAt: Long,
    @EncodeDefault val endedAt: Long,
    @EncodeDefault val status: SyncRunStatus,
    @EncodeDefault val totalCount: Int,
    @EncodeDefault val successCount: Int,
    @EncodeDefault val failureCount: Int,
    @EncodeDefault val message: String = "",
    @EncodeDefault val items: List<SyncRunItem> = emptyList(),
)

data class SyncEndpoint(
    val type: SyncEndpointType,
    val ref: String,
    val label: String,
    val canRead: Boolean = true,
    val canWrite: Boolean = true,
)

internal data class SyncSourceFileEntry(
    val relativePath: String,
    val file: FileSimpleInfo,
)

internal data class SyncSourceSnapshot(
    val files: List<SyncSourceFileEntry>,
    val directories: List<String>,
)

internal data class SyncTargetSnapshot(
    val rootInfo: FileSimpleInfo?,
    val filesByRelative: MutableMap<String, FileSimpleInfo>,
    val filesByAbsolute: MutableMap<String, FileSimpleInfo>,
    val directoryPaths: MutableSet<String>,
    val occupiedPaths: MutableSet<String>,
)

internal data class SyncExecutionSummary(
    val status: SyncRunStatus,
    val successCount: Int,
    val failureCount: Int,
    val message: String,
    val items: List<SyncRunItem>,
)
