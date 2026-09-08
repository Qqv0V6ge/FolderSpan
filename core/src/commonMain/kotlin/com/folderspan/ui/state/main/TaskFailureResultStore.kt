package com.folderspan.ui.state.main

import strings.AppStrings

import com.folderspan.utils.FileAccessPermission
import com.folderspan.utils.FileUtils
import com.folderspan.utils.LogKit
import com.folderspan.utils.PathUtils
import com.folderspan.utils.ProtoBufCodec
import kotlinx.serialization.Serializable
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

private const val TASK_FAILURE_RESULTS_DIR_NAME = "task-failure-results"
private const val TASK_FAILURE_RESULTS_FILE_SUFFIX = ".pb64l"
private const val TASK_FAILURE_RESULTS_VERSION = 1

@Serializable
enum class TaskFailureLifecycleState {
    FAILED,
    RESOLVED,
}

@Serializable
data class TaskFailurePersistedRecord(
    val version: Int = TASK_FAILURE_RESULTS_VERSION,
    val updatedAt: Long,
    val lifecycleState: TaskFailureLifecycleState,
    val entry: TaskRetryEntry,
    val displayMessage: String = "",
)

data class TaskFailureLoadedResult(
    val entries: List<TaskRetryEntry> = emptyList(),
    val fileAvailable: Boolean = true,
)

interface TaskFailureResultStore {
    fun recordFailure(taskKey: Long, entry: TaskRetryEntry, message: String)

    fun recordResolved(taskKey: Long, entry: TaskRetryEntry)

    fun loadFailures(taskKey: Long): TaskFailureLoadedResult

    fun replaceFailures(taskKey: Long, entries: List<TaskRetryEntry>)

    fun deleteTask(taskKey: Long)
}

class TempTaskFailureResultStore : TaskFailureResultStore {
    private companion object {
        val operationLock = TaskRuntimeStoreLock()
    }

    override fun recordFailure(taskKey: Long, entry: TaskRetryEntry, message: String) = withOperationLock {
        appendRecord(
            taskKey = taskKey,
            record = TaskFailurePersistedRecord(
                updatedAt = Clock.System.now().toEpochMilliseconds(),
                lifecycleState = TaskFailureLifecycleState.FAILED,
                entry = entry.withState(TaskRetryState.FAILURE).withFailureMessage(""),
                displayMessage = message,
            )
        )
    }

    override fun recordResolved(taskKey: Long, entry: TaskRetryEntry) = withOperationLock {
        appendRecord(
            taskKey = taskKey,
            record = TaskFailurePersistedRecord(
                updatedAt = Clock.System.now().toEpochMilliseconds(),
                lifecycleState = TaskFailureLifecycleState.RESOLVED,
                entry = entry.withState(TaskRetryState.SUCCESS).withFailureMessage(""),
                displayMessage = "",
            )
        )
    }

    override fun loadFailures(taskKey: Long): TaskFailureLoadedResult = withOperationLock {
        val filePath = buildTaskFailureFilePath(taskKey)
        if (!PathUtils.exists(FileAccessPermission.Allowed, filePath)) {
            return@withOperationLock TaskFailureLoadedResult(entries = emptyList(), fileAvailable = false)
        }

        val latestByEntryKey = LinkedHashMap<String, TaskFailurePersistedRecord>()
        runCatching {
            FileUtils.readFileLines(FileAccessPermission.Allowed, filePath).forEach { rawLine ->
                val line = rawLine.trim()
                if (line.isEmpty()) return@forEach
                val record = decodeProtoLine<TaskFailurePersistedRecord>(line)
                latestByEntryKey[record.entry.entryKey] = record
            }
            TaskFailureLoadedResult(
                entries = latestByEntryKey.values
                    .mapNotNull { record -> record.toActiveEntryOrNull() },
                fileAvailable = true,
            )
        }.getOrElse { error ->
            LogKit.e(AppStrings.ui_failed_read_task_failure_item_file_taskkey_arg0_path.format(arg0 = (taskKey).toString(), arg1 = filePath), error)
            TaskFailureLoadedResult(entries = emptyList(), fileAvailable = false)
        }
    }

    override fun replaceFailures(taskKey: Long, entries: List<TaskRetryEntry>) = withOperationLock {
        deleteTask(taskKey)
        if (entries.isEmpty()) return@withOperationLock
        entries.forEach { entry ->
            recordFailure(taskKey, entry, entry.failureMessage)
        }
    }

    override fun deleteTask(taskKey: Long) = withOperationLock {
        val filePath = buildTaskFailureFilePath(taskKey)
        if (!PathUtils.exists(FileAccessPermission.Allowed, filePath)) return@withOperationLock
        FileUtils.deleteFile(FileAccessPermission.Allowed, filePath).onFailure { error ->
            LogKit.e(AppStrings.ui_failed_delete_task_failure_item_file_taskkey_arg0_path.format(arg0 = (taskKey).toString(), arg1 = filePath), error)
        }
    }

    private fun appendRecord(taskKey: Long, record: TaskFailurePersistedRecord) {
        ensureBaseDirectory()
        val filePath = buildTaskFailureFilePath(taskKey)
        FileUtils.createFile(FileAccessPermission.Allowed, filePath).onFailure { error ->
            LogKit.e(AppStrings.ui_failed_create_task_failure_item_file_taskkey_arg0_path.format(arg0 = (taskKey).toString(), arg1 = filePath), error)
        }
        runCatching {
            FileUtils.appendToFile(
                FileAccessPermission.Allowed,
                filePath,
                encodeProtoLine(record) + "\n"
            )
        }.onFailure { error ->
            LogKit.e(AppStrings.ui_failed_append_task_failure_item_file_taskkey_arg0_path.format(arg0 = (taskKey).toString(), arg1 = filePath), error)
        }
    }

    private fun ensureBaseDirectory() {
        PathUtils.createDirectoryIfNotExists(FileAccessPermission.Allowed, buildTaskFailureBaseDir())
    }

    private fun buildTaskFailureBaseDir(): String {
        val separator = PathUtils.getPathSeparator()
        val cachePath = PathUtils.getCachePath().trimEnd('/', '\\')
        return if (cachePath.endsWith(separator)) {
            cachePath + TASK_FAILURE_RESULTS_DIR_NAME
        } else {
            cachePath + separator + TASK_FAILURE_RESULTS_DIR_NAME
        }
    }

    private fun buildTaskFailureFilePath(taskKey: Long): String {
        val separator = PathUtils.getPathSeparator()
        return buildTaskFailureBaseDir() + separator + taskKey + TASK_FAILURE_RESULTS_FILE_SUFFIX
    }

    private inline fun <reified T> encodeProtoLine(value: T): String {
        return Base64.encode(ProtoBufCodec.encode(value))
    }

    private inline fun <reified T> decodeProtoLine(value: String): T {
        return ProtoBufCodec.decode(Base64.decode(value.trim()))
    }

    private inline fun <T> withOperationLock(block: () -> T): T {
        operationLock.lock()
        try {
            return block()
        } finally {
            operationLock.unlock()
        }
    }

    private fun TaskFailurePersistedRecord.toActiveEntryOrNull(): TaskRetryEntry? {
        if (lifecycleState != TaskFailureLifecycleState.FAILED) return null
        return entry
            .withState(TaskRetryState.FAILURE)
            .withFailureMessage(displayMessage)
    }
}
