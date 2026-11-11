package com.folderspan.ui.state.main

import com.folderspan.utils.currentSyncTimeZone

import strings.AppStrings

import com.folderspan.ignore.ResolvedIgnoreMatcher
import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import kotlinx.serialization.EncodeDefault
import com.folderspan.service.network.StorageReference
import com.folderspan.service.network.StorageCapabilities
import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

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
    Daily,
    Weekly,
}

enum class SyncRunStatus {
    Idle,
    Queued,
    Running,
    Success,
    PartialSuccess,
    Failure,
    Canceled,
    Interrupted,
}

enum class SyncMode { Update, Mirror, BackupThenClean, Bidirectional }
enum class SyncBidiConflictPolicy { KeepBoth, Newer, Source, Target }
enum class SyncNotifyLevel { All, FailuresAndConfirmations, Off }

internal fun syncModeEndpointError(mode: SyncMode, source: Boolean, directory: Boolean, capabilities: StorageCapabilities): String? = when {
    !directory && mode != SyncMode.Update -> AppStrings.sync_mode_directory_required
    !capabilities.sync -> if (source) AppStrings.ui_source_endpoint_unavailable else AppStrings.ui_target_endpoint_unavailable
    (source || mode == SyncMode.Bidirectional) && !capabilities.read -> AppStrings.ui_source_endpoint_does_not_support_reading
    (!source || mode == SyncMode.Bidirectional) && !capabilities.write -> AppStrings.ui_target_endpoint_does_not_support_writing
    source && mode in listOf(SyncMode.BackupThenClean, SyncMode.Bidirectional) && !capabilities.delete -> AppStrings.sync_source_delete_required
    !source && mode in listOf(SyncMode.Mirror, SyncMode.Bidirectional) && !capabilities.delete -> AppStrings.sync_target_delete_required
    else -> null
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
    return sourceUpdated > 0L && targetUpdated > 0L && sourceUpdated > targetUpdated && sourceUpdated - targetUpdated > 2_000L
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

    var message = Regex("""([A-Za-z][A-Za-z0-9+.-]*://)[^\s/?#]*@""").replace(rawMessage) { match ->
        "${match.groupValues[1]}***@"
    }
    val sensitivePatterns = listOf(
        Regex("""(?i)(\bBearer\s+)([^\s,;]+)"""),
        Regex("""(?i)((?:password|(?:access_|refresh_)?token)\s*[:=]\s*)([^\s,;&]+)"""),
        Regex("""(?i)(authorization\s*[:=]\s*)(?:(?:Bearer|Basic)\s+)?([^\s,;&]+)"""),
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
    val sourceStorageReference: StorageReference? = null,
    val targetStorageReference: StorageReference? = null,
    @EncodeDefault val policyVersion: Long = 1,
    @EncodeDefault val backup: Boolean = false,
    @EncodeDefault val destinations: List<StorageDestination> = emptyList(),
    @EncodeDefault val selectRecommended: Boolean = false,
    @EncodeDefault val preferredEntryIds: List<Long> = emptyList(),
    @EncodeDefault val selectedSources: List<FileSimpleInfo> = emptyList(),
    @EncodeDefault val retryCount: Int = 1,
    @EncodeDefault val timeoutSeconds: Int = 120,
    @EncodeDefault val parallelism: Int = 1,
    @EncodeDefault val ownerDeviceId: String = "",
    @EncodeDefault val ownerDeviceName: String = "",
    @EncodeDefault val mode: SyncMode = SyncMode.Update,
    @EncodeDefault val bidiConflictPolicy: SyncBidiConflictPolicy = SyncBidiConflictPolicy.KeepBoth,
    @EncodeDefault val recycleEnabled: Boolean = true,
    @EncodeDefault val recycleRetentionDays: Int = 30,
    @EncodeDefault val deleteThreshold: Int = 100,
    @EncodeDefault val includeRaw: String = "",
    @EncodeDefault val scheduleTimeMinutes: Int = 120,
    @EncodeDefault val scheduleWeekdays: Int = 0,
    @EncodeDefault val triggerOnDeviceConnect: Boolean = false,
    @EncodeDefault val triggerOnNetworkAvailable: Boolean = false,
    @EncodeDefault val requireWifi: Boolean = false,
    @EncodeDefault val requireCharging: Boolean = false,
    @EncodeDefault val notifyLevel: SyncNotifyLevel = SyncNotifyLevel.FailuresAndConfirmations,
    @EncodeDefault val currentSourceReferences: Map<String, StorageReference> = emptyMap(),
)

@Serializable
enum class SyncRunItemKind { Other, Copy, Rename, CreateDirectory, Unchanged, Skip, Delete, Recycle, Overwrite, Conflict, CleanSource, Recovery }

@Serializable
data class SyncRunResultCount(val kind: SyncRunItemKind, val successCount: Long = 0, val failureCount: Long = 0)

data class SyncRunDetailRow(val ordinal: Long, val item: SyncRunItem)

@Serializable
data class SyncRunItem(
    @EncodeDefault val path: String,
    @EncodeDefault val success: Boolean,
    @EncodeDefault val message: String = "",
    val source: FileSimpleInfo? = null,
    val kind: SyncRunItemKind = SyncRunItemKind.Other,
    val participant: String = "",
    val recoveryGroup: String = "",
    val recoveryLocations: List<SyncRecoveryLocation> = emptyList(),
)

@Serializable
data class SyncRunParticipant(
    val id: String,
    val label: String,
    val type: SyncEndpointType,
    val ref: String,
    val paths: List<String>,
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
    val policySnapshot: SyncTask? = null,
    @EncodeDefault val targets: List<StorageTargetRun> = emptyList(),
    @EncodeDefault val sourceManifest: List<StorageSourceEntry> = emptyList(),
    @EncodeDefault val sourceDirectories: List<String> = emptyList(),
    @EncodeDefault val sourceDigests: Map<String, String> = emptyMap(),
    @EncodeDefault val copiedCount: Int = 0,
    @EncodeDefault val renamedCount: Int = 0,
    @EncodeDefault val createdDirectoryCount: Int = 0,
    @EncodeDefault val unchangedCount: Int = 0,
    @EncodeDefault val skippedCount: Int = 0,
    @EncodeDefault val transferredBytes: Long = 0,
    @EncodeDefault val transferDurationMillis: Long = 0,
    @EncodeDefault val unsavedFailureCount: Int = 0,
    @EncodeDefault val retryOfRunId: Long = 0,
    @EncodeDefault val participants: List<SyncRunParticipant> = emptyList(),
    @EncodeDefault val resultCounts: List<SyncRunResultCount> = emptyList(),
    @EncodeDefault val detailCount: Long = 0,
    @EncodeDefault val unsavedDetailCount: Long = 0,
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
    val protectedPaths: Set<String> = emptySet(),
    val ignoreMatcher: ResolvedIgnoreMatcher? = null,
)

internal data class SyncTargetSnapshot(
    val rootInfo: FileSimpleInfo?,
    val filesByRelative: MutableMap<String, FileSimpleInfo>,
    val filesByAbsolute: MutableMap<String, FileSimpleInfo>,
    val directoryPaths: MutableSet<String>,
    val occupiedPaths: MutableSet<String>,
    val protectedPaths: Set<String> = emptySet(),
)

internal data class SyncExecutionSummary(
    val status: SyncRunStatus,
    val successCount: Int,
    val failureCount: Int,
    val message: String,
    val items: List<SyncRunItem>,
    val copiedCount: Int = 0,
    val renamedCount: Int = 0,
    val createdDirectoryCount: Int = 0,
    val unchangedCount: Int = 0,
    val skippedCount: Int = 0,
    val transferredBytes: Long = 0,
    val transferDurationMillis: Long = 0,
    val failedItems: List<SyncFailureItem> = emptyList(),
)

@Serializable
data class SyncFailureItem(
    val relativePath: String,
    val isDirectory: Boolean,
    val message: String = "",
)

fun computeNextRun(task: SyncTask, now: Long, timeZone: TimeZone = currentSyncTimeZone()): Long {
    if (!task.enabled || task.scheduleType == SyncScheduleType.Manual) return 0
    if (task.scheduleType == SyncScheduleType.Daily || task.scheduleType == SyncScheduleType.Weekly) {
        if (task.scheduleTimeMinutes !in 0..1439 || task.scheduleType == SyncScheduleType.Weekly && task.scheduleWeekdays !in 1..127) return 0
        val today = Instant.fromEpochMilliseconds(now).toLocalDateTime(timeZone).date
        for (offset in 0..7) {
            val date = today.plus(offset, DateTimeUnit.DAY)
            if (task.scheduleType == SyncScheduleType.Weekly && task.scheduleWeekdays and (1 shl (date.dayOfWeek.isoDayNumber - 1)) == 0) continue
            val candidate = date.atTime(task.scheduleTimeMinutes / 60, task.scheduleTimeMinutes % 60).toInstant(timeZone).toEpochMilliseconds()
            if (candidate > now) return candidate
        }
        return 0
    }
    val minutes = task.intervalMinutes.coerceAtLeast(1)
    return if (minutes > (Long.MAX_VALUE - now.coerceAtLeast(0)) / 60_000L) Long.MAX_VALUE
    else now + minutes * 60_000L
}

fun SyncTask.withMode(value: SyncMode): SyncTask = copy(
    mode = value,
    conflictPolicy = if (value == SyncMode.BackupThenClean && conflictPolicy == SyncConflictPolicy.Replace) SyncConflictPolicy.Rename else conflictPolicy,
)

internal fun syncCapabilitySettingsError(task: SyncTask): String? = when {
    task.recycleRetentionDays !in 1..365 -> AppStrings.sync_invalid_retention
    task.deleteThreshold !in 1..100_000 -> AppStrings.sync_invalid_delete_threshold
    task.mode == SyncMode.BackupThenClean && task.conflictPolicy == SyncConflictPolicy.Replace -> AppStrings.sync_backup_replace_forbidden
    task.scheduleType in listOf(SyncScheduleType.Daily, SyncScheduleType.Weekly) && task.scheduleTimeMinutes !in 0..1439 -> AppStrings.sync_invalid_schedule_time
    task.scheduleType == SyncScheduleType.Weekly && task.scheduleWeekdays !in 1..127 -> AppStrings.sync_weekday_required
    else -> null
}

fun isSyncTaskOwnedBy(task: SyncTask, currentDeviceId: String): Boolean =
    currentDeviceId.isBlank() || task.ownerDeviceId.isBlank() || task.ownerDeviceId == currentDeviceId

class SyncReverseTaskException(val tasks: List<SyncTask>) : IllegalArgumentException("Reverse synchronization tasks require confirmation")

fun findReverseSyncTasks(
    candidate: SyncTask,
    tasks: List<SyncTask>,
    separatorOf: (SyncEndpointType, String) -> String,
    currentDeviceId: String = candidate.ownerDeviceId,
): List<SyncTask> {
    if (candidate.mode == SyncMode.Bidirectional) return emptyList()
    fun overlaps(left: String, right: String, separator: String): Boolean =
        normalizePathForCompare(left, separator) == normalizePathForCompare(right, separator) ||
            syncTargetInsideSourcePath(left, right, separator) || syncTargetInsideSourcePath(right, left, separator)
    return tasks.filter { task ->
        task.id != candidate.id && task.mode != SyncMode.Bidirectional && isSyncTaskOwnedBy(task, currentDeviceId) &&
            candidate.sourceType == task.targetType && candidate.sourceRef == task.targetRef &&
            candidate.targetType == task.sourceType && candidate.targetRef == task.sourceRef &&
            candidate.sourceStorageReference == task.targetStorageReference &&
            candidate.targetStorageReference == task.sourceStorageReference &&
            overlaps(candidate.sourcePath, task.targetPath, separatorOf(candidate.sourceType, candidate.sourceRef)) &&
            overlaps(candidate.targetPath, task.sourcePath, separatorOf(candidate.targetType, candidate.targetRef))
    }
}

data class SyncTaskSnapshotMerge(
    val tasks: List<SyncTask>,
    val deletedTaskIds: Set<Long>,
    val clearedRunTaskIds: Set<Long>,
)

internal fun sameSyncSchedule(previous: SyncTask, incoming: SyncTask): Boolean =
    previous.enabled == incoming.enabled && previous.scheduleType == incoming.scheduleType && when (incoming.scheduleType) {
        SyncScheduleType.Manual -> true
        SyncScheduleType.Interval -> previous.intervalMinutes == incoming.intervalMinutes
        SyncScheduleType.Daily -> previous.scheduleTimeMinutes == incoming.scheduleTimeMinutes
        SyncScheduleType.Weekly -> previous.scheduleTimeMinutes == incoming.scheduleTimeMinutes && previous.scheduleWeekdays == incoming.scheduleWeekdays
    }

internal fun matchesSyncDueRun(expected: SyncTask, current: SyncTask?, scheduledAt: Long, now: Long): Boolean =
    current != null && current.enabled && current.scheduleType != SyncScheduleType.Manual &&
        scheduledAt > 0 && scheduledAt <= now && current.nextRunAt == scheduledAt &&
        current.ownerDeviceId == expected.ownerDeviceId && syncPreviewFingerprint(current) == syncPreviewFingerprint(expected)

internal fun nextSyncRunAfterFinish(
    current: SyncTask, started: SyncTask, record: SyncRunRecord, now: Long,
    timeZone: TimeZone = currentSyncTimeZone(),
): Long = when {
    !current.enabled || current.scheduleType == SyncScheduleType.Manual -> 0L
    !sameSyncSchedule(current, started) || current.nextRunAt != started.nextRunAt -> current.nextRunAt
    record.trigger == "event" && current.nextRunAt > record.endedAt -> current.nextRunAt
    else -> computeNextRun(current, now, timeZone)
}

internal fun nextSyncRunOnLoad(task: SyncTask, now: Long, interrupted: Boolean, owned: Boolean,
    previousTimeZone: String = "", timeZone: TimeZone = currentSyncTimeZone()): Long = when {
    !owned || !task.enabled || task.scheduleType == SyncScheduleType.Manual -> 0L
    task.scheduleType in listOf(SyncScheduleType.Daily, SyncScheduleType.Weekly) &&
        previousTimeZone.isNotEmpty() && previousTimeZone != timeZone.id -> computeNextRun(task, now, timeZone)
    task.nextRunAt > 0 -> task.nextRunAt
    interrupted -> now
    else -> computeNextRun(task, now, timeZone)
}

fun mergeSyncTaskSnapshot(
    local: List<SyncTask>,
    remote: List<SyncTask>,
    currentDeviceId: String,
    now: Long,
): SyncTaskSnapshotMerge {
    val localById = local.associateBy { it.id }
    val remoteIds = remote.mapTo(mutableSetOf()) { it.id }
    val deleted = localById.keys - remoteIds
    val cleared = deleted.toMutableSet()
    val merged = remote.map { incoming ->
        val previous = localById[incoming.id]
        val sameOwner = previous != null && previous.ownerDeviceId == incoming.ownerDeviceId
        if (previous != null && !sameOwner) cleared.add(incoming.id)
        val owned = isSyncTaskOwnedBy(incoming, currentDeviceId)
        val nextRunAt = when {
            !owned || !incoming.enabled || incoming.scheduleType == SyncScheduleType.Manual -> 0L
            sameOwner && sameSyncSchedule(previous, incoming) && previous.nextRunAt > 0 -> previous.nextRunAt
            else -> computeNextRun(incoming, now)
        }
        incoming.copy(
            lastRunAt = if (sameOwner) previous.lastRunAt else 0,
            lastStatus = if (sameOwner) previous.lastStatus else SyncRunStatus.Idle,
            lastMessage = if (sameOwner) previous.lastMessage else "",
            nextRunAt = nextRunAt,
        )
    }
    return SyncTaskSnapshotMerge(merged, deleted, cleared)
}

internal enum class SyncPlanAction { CreateDirectory, Copy, RenameCopy, Unchanged, Skip }
internal enum class SyncPlanSkipReason { Conflict, Protected, SelectedTypeConflict }

internal data class SyncPlanItem(
    val action: SyncPlanAction,
    val relativePath: String,
    val targetPath: String,
    val source: FileSimpleInfo? = null,
    val targetExisted: Boolean = false,
    val targetIsDirectory: Boolean = false,
    val skipReason: SyncPlanSkipReason = SyncPlanSkipReason.Conflict,
)

internal fun planDirectorySync(
    source: SyncSourceSnapshot,
    target: SyncTargetSnapshot,
    task: SyncTask,
    targetSeparator: String = "/",
    targetRootPath: String = task.targetPath,
    recoverPendingWrite: Boolean = false,
    scopeRelativePath: (String) -> String = { it },
): List<SyncPlanItem> {
    val occupied = target.occupiedPaths.toMutableSet()
    val protected = source.protectedPaths + target.protectedPaths
    val protectedAncestors = syncParentPaths(protected)
    val files = source.files.filter { (task.includeSubdirectories || '/' !in scopeRelativePath(it.relativePath)) &&
        !syncPathProtected(it.relativePath, protected) && it.relativePath !in protectedAncestors }
    val duplicatePaths = files.groupingBy { it.relativePath }.eachCount().filterValues { it > 1 }.keys
    val fileItems = files.map { entry ->
        val preferred = joinPath(targetRootPath, entry.relativePath, targetSeparator)
        val existing = target.filesByAbsolute[preferred]
        val directoryExists = preferred in target.directoryPaths
        val exists = preferred in occupied
        val action = when {
            recoverPendingWrite -> SyncPlanAction.Copy
            entry.relativePath !in duplicatePaths && existing != null && !syncFileChanged(entry.file, existing) -> SyncPlanAction.Unchanged
            exists && task.conflictPolicy == SyncConflictPolicy.Skip -> SyncPlanAction.Skip
            exists && task.conflictPolicy == SyncConflictPolicy.Rename -> SyncPlanAction.RenameCopy
            else -> SyncPlanAction.Copy
        }
        val path = if (action == SyncPlanAction.RenameCopy) {
            buildSyncRenamePath(preferred, false, occupied, targetSeparator)
        } else preferred
        if (action == SyncPlanAction.Copy || action == SyncPlanAction.RenameCopy) occupied.add(path)
        SyncPlanItem(action, entry.relativePath, path, entry.file, exists && path == preferred, directoryExists && path == preferred)
    }
    val directories = linkedSetOf<String>()
    if (task.includeEmptyDirectories) {
        source.directories.filterTo(directories) { (task.includeSubdirectories || '/' !in scopeRelativePath(it)) &&
            !syncPathProtected(it, protected) }
    }
    fileItems.filter { it.action == SyncPlanAction.Copy || it.action == SyncPlanAction.RenameCopy }.forEach { item ->
        var relative = item.relativePath.substringBeforeLast('/', "")
        while (relative.isNotEmpty()) {
            directories.add(relative)
            relative = relative.substringBeforeLast('/', "")
        }
    }
    val directoryItems = directories.sortedWith(compareBy<String> { it.count { char -> char == '/' } }.thenBy { it }).mapNotNull { relative ->
        val path = joinPath(targetRootPath, relative, targetSeparator)
        if (path in target.directoryPaths) null
        else SyncPlanItem(SyncPlanAction.CreateDirectory, relative, path, targetExisted = path in target.occupiedPaths)
    }
    return directoryItems + fileItems
}
