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

private const val SYNC_TASK_STORE_DIR_NAME = "sync-tasks"
private const val SYNC_TASK_STORE_TASK_DIR_NAME = "tasks"
private const val SYNC_TASK_STORE_RUN_DIR_NAME = "runs"
private const val SYNC_TASK_STORE_META_FILE_NAME = "meta.pb64"
private const val SYNC_TASK_STORE_TASK_FILE_PREFIX = "task-"
private const val SYNC_TASK_STORE_TASK_FILE_SUFFIX = ".pb64"
private const val SYNC_TASK_STORE_RUN_FILE_SUFFIX = ".pb64l"
private const val SYNC_TASK_STORE_RUN_HISTORY_LIMIT = 30

interface SyncTaskStore {
    fun loadTasks(): List<SyncTask>

    fun saveTask(task: SyncTask): SyncTask

    fun deleteTask(taskId: Long)

    fun appendRun(record: SyncRunRecord)

    fun loadRuns(taskId: Long): List<SyncRunRecord>

    fun deleteRuns(taskId: Long)
}

@Serializable
private data class SyncTaskStoreMeta(
    val nextTaskId: Long = 1L,
)

class TempSyncTaskStore(
    private val baseDir: String = buildDefaultSyncTaskStoreDir(),
) : SyncTaskStore {
    private companion object {
        val operationLock = TaskRuntimeStoreLock()
    }

    override fun loadTasks(): List<SyncTask> = withOperationLock {
        val taskDir = buildTaskDirPath()
        if (!PathUtils.exists(FileAccessPermission.Allowed, taskDir)) return@withOperationLock emptyList()
        PathUtils.getFileAndFolder(FileAccessPermission.Allowed, taskDir)
            .getOrDefault(emptyList())
            .filter { file -> !file.isDirectory && file.name.startsWith(SYNC_TASK_STORE_TASK_FILE_PREFIX) }
            .mapNotNull { file -> loadTaskFile(file.path) }
            .sortedWith(compareByDescending<SyncTask> { task -> task.updatedAt }.thenByDescending { task -> task.id })
    }

    override fun saveTask(task: SyncTask): SyncTask = withOperationLock {
        val normalized = if (task.id > 0L) {
            ensureNextTaskIdAfter(task.id)
            task
        } else {
            task.copy(id = nextTaskId())
        }
        replaceTextFile(
            path = buildTaskFilePath(normalized.id),
            content = encodeProtoLine(normalized),
            label = AppStrings.ui_sync_tasks,
        )
        normalized
    }

    override fun deleteTask(taskId: Long) = withOperationLock {
        deleteFileIfExists(buildTaskFilePath(taskId), AppStrings.ui_sync_tasks)
        deleteRunsLocked(taskId)
    }

    override fun appendRun(record: SyncRunRecord) = withOperationLock {
        ensureParentDirectory(buildRunFilePath(record.taskId))
        FileUtils.createFile(FileAccessPermission.Allowed, buildRunFilePath(record.taskId)).onFailure { error ->
            LogKit.e(AppStrings.ui_failed_create_synchronization_running_record_taskid_arg0.format(arg0 = (record.taskId).toString()), error)
        }
        runCatching {
            FileUtils.appendToFile(
                FileAccessPermission.Allowed,
                buildRunFilePath(record.taskId),
                encodeProtoLine(record) + "\n",
            )
        }.onFailure { error ->
            LogKit.e(AppStrings.ui_failed_append_synchronization_running_record_taskid_arg0.format(arg0 = (record.taskId).toString()), error)
        }
        trimRunsLocked(record.taskId)
    }

    override fun loadRuns(taskId: Long): List<SyncRunRecord> = withOperationLock {
        loadRunRecordsLocked(taskId)
            .sortedByDescending { record -> record.startedAt }
            .take(SYNC_TASK_STORE_RUN_HISTORY_LIMIT)
    }

    override fun deleteRuns(taskId: Long) = withOperationLock {
        deleteRunsLocked(taskId)
    }

    private fun loadTaskFile(path: String): SyncTask? {
        return runCatching {
            val content = FileUtils.readFile(FileAccessPermission.Allowed, path)
                .getOrElse { failure -> throw failure }
                .decodeToString()
            decodeProtoLine<SyncTask>(content)
        }.onFailure { error ->
            LogKit.e(AppStrings.ui_failed_read_synchronization_task_file_path_arg0.format(arg0 = path), error)
        }.getOrNull()
    }

    private fun nextTaskId(): Long {
        val meta = loadMeta()
        val nextId = meta.nextTaskId.coerceAtLeast(1L)
        saveMeta(meta.copy(nextTaskId = nextId + 1L))
        return nextId
    }

    private fun ensureNextTaskIdAfter(taskId: Long) {
        val meta = loadMeta()
        if (meta.nextTaskId <= taskId) {
            saveMeta(meta.copy(nextTaskId = taskId + 1L))
        }
    }

    private fun loadMeta(): SyncTaskStoreMeta {
        val path = buildMetaFilePath()
        if (!PathUtils.exists(FileAccessPermission.Allowed, path)) return SyncTaskStoreMeta()
        return runCatching {
            val content = FileUtils.readFile(FileAccessPermission.Allowed, path)
                .getOrElse { failure -> throw failure }
                .decodeToString()
            decodeProtoLine<SyncTaskStoreMeta>(content)
        }.onFailure { error ->
            LogKit.e(AppStrings.ui_failed_read_synchronization_task_warehouse_metadata_path_arg0.format(arg0 = path), error)
        }.getOrDefault(SyncTaskStoreMeta())
    }

    private fun saveMeta(meta: SyncTaskStoreMeta) {
        replaceTextFile(
            path = buildMetaFilePath(),
            content = encodeProtoLine(meta),
            label = AppStrings.ui_synchronize_task_repository_metadata,
        )
    }

    private fun deleteRunsLocked(taskId: Long) {
        deleteFileIfExists(buildRunFilePath(taskId), AppStrings.ui_synchronize_running_records)
    }

    private fun loadRunRecordsLocked(taskId: Long): List<SyncRunRecord> {
        val path = buildRunFilePath(taskId)
        if (!PathUtils.exists(FileAccessPermission.Allowed, path)) return emptyList()
        return FileUtils.readFileLines(FileAccessPermission.Allowed, path)
            .mapNotNull { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty()) return@mapNotNull null
                runCatching { decodeProtoLine<SyncRunRecord>(trimmed) }
                    .onFailure { error -> LogKit.e(AppStrings.ui_failed_parse_synchronization_running_record_taskid_arg0.format(arg0 = (taskId).toString()), error) }
                    .getOrNull()
            }
    }

    private fun trimRunsLocked(taskId: Long) {
        val latestRecords = loadRunRecordsLocked(taskId)
            .sortedByDescending { record -> record.startedAt }
            .take(SYNC_TASK_STORE_RUN_HISTORY_LIMIT)
            .asReversed()
        val content = latestRecords.joinToString(separator = "\n") { record ->
            encodeProtoLine(record)
        }.let { text ->
            if (text.isEmpty()) text else "$text\n"
        }
        replaceTextFile(
            path = buildRunFilePath(taskId),
            content = content,
            label = AppStrings.ui_synchronize_running_records,
        )
    }

    private fun replaceTextFile(path: String, content: String, label: String) {
        ensureParentDirectory(path)
        val tempPath = "$path.tmp"
        runCatching {
            if (PathUtils.exists(FileAccessPermission.Allowed, tempPath)) {
                FileUtils.deleteFile(FileAccessPermission.Allowed, tempPath)
                    .getOrElse { failure -> throw failure }
            }
            FileUtils.createFile(FileAccessPermission.Allowed, tempPath)
                .getOrElse { failure -> throw failure }
            FileUtils.appendToFile(FileAccessPermission.Allowed, tempPath, content)
            if (PathUtils.exists(FileAccessPermission.Allowed, path)) {
                FileUtils.deleteFile(FileAccessPermission.Allowed, path)
                    .getOrElse { failure -> throw failure }
            }
            renameSiblingFile(tempPath, path).getOrElse { failure -> throw failure }
        }.onFailure { error ->
            LogKit.e(AppStrings.ui_replacement_arg0_failed_path_arg1.format(arg0 = label, arg1 = path), error)
        }
    }

    private fun deleteFileIfExists(path: String, label: String) {
        if (!PathUtils.exists(FileAccessPermission.Allowed, path)) return
        FileUtils.deleteFile(FileAccessPermission.Allowed, path).onFailure { error ->
            LogKit.e(AppStrings.ui_failed_delete_arg0_path_arg1.format(arg0 = label, arg1 = path), error)
        }
    }

    private fun renameSiblingFile(fromPath: String, toPath: String): Result<Boolean> {
        val separator = PathUtils.getPathSeparator()
        return FileUtils.rename(
            permission = FileAccessPermission.Allowed,
            path = buildParentPath(fromPath),
            oldName = fromPath.substringAfterLast(separator),
            newName = toPath.substringAfterLast(separator),
        )
    }

    private fun ensureParentDirectory(path: String) {
        PathUtils.createDirectoryIfNotExists(FileAccessPermission.Allowed, buildParentPath(path))
    }

    private fun buildTaskDirPath(): String {
        return joinPath(baseDir, SYNC_TASK_STORE_TASK_DIR_NAME)
    }

    private fun buildRunDirPath(): String {
        return joinPath(baseDir, SYNC_TASK_STORE_RUN_DIR_NAME)
    }

    private fun buildMetaFilePath(): String {
        return joinPath(baseDir, SYNC_TASK_STORE_META_FILE_NAME)
    }

    private fun buildTaskFilePath(taskId: Long): String {
        return joinPath(buildTaskDirPath(), "$SYNC_TASK_STORE_TASK_FILE_PREFIX$taskId$SYNC_TASK_STORE_TASK_FILE_SUFFIX")
    }

    private fun buildRunFilePath(taskId: Long): String {
        return joinPath(buildRunDirPath(), "$taskId$SYNC_TASK_STORE_RUN_FILE_SUFFIX")
    }

    private fun buildParentPath(path: String): String {
        val separator = PathUtils.getPathSeparator()
        val normalized = path.trimEnd('/', '\\')
        val index = normalized.lastIndexOf(separator)
        return if (index <= 0) separator else normalized.substring(0, index)
    }

    private inline fun <T> withOperationLock(block: () -> T): T {
        operationLock.lock()
        try {
            return block()
        } finally {
            operationLock.unlock()
        }
    }

    private inline fun <reified T> encodeProtoLine(value: T): String {
        return Base64.encode(ProtoBufCodec.encode(value))
    }

    private inline fun <reified T> decodeProtoLine(value: String): T {
        return ProtoBufCodec.decode(Base64.decode(value.trim()))
    }
}

private fun buildDefaultSyncTaskStoreDir(): String {
    return joinPath(PathUtils.getAppPath().trimEnd('/', '\\'), SYNC_TASK_STORE_DIR_NAME)
}

private fun joinPath(parent: String, child: String): String {
    val separator = PathUtils.getPathSeparator()
    val normalizedParent = parent.trimEnd('/', '\\')
    return if (normalizedParent.endsWith(separator)) {
        normalizedParent + child
    } else {
        normalizedParent + separator + child
    }
}
