package com.folderspan.ui.state.main

import strings.AppStrings

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FileCopy
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.folderspan.data.StatusEnum
import com.folderspan.data.file.FileProtocol
import com.folderspan.extensions.formatDuration
import com.folderspan.extensions.formatSpeed
import com.folderspan.localization.LocalizedMessage
import com.folderspan.localization.renderOrLegacy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import kotlin.random.Random
import kotlin.time.Clock

internal const val MAX_TASK_VALUE_ENTRIES = 100
internal const val MAX_TASK_RESULT_ENTRIES = 100
internal const val TASK_PATH_VALUE_KEY = "path"
internal const val TASK_PROGRESS_CUR_VALUE_KEY = "progressCur"
internal const val TASK_PROGRESS_MAX_VALUE_KEY = "progressMax"
internal const val TASK_OVERALL_PROGRESS_CUR_VALUE_KEY = "__overall_progress_cur"
internal const val TASK_OVERALL_PROGRESS_MAX_VALUE_KEY = "__overall_progress_max"
internal const val TASK_PROTOCOL_LABEL_VALUE_KEY = "protocolLabel"
internal const val TASK_ENDPOINT_VALUE_KEY = "endpoint"
internal const val TASK_SOURCE_PATH_VALUE_KEY = "sourcePath"
internal const val TASK_TARGET_PATH_VALUE_KEY = "targetPath"
internal const val TRANSIENT_RESULT_PATH_VALUE_KEY = "__transient_result_path"
internal const val TRANSIENT_RESULT_MESSAGE_VALUE_KEY = "__transient_result_message"
internal const val FAILURE_COUNT_VALUE_KEY = "__failure_count"
internal const val FAILURE_FIRST_PATH_VALUE_KEY = "__failure_first_path"
internal const val FAILURE_FIRST_MESSAGE_VALUE_KEY = "__failure_first_message"
internal const val TASK_RUNTIME_PHASE_VALUE_KEY = "__runtime_phase"
internal const val TASK_RUNTIME_STAGE_VALUE_KEY = "__runtime_stage"
internal const val TASK_RUNTIME_QUEUE_CATEGORY_VALUE_KEY = "__runtime_queue_category"
internal const val TASK_RUNTIME_QUEUE_FILE_VALUE_KEY = "__runtime_queue_file"
internal const val TASK_RUNTIME_REMAINING_VALUE_KEY = "__runtime_remaining"
internal const val TASK_RUNTIME_RESUMED_VALUE_KEY = "__runtime_resumed"
internal const val TASK_RUNTIME_METRIC_KIND_VALUE_KEY = "__runtime_metric_kind"
internal const val TASK_RUNTIME_METRIC_COMPLETED_VALUE_KEY = "__runtime_metric_completed"
internal const val TASK_RUNTIME_METRIC_TOTAL_VALUE_KEY = "__runtime_metric_total"
internal const val TASK_RUNTIME_METRIC_SPEED_VALUE_KEY = "__runtime_metric_speed"
internal const val TASK_RUNTIME_METRIC_ETA_MS_VALUE_KEY = "__runtime_metric_eta_ms"
internal const val TASK_RUNTIME_METRIC_STARTED_AT_VALUE_KEY = "__runtime_metric_started_at"
internal const val TASK_RUNTIME_METRIC_UPDATED_AT_VALUE_KEY = "__runtime_metric_updated_at"
internal const val TASK_RUNTIME_PARALLEL_COUNT_VALUE_KEY = "__runtime_parallel_count"
internal const val TASK_RUNTIME_METRIC_KIND_BYTES = "bytes"
internal const val TASK_RUNTIME_METRIC_KIND_ITEMS = "items"
internal val TASK_PROGRESS_PERCENT_REGEX = Regex(AppStrings.legacy_task_progress_percent_pattern)
internal val COMPLETED_RUNTIME_VALUE_KEYS = setOf(
    TASK_PROGRESS_CUR_VALUE_KEY,
    TASK_PROGRESS_MAX_VALUE_KEY,
    TASK_OVERALL_PROGRESS_CUR_VALUE_KEY,
    TASK_OVERALL_PROGRESS_MAX_VALUE_KEY,
    TASK_PROTOCOL_LABEL_VALUE_KEY,
    TASK_ENDPOINT_VALUE_KEY,
    TASK_SOURCE_PATH_VALUE_KEY,
    TASK_TARGET_PATH_VALUE_KEY,
    TRANSIENT_RESULT_PATH_VALUE_KEY,
    TRANSIENT_RESULT_MESSAGE_VALUE_KEY,
    TASK_RUNTIME_PHASE_VALUE_KEY,
    TASK_RUNTIME_STAGE_VALUE_KEY,
    TASK_RUNTIME_QUEUE_CATEGORY_VALUE_KEY,
    TASK_RUNTIME_QUEUE_FILE_VALUE_KEY,
    TASK_RUNTIME_REMAINING_VALUE_KEY,
    TASK_RUNTIME_RESUMED_VALUE_KEY,
    TASK_RUNTIME_METRIC_KIND_VALUE_KEY,
    TASK_RUNTIME_METRIC_COMPLETED_VALUE_KEY,
    TASK_RUNTIME_METRIC_TOTAL_VALUE_KEY,
    TASK_RUNTIME_METRIC_SPEED_VALUE_KEY,
    TASK_RUNTIME_METRIC_ETA_MS_VALUE_KEY,
    TASK_RUNTIME_METRIC_STARTED_AT_VALUE_KEY,
    TASK_RUNTIME_METRIC_UPDATED_AT_VALUE_KEY,
    TASK_RUNTIME_PARALLEL_COUNT_VALUE_KEY,
)

internal val orderedRuntimeStages = listOf(
    TaskRuntimeStage.COPY,
    TaskRuntimeStage.DELETE_SOURCE,
    TaskRuntimeStage.DELETE,
)


enum class TaskType {
    Copy,
    Move,
    Delete,
    Download,
}


@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class Task(
    @ProtoNumber(1) val taskType: TaskType,
    @ProtoNumber(2) val key: Long = Clock.System.now().toEpochMilliseconds() + Random.nextInt(),
    @ProtoNumber(3) val status: StatusEnum,
    // 任务参数
    @ProtoNumber(4) val values: Map<String, String> = emptyMap(),
    // 任务结果
    @ProtoNumber(5) val result: Map<String, String> = emptyMap(),
    @ProtoNumber(6) val protocol: FileProtocol = FileProtocol.Local,
    @ProtoNumber(7) val protocolId: String = "",
    @ProtoNumber(8) val retryEntries: List<TaskRetryEntry> = emptyList(),
    @ProtoNumber(9) val activeResults: Map<String, String> = emptyMap(),
    @ProtoNumber(10) val resultMessages: Map<String, LocalizedMessage> = emptyMap(),
    @ProtoNumber(11) val activeResultMessages: Map<String, LocalizedMessage> = emptyMap(),
    @ProtoNumber(12) val localizedMessage: LocalizedMessage? = null,
) {
    private fun activeRuntimePhaseText(): String? {
        return when (values[TASK_RUNTIME_PHASE_VALUE_KEY]) {
            "scan" -> AppStrings.ui_traverse
            "execute" -> AppStrings.ui_execute
            else -> null
        }
    }

    fun runtimePhaseText(): String? = activeRuntimePhaseText()

    fun runtimeMetricSpeedText(): String? {
        val speed = values[TASK_RUNTIME_METRIC_SPEED_VALUE_KEY]?.toDoubleOrNull()
            ?.takeIf { item -> item >= 0.0 }
            ?: return null
        return when (values[TASK_RUNTIME_METRIC_KIND_VALUE_KEY]) {
            TASK_RUNTIME_METRIC_KIND_BYTES -> speed.formatSpeed()
            TASK_RUNTIME_METRIC_KIND_ITEMS -> speed.formatItemsPerSecond()
            else -> null
        }
    }

    fun runtimeMetricRemainingText(): String? {
        return values[TASK_RUNTIME_METRIC_ETA_MS_VALUE_KEY]
            ?.toLongOrNull()
            ?.takeIf { item -> item >= 0L }
            ?.formatDuration()
    }

    fun activeParallelCount(): Int {
        val explicitParallelCount = values[TASK_RUNTIME_PARALLEL_COUNT_VALUE_KEY]
            ?.toIntOrNull()
            ?.coerceAtLeast(0)
        explicitParallelCount?.let { count -> return count }
        return activeResults.count { (_, value) ->
            value.isNotBlank() && !value.isTaskLevelRuntimeStatusMessage()
        }
    }

    @Composable
    fun ToTitle(style: TextStyle = TextStyle.Default) {
        if (status == StatusEnum.SUCCESS) {
            if (failureCount() > 0) {
                return when (taskType) {
                    TaskType.Copy -> Text(AppStrings.ui_copy_completed_partial_failure, style = style)
                    TaskType.Move -> Text(AppStrings.ui_move_completed_partially_failed, style = style)
                    TaskType.Delete -> Text(AppStrings.ui_deletion_completed_partial_failure, style = style)
                    TaskType.Download -> Text(AppStrings.ui_download_failed, style = style)
                }
            }
            return when (taskType) {
                TaskType.Copy -> Text(AppStrings.ui_copied_successfully, style = style)
                TaskType.Move -> Text(AppStrings.ui_moved_successfully, style = style)
                TaskType.Delete -> Text(AppStrings.ui_delete_successfully, style = style)
                TaskType.Download -> Text(AppStrings.ui_download_completed, style = style)
            }
        }
        if (status == StatusEnum.PAUSE) {
            return when (taskType) {
                TaskType.Copy -> Text(AppStrings.ui_replication_paused, style = style)
                TaskType.Move -> Text(AppStrings.ui_mobile_hold, style = style)
                TaskType.Delete -> Text(AppStrings.ui_delete_pending, style = style)
                TaskType.Download -> Text(AppStrings.ui_download_paused, style = style)
            }
        }
        if (status == StatusEnum.FAILURE) {
            return when (taskType) {
                TaskType.Copy -> Text(AppStrings.ui_copy_failed, style = style)
                TaskType.Move -> Text(AppStrings.ui_move_failed, style = style)
                TaskType.Delete -> Text(AppStrings.ui_delete_failed, style = style)
                TaskType.Download -> Text(AppStrings.ui_download_failed, style = style)
            }
        }

        return when (taskType) {
            TaskType.Copy -> Text(AppStrings.ui_copying, style = style)
            TaskType.Move -> Text(AppStrings.task_status_moving, style = style)
            TaskType.Delete -> Text(AppStrings.ui_deleting, style = style)
            TaskType.Download -> Text(AppStrings.ui_clipboard_url_download_downloading, style = style)
        }
    }

    @Composable
    fun ToIcon() {
        when (taskType) {
            TaskType.Copy -> Icon(Icons.Outlined.FileCopy, null)
            TaskType.Move -> Icon(Icons.Default.ContentCut, null)
            TaskType.Delete -> Icon(Icons.Default.Delete, null)
            TaskType.Download -> Icon(Icons.Default.Download, null)
        }
    }

    @Composable
    fun ToStatusIcon(tint: Color? = null) {
        when (status) {
            StatusEnum.SUCCESS -> Icon(
                Icons.Default.CheckCircle,
                null,
                tint = tint ?: MaterialTheme.colorScheme.primary
            )

            StatusEnum.FAILURE -> Icon(
                Icons.Outlined.ErrorOutline,
                null,
                tint = tint ?: MaterialTheme.colorScheme.error
            )

            StatusEnum.PAUSE -> Icon(
                Icons.Default.PauseCircle,
                null,
                tint = tint ?: MaterialTheme.colorScheme.secondary
            )

            StatusEnum.LOADING -> CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                color = tint ?: MaterialTheme.colorScheme.primary,
                strokeWidth = 3.dp
            )
        }
    }

    fun withStatus(newStatus: StatusEnum): Task =
        if (status == newStatus) this else copy(status = newStatus)

    /**
     * 写入单个临时 value；若超过 `MAX_TASK_VALUE_ENTRIES` 会移除最早插入的条目。
     *
     * @param key 临时值键
     * @param value 临时值内容
     * @return 更新后的任务实体
     */
    fun withValue(key: String, value: String): Task {
        return withValues(listOf(key to value))
    }

    fun withValues(entries: Iterable<Pair<String, String>>): Task {
        val updates = entries.toList()
        if (updates.isEmpty()) return this
        if (updates.all { (key, value) -> values[key] == value }) return this
        val newValues = LinkedHashMap(values)
        var changed = false
        updates.forEach { (key, value) ->
            if (newValues[key] == value) return@forEach
            changed = true
            newValues.remove(key)
            if (newValues.size >= MAX_TASK_VALUE_ENTRIES) {
                val iterator = newValues.entries.iterator()
                if (iterator.hasNext()) {
                    iterator.next()
                    iterator.remove()
                }
            }
            newValues[key] = value
        }
        return if (changed) copy(values = newValues) else this
    }

    fun withoutValues(keys: Collection<String>): Task {
        if (keys.isEmpty()) return this
        if (keys.none { key -> values.containsKey(key) }) return this
        val newValues = values.toMutableMap()
        keys.forEach { key -> newValues.remove(key) }
        return copy(values = newValues)
    }

    fun clearCompletedRuntimeState(): Task {
        val retainedPath = values[TASK_PATH_VALUE_KEY]
        val retainedValues = buildMap {
            retainedPath?.let { path -> put(TASK_PATH_VALUE_KEY, path) }
            this@Task.values.forEach { (key, value) ->
                // Network/UI diagnostics are only relevant while the task is active.
                if (key !in COMPLETED_RUNTIME_VALUE_KEYS && key != TASK_PATH_VALUE_KEY) {
                    put(key, value)
                }
            }
        }
        return when {
            result.isEmpty() &&
                retryEntries.isEmpty() &&
                activeResults.isEmpty() &&
                resultMessages.isEmpty() &&
                activeResultMessages.isEmpty() &&
                values == retainedValues -> this
            else -> copy(
                values = retainedValues,
                result = emptyMap(),
                retryEntries = emptyList(),
                activeResults = emptyMap(),
                resultMessages = emptyMap(),
                activeResultMessages = emptyMap(),
                localizedMessage = null,
            )
        }
    }

    fun withoutValue(key: String): Task {
        return withoutValues(listOf(key))
    }

    fun withTransientResult(path: String, message: String): Task {
        val previousTransientPath = transientResultPath()
        val withoutStaleTaskLevelResult = if (
            previousTransientPath != null &&
            previousTransientPath.isBlank() &&
            previousTransientPath != path
        ) {
            withoutActiveResult(previousTransientPath)
        } else {
            this
        }
        return withoutStaleTaskLevelResult.withValues(
            listOf(
                TRANSIENT_RESULT_PATH_VALUE_KEY to path,
                TRANSIENT_RESULT_MESSAGE_VALUE_KEY to message,
            )
        ).withActiveResult(path, message)
    }

    fun withoutTransientResult(path: String? = null): Task {
        if (!values.containsKey(TRANSIENT_RESULT_MESSAGE_VALUE_KEY)) return this
        val currentPath = values[TRANSIENT_RESULT_PATH_VALUE_KEY]
        if (path != null && currentPath != path) return this
        return withoutValues(
            listOf(
                TRANSIENT_RESULT_PATH_VALUE_KEY,
                TRANSIENT_RESULT_MESSAGE_VALUE_KEY,
            )
        )
    }

    fun transientResultPath(): String? = values[TRANSIENT_RESULT_PATH_VALUE_KEY]

    fun transientResultMessage(): String? = values[TRANSIENT_RESULT_MESSAGE_VALUE_KEY]

    fun currentProgressCur(): Int {
        return values[TASK_OVERALL_PROGRESS_CUR_VALUE_KEY]
            ?.toIntOrNull()
            ?: values[TASK_PROGRESS_CUR_VALUE_KEY]?.toIntOrNull()
            ?: 0
    }

    fun currentProgressMax(): Int {
        return values[TASK_OVERALL_PROGRESS_MAX_VALUE_KEY]
            ?.toIntOrNull()
            ?: values[TASK_PROGRESS_MAX_VALUE_KEY]?.toIntOrNull()
            ?: 0
    }

    fun failureCount(): Int {
        values[FAILURE_COUNT_VALUE_KEY]?.toIntOrNull()?.let { stored ->
            return stored.coerceAtLeast(0)
        }
        return failedRetryEntries().size
    }

    fun firstFailurePath(): String? {
        return values[FAILURE_FIRST_PATH_VALUE_KEY]
            ?.takeIf { item -> item.isNotBlank() }
            ?: failedRetryEntries().firstOrNull()?.resultPath
    }

    fun firstFailureMessage(): String? {
        return values[FAILURE_FIRST_MESSAGE_VALUE_KEY]
            ?.takeIf { item -> item.isNotBlank() }
            ?: failedRetryEntries().firstOrNull()?.displayFailureMessage()?.takeIf { item -> item.isNotBlank() }
    }

    fun withFailureSummary(failureCount: Int, firstFailurePath: String?, firstFailureMessage: String?): Task {
        val normalizedCount = failureCount.coerceAtLeast(0)
        var updated = this
        if (normalizedCount <= 0) {
            if (updated.values.containsKey(FAILURE_COUNT_VALUE_KEY)) {
                updated = updated.withoutValue(FAILURE_COUNT_VALUE_KEY)
            }
            if (updated.values.containsKey(FAILURE_FIRST_PATH_VALUE_KEY)) {
                updated = updated.withoutValue(FAILURE_FIRST_PATH_VALUE_KEY)
            }
            if (updated.values.containsKey(FAILURE_FIRST_MESSAGE_VALUE_KEY)) {
                updated = updated.withoutValue(FAILURE_FIRST_MESSAGE_VALUE_KEY)
            }
            return updated
        }

        val normalizedPath = firstFailurePath?.takeIf { item -> item.isNotBlank() }.orEmpty()
        val normalizedMessage = firstFailureMessage?.takeIf { item -> item.isNotBlank() }.orEmpty()
        updated = updated.withValue(FAILURE_COUNT_VALUE_KEY, normalizedCount.toString())
        if (normalizedPath.isNotEmpty()) {
            updated = updated.withValue(FAILURE_FIRST_PATH_VALUE_KEY, normalizedPath)
        } else if (updated.values.containsKey(FAILURE_FIRST_PATH_VALUE_KEY)) {
            updated = updated.withoutValue(FAILURE_FIRST_PATH_VALUE_KEY)
        }
        if (normalizedMessage.isNotEmpty()) {
            updated = updated.withValue(FAILURE_FIRST_MESSAGE_VALUE_KEY, normalizedMessage)
        } else if (updated.values.containsKey(FAILURE_FIRST_MESSAGE_VALUE_KEY)) {
            updated = updated.withoutValue(FAILURE_FIRST_MESSAGE_VALUE_KEY)
        }
        return updated
    }

    fun withFailureSummary(entries: List<TaskRetryEntry>): Task {
        val first = entries.firstOrNull()
        return withFailureSummary(
            failureCount = entries.size,
            firstFailurePath = first?.resultPath,
            firstFailureMessage = first?.displayFailureMessage(),
        )
    }

    fun appendFailureSummary(path: String, message: String): Task {
        if (path.isBlank() && message.isBlank()) return this
        val currentCount = failureCount()
        val firstPath = firstFailurePath()
        val firstMessage = firstFailureMessage()
        return withFailureSummary(
            failureCount = currentCount + 1,
            firstFailurePath = firstPath ?: path,
            firstFailureMessage = firstMessage ?: message,
        )
    }

    /**
     * 写入单个结果条目；若超过 `MAX_TASK_RESULT_ENTRIES` 会移除最早插入的条目。
     *
     * @param key 结果键
     * @param value 结果内容
     * @return 更新后的任务实体
     */
    fun withResult(key: String, value: String): Task {
        val current = result[key]
        if (current == value) return this
        val newResult = LinkedHashMap(result).apply { remove(key) }
        if (newResult.size >= MAX_TASK_RESULT_ENTRIES) {
            val iterator = newResult.entries.iterator()
            if (iterator.hasNext()) {
                iterator.next()
                iterator.remove()
            }
        }
        newResult[key] = value
        val newLocalizedResults = LinkedHashMap(resultMessages).apply { remove(key) }
        LocalizedMessage.fromLegacy(value)?.let { message ->
            newLocalizedResults[key] = message
        }
        return copy(
            result = newResult,
            resultMessages = newLocalizedResults,
            localizedMessage = LocalizedMessage.fromLegacy(value),
        )
    }

    fun withActiveResult(key: String, value: String): Task {
        val normalizedValue = value.trim()
        if (normalizedValue.isEmpty()) {
            return withoutActiveResult(key)
        }
        val current = activeResults[key]
        if (current == value) return this
        val newResult = LinkedHashMap(activeResults).apply { remove(key) }
        if (!newResult.containsKey(key) && newResult.size >= MAX_TASK_RESULT_ENTRIES) {
            val iterator = newResult.entries.iterator()
            if (iterator.hasNext()) {
                iterator.next()
                iterator.remove()
            }
        }
        newResult[key] = value
        val newLocalizedResults = LinkedHashMap(activeResultMessages).apply { remove(key) }
        LocalizedMessage.fromLegacy(value)?.let { message ->
            newLocalizedResults[key] = message
        }
        return copy(
            activeResults = newResult,
            activeResultMessages = newLocalizedResults,
            localizedMessage = LocalizedMessage.fromLegacy(value),
        )
    }

    fun withoutActiveResult(key: String): Task {
        if (!activeResults.containsKey(key)) return this
        val newResult = activeResults.toMutableMap().apply { remove(key) }
        val newLocalizedResults = activeResultMessages.toMutableMap().apply { remove(key) }
        return copy(
            activeResults = newResult,
            activeResultMessages = newLocalizedResults,
        )
    }

    fun withoutResult(key: String): Task {
        if (!result.containsKey(key)) return this
        val newResult = result.toMutableMap().apply { remove(key) }
        val newLocalizedResults = resultMessages.toMutableMap().apply { remove(key) }
        return copy(
            result = newResult,
            resultMessages = newLocalizedResults,
        )
    }

    fun clearActiveResults(): Task =
        if (activeResults.isEmpty() && activeResultMessages.isEmpty()) {
            this
        } else {
            copy(
                activeResults = emptyMap(),
                activeResultMessages = emptyMap(),
            )
        }

    fun resultMessage(key: String, legacy: String): String =
        resultMessages[key].renderOrLegacy(legacy)

    fun activeResultMessage(key: String, displayText: String): String =
        activeResultMessages[key]
            ?.render()
            ?.takeIf { message -> message.isNotBlank() }
            ?: displayText

    fun displayMessage(legacy: String): String = localizedMessage.renderOrLegacy(legacy)

    fun clearTransientResult(): Task {
        return withoutValues(
            listOf(
                TRANSIENT_RESULT_PATH_VALUE_KEY,
                TRANSIENT_RESULT_MESSAGE_VALUE_KEY,
            )
        )
    }

    fun withRetryEntries(entries: List<TaskRetryEntry>): Task {
        if (retryEntries == entries) return this
        return copy(retryEntries = entries)
    }

    fun failedRetryEntries(): List<TaskRetryEntry> {
        return retryEntries.filter { item -> item.state == TaskRetryState.FAILURE }
    }
}
