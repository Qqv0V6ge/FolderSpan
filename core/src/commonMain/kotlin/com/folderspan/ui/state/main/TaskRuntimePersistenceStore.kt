package com.folderspan.ui.state.main

import com.folderspan.utils.FileAccessPermission
import strings.AppStrings

import com.folderspan.data.file.FileProtocol
import com.folderspan.utils.FileUtils
import com.folderspan.utils.LogKit
import com.folderspan.utils.PathUtils
import com.folderspan.utils.ProtoBufCodec
import kotlinx.serialization.Serializable
import kotlin.io.encoding.Base64
import kotlin.time.Clock

private const val TASK_RUNTIME_BASE_DIR_NAME = "task-runtime"
private const val TASK_RUNTIME_META_FILE_NAME = "meta.pb64"
private const val TASK_RUNTIME_RUN_FILE_NAME = "run.pb64"
private const val TASK_RUNTIME_TASK_FILE_NAME = "task.pb64"
private const val TASK_RUNTIME_PENDING_DIR_NAME = "pending"
private const val TASK_RUNTIME_TRANSFER_DIR_NAME = "transfer"
private const val TASK_RUNTIME_QUEUE_FILE_SUFFIX = ".pb64l"
private const val TASK_RUNTIME_TRANSFER_FILE_SUFFIX = ".pb64"
private const val TASK_RUNTIME_QUEUE_CHUNK_SIZE = 256
private const val TASK_RUNTIME_SCHEMA_VERSION = 3

@Serializable
enum class TaskRuntimePhase {
    SCANNING,
    EXECUTING,
}

@Serializable
enum class TaskRuntimeStage {
    COPY,
    DELETE_SOURCE,
    DELETE,
}

@Serializable
enum class TaskRuntimeQueueCategory {
    DIRECTORIES,
    FILES,
    EMPTY_FILES,
}

@Serializable
enum class TaskRuntimeEntryKind {
    DIRECTORY_CREATE,
    FILE_COPY,
    SOURCE_DELETE,
    TARGET_DELETE,
    EMPTY_FILE_CREATE,
}

@Serializable
data class TaskRuntimeEndpointRef(
    val protocol: FileProtocol = FileProtocol.Local,
    val protocolId: String = "",
    val path: String = "",
)

@Serializable
data class TaskRuntimeMeta(
    val schemaVersion: Int = TASK_RUNTIME_SCHEMA_VERSION,
    val taskKey: Long,
    val taskType: TaskType,
    val source: TaskRuntimeEndpointRef = TaskRuntimeEndpointRef(),
    val target: TaskRuntimeEndpointRef? = null,
    val currentPhase: TaskRuntimePhase = TaskRuntimePhase.SCANNING,
    val scanCompleted: Boolean = false,
    val resumedFromRecovery: Boolean = false,
    val totalEntries: Int = 0,
    val remainingEntries: Int = 0,
    val lastError: String = "",
    val createdAt: Long,
    val updatedAt: Long = createdAt,
)

@Serializable
data class TaskRuntimeRunState(
    val taskKey: Long,
    val runId: String,
    val currentPhase: TaskRuntimePhase = TaskRuntimePhase.EXECUTING,
    val currentStage: TaskRuntimeStage? = null,
    val currentQueueCategory: TaskRuntimeQueueCategory? = null,
    val currentQueueFile: String = "",
    val currentEntryPath: String = "",
    val lastStartedAt: Long = 0L,
    val lastAckedAt: Long = 0L,
)

@Serializable
data class TaskRuntimeQueueEntry(
    val entryId: String,
    val stage: TaskRuntimeStage,
    val kind: TaskRuntimeEntryKind,
    val src: TaskRuntimeEndpointRef = TaskRuntimeEndpointRef(),
    val dest: TaskRuntimeEndpointRef = TaskRuntimeEndpointRef(),
    val isDirectory: Boolean = false,
    val size: Long = 0L,
    val order: Int = 0,
)

data class TaskRuntimeQueuedEntry(
    val fileName: String,
    val entry: TaskRuntimeQueueEntry,
)

@Serializable
data class TaskRuntimeTransferCheckpoint(
    val schemaVersion: Int = TASK_RUNTIME_SCHEMA_VERSION,
    val taskKey: Long,
    val entryId: String,
    val destPath: String,
    val fileSize: Long,
    val chunkSize: Int,
    val completedChunks: Int = 0,
    val createdAt: Long,
    val updatedAt: Long = createdAt,
)

interface TaskRuntimePersistenceStore {
    fun loadTaskSnapshots(): List<Task>

    fun loadTaskSnapshot(taskKey: Long): Task?

    fun saveTaskSnapshot(task: Task)

    fun deleteTaskSnapshot(taskKey: Long)

    fun loadMeta(taskKey: Long): TaskRuntimeMeta?

    fun saveMeta(meta: TaskRuntimeMeta)

    fun loadRunState(taskKey: Long): TaskRuntimeRunState?

    fun saveRunState(runState: TaskRuntimeRunState)

    fun deleteRunState(taskKey: Long)

    fun appendQueueEntries(
        taskKey: Long,
        stage: TaskRuntimeStage,
        category: TaskRuntimeQueueCategory,
        entries: List<TaskRuntimeQueueEntry>,
    )

    fun peekNextQueueEntry(
        taskKey: Long,
        stage: TaskRuntimeStage,
        category: TaskRuntimeQueueCategory,
        preferredFile: String? = null,
    ): TaskRuntimeQueuedEntry?

    /**
     * 确认一个运行队列条目已处理。
     *
     * entryId 为空时保持旧语义：删除指定分片的队头；entryId 非空时删除匹配条目，
     * 供并发执行的 COPY/FILES 队列按实际完成顺序安全 ack。
     */
    fun ackQueueEntry(
        taskKey: Long,
        stage: TaskRuntimeStage,
        category: TaskRuntimeQueueCategory,
        fileName: String,
        entryId: String? = null,
    )

    fun listPendingQueueFiles(
        taskKey: Long,
        stage: TaskRuntimeStage,
        category: TaskRuntimeQueueCategory,
    ): List<String>

    fun loadPendingQueueEntries(
        taskKey: Long,
        stage: TaskRuntimeStage,
        category: TaskRuntimeQueueCategory,
        limit: Int = Int.MAX_VALUE,
    ): List<TaskRuntimeQueuedEntry>

    fun countPendingEntries(
        taskKey: Long,
        stage: TaskRuntimeStage? = null,
        category: TaskRuntimeQueueCategory? = null,
    ): Int

    fun hasPendingEntries(taskKey: Long): Boolean

    fun loadTransferCheckpoint(taskKey: Long, entryId: String): TaskRuntimeTransferCheckpoint?

    fun saveTransferCheckpoint(checkpoint: TaskRuntimeTransferCheckpoint)

    fun deleteTransferCheckpoint(taskKey: Long, entryId: String)

    fun clearTaskRuntime(taskKey: Long)
}

internal expect class TaskRuntimeStoreLock() {
    fun lock()

    fun unlock()
}

class TempTaskRuntimePersistenceStore : TaskRuntimePersistenceStore {
    private val queueLineCache = mutableMapOf<RuntimeQueueFileKey, List<String>>()

    private data class RuntimeQueueFileKey(
        val taskKey: Long,
        val stage: TaskRuntimeStage,
        val category: TaskRuntimeQueueCategory,
        val fileName: String,
    )

    private companion object {
        val operationLock = TaskRuntimeStoreLock()
    }

    override fun loadTaskSnapshots(): List<Task> = withOperationLock {
        val baseDir = buildRuntimeBaseDir()
        if (!PathUtils.exists(FileAccessPermission.Allowed, baseDir)) return@withOperationLock emptyList()
        PathUtils.getFileAndFolder(FileAccessPermission.Allowed, baseDir)
            .getOrDefault(emptyList())
            .filter { item -> item.isDirectory }
            .mapNotNull { item ->
                loadProtoFile<Task>(
                    path = item.path + PathUtils.getPathSeparator() + TASK_RUNTIME_TASK_FILE_NAME,
                    label = AppStrings.ui_task_snapshot,
                )
            }
            .sortedBy { task -> task.key }
    }

    override fun loadTaskSnapshot(taskKey: Long): Task? = withOperationLock {
        loadProtoFile<Task>(buildTaskSnapshotPath(taskKey), AppStrings.ui_task_snapshot, taskKey)
    }

    override fun saveTaskSnapshot(task: Task) = withOperationLock {
        writeTextFile(
            path = buildTaskSnapshotPath(task.key),
            content = encodeProtoLine(task),
            label = AppStrings.ui_task_snapshot,
            taskKey = task.key,
        )
    }

    override fun deleteTaskSnapshot(taskKey: Long) = withOperationLock {
        deleteFileIfExists(buildTaskSnapshotPath(taskKey), AppStrings.ui_task_snapshot, taskKey)
    }

    override fun loadMeta(taskKey: Long): TaskRuntimeMeta? = withOperationLock {
        loadProtoFile<TaskRuntimeMeta>(buildMetaFilePath(taskKey), AppStrings.ui_task_metadata, taskKey)
    }

    override fun saveMeta(meta: TaskRuntimeMeta) = withOperationLock {
        writeTextFile(
            path = buildMetaFilePath(meta.taskKey),
            content = encodeProtoLine(meta),
            label = AppStrings.ui_task_metadata,
            taskKey = meta.taskKey,
        )
    }

    override fun loadRunState(taskKey: Long): TaskRuntimeRunState? = withOperationLock {
        loadProtoFile<TaskRuntimeRunState>(buildRunStateFilePath(taskKey), AppStrings.ui_task_running_status, taskKey)
    }

    override fun saveRunState(runState: TaskRuntimeRunState) = withOperationLock {
        writeTextFile(
            path = buildRunStateFilePath(runState.taskKey),
            content = encodeProtoLine(runState),
            label = AppStrings.ui_task_running_status,
            taskKey = runState.taskKey,
        )
    }

    override fun deleteRunState(taskKey: Long) = withOperationLock {
        deleteFileIfExists(buildRunStateFilePath(taskKey), AppStrings.ui_task_running_status, taskKey)
    }

    override fun appendQueueEntries(
        taskKey: Long,
        stage: TaskRuntimeStage,
        category: TaskRuntimeQueueCategory,
        entries: List<TaskRuntimeQueueEntry>,
    ) = withOperationLock {
        if (entries.isEmpty()) return@withOperationLock
        ensurePendingCategoryDirectory(taskKey, stage, category)
        var nextFileIndex = nextQueueFileIndex(taskKey, stage, category)
        entries.chunked(TASK_RUNTIME_QUEUE_CHUNK_SIZE).forEach { chunk ->
            val fileName = buildQueueFileName(nextFileIndex++)
            val lines = chunk.map { entry ->
                encodeProtoLine(entry)
            }
            val content = lines.joinToString(separator = "\n", postfix = "\n")
            writeTextFile(
                path = buildQueueFilePath(taskKey, stage, category, fileName),
                content = content,
                label = AppStrings.ui_task_queue_sharding,
                taskKey = taskKey,
            )
        }
    }

    override fun peekNextQueueEntry(
        taskKey: Long,
        stage: TaskRuntimeStage,
        category: TaskRuntimeQueueCategory,
        preferredFile: String?,
    ): TaskRuntimeQueuedEntry? = withOperationLock {
        preferredFile
            ?.takeIf { fileName -> fileName.endsWith(TASK_RUNTIME_QUEUE_FILE_SUFFIX) }
            ?.let { fileName ->
                peekQueueFile(taskKey, stage, category, fileName)?.let { queuedEntry ->
                    return@withOperationLock queuedEntry
                }
            }
        val queueFiles = listPendingQueueFilesLocked(taskKey, stage, category)
        if (queueFiles.isEmpty()) return@withOperationLock null
        queueFiles.filterNot { item -> item == preferredFile }.forEach { fileName ->
            peekQueueFile(taskKey, stage, category, fileName)?.let { queuedEntry ->
                return@withOperationLock queuedEntry
            }
        }
        null
    }

    private fun peekQueueFile(
        taskKey: Long,
        stage: TaskRuntimeStage,
        category: TaskRuntimeQueueCategory,
        fileName: String,
    ): TaskRuntimeQueuedEntry? {
        val path = buildQueueFilePath(taskKey, stage, category, fileName)
        val queueFileKey = RuntimeQueueFileKey(taskKey, stage, category, fileName)
        if (!PathUtils.exists(FileAccessPermission.Allowed, path)) {
            queueLineCache.remove(queueFileKey)
            return null
        }
        val firstLine = readQueueLines(queueFileKey, path, taskKey).firstOrNull { line -> line.isNotBlank() }
        if (firstLine == null) {
            deleteFileIfExists(path, AppStrings.ui_empty_queue_sharding, taskKey)
            queueLineCache.remove(queueFileKey)
            return null
        }
        return TaskRuntimeQueuedEntry(
            fileName = fileName,
            entry = parseQueueEntry(firstLine, taskKey, stage, fileName),
        )
    }

    override fun ackQueueEntry(
        taskKey: Long,
        stage: TaskRuntimeStage,
        category: TaskRuntimeQueueCategory,
        fileName: String,
        entryId: String?,
    ) = withOperationLock {
        val path = buildQueueFilePath(taskKey, stage, category, fileName)
        val queueFileKey = RuntimeQueueFileKey(taskKey, stage, category, fileName)
        if (!PathUtils.exists(FileAccessPermission.Allowed, path)) {
            queueLineCache.remove(queueFileKey)
            return@withOperationLock
        }
        val currentLines = readQueueLines(queueFileKey, path, taskKey)
            .filter { line -> line.isNotBlank() }
        val remainingLines = if (entryId.isNullOrBlank()) {
            currentLines.drop(1)
        } else {
            var removed = false
            currentLines.filter { line ->
                if (removed) return@filter true
                val entry = parseQueueEntry(line, taskKey, stage, fileName)
                if (entry.entryId == entryId) {
                    removed = true
                    false
                } else {
                    true
                }
            }.also {
                if (!removed) {
                    return@withOperationLock
                }
            }
        }
        if (remainingLines.isEmpty()) {
            deleteFileIfExists(path, AppStrings.ui_task_queue_sharding, taskKey)
            queueLineCache.remove(queueFileKey)
            return@withOperationLock
        }
        replaceTextFile(
            path = path,
            content = remainingLines.joinToString(separator = "\n", postfix = "\n"),
            label = AppStrings.ui_task_queue_sharding,
            taskKey = taskKey,
        )
        queueLineCache[queueFileKey] = remainingLines
    }

    override fun listPendingQueueFiles(
        taskKey: Long,
        stage: TaskRuntimeStage,
        category: TaskRuntimeQueueCategory,
    ): List<String> = withOperationLock {
        listPendingQueueFilesLocked(taskKey, stage, category)
    }

    override fun loadPendingQueueEntries(
        taskKey: Long,
        stage: TaskRuntimeStage,
        category: TaskRuntimeQueueCategory,
        limit: Int,
    ): List<TaskRuntimeQueuedEntry> = withOperationLock {
        if (limit <= 0) return@withOperationLock emptyList()
        val entries = mutableListOf<TaskRuntimeQueuedEntry>()
        listPendingQueueFilesLocked(taskKey, stage, category).forEach { fileName ->
            val queueFileKey = RuntimeQueueFileKey(taskKey, stage, category, fileName)
            val path = buildQueueFilePath(taskKey, stage, category, fileName)
            readQueueLines(queueFileKey, path, taskKey)
                .asSequence()
                .filter { line -> line.isNotBlank() }
                .forEach { line ->
                    entries += TaskRuntimeQueuedEntry(
                        fileName = fileName,
                        entry = parseQueueEntry(line, taskKey, stage, fileName),
                    )
                    if (entries.size >= limit) {
                        return@withOperationLock entries
                    }
                }
        }
        entries
    }

    private fun listPendingQueueFilesLocked(
        taskKey: Long,
        stage: TaskRuntimeStage,
        category: TaskRuntimeQueueCategory,
    ): List<String> {
        val categoryDir = buildPendingCategoryDirPath(taskKey, stage, category)
        if (!PathUtils.exists(FileAccessPermission.Allowed, categoryDir)) return emptyList()
        return PathUtils.getFileAndFolder(FileAccessPermission.Allowed, categoryDir)
            .getOrDefault(emptyList())
            .asSequence()
            .filter { item -> !item.isDirectory }
            .map { item -> item.path.substringAfterLast(PathUtils.getPathSeparator()) }
            .filter { item -> item.endsWith(TASK_RUNTIME_QUEUE_FILE_SUFFIX) }
            .sorted()
            .toList()
    }

    override fun countPendingEntries(
        taskKey: Long,
        stage: TaskRuntimeStage?,
        category: TaskRuntimeQueueCategory?,
    ): Int = withOperationLock {
        countPendingEntriesLocked(taskKey, stage, category)
    }

    private fun countPendingEntriesLocked(
        taskKey: Long,
        stage: TaskRuntimeStage?,
        category: TaskRuntimeQueueCategory?,
    ): Int {
        return when {
            stage == null -> TaskRuntimeStage.entries.sumOf { currentStage ->
                countPendingEntriesLocked(taskKey, currentStage, category)
            }

            category == null -> TaskRuntimeQueueCategory.entries.sumOf { currentCategory ->
                countPendingEntriesLocked(taskKey, stage, currentCategory)
            }

            else -> listPendingQueueFilesLocked(taskKey, stage, category).sumOf { fileName ->
                readQueueLines(
                    RuntimeQueueFileKey(taskKey, stage, category, fileName),
                    buildQueueFilePath(taskKey, stage, category, fileName),
                    taskKey,
                    useCache = false,
                )
                    .count { line -> line.isNotBlank() }
            }
        }
    }

    override fun hasPendingEntries(taskKey: Long): Boolean = withOperationLock {
        hasPendingEntriesLocked(taskKey)
    }

    private fun hasPendingEntriesLocked(taskKey: Long): Boolean {
        return TaskRuntimeStage.entries.any { stage ->
            TaskRuntimeQueueCategory.entries.any { category ->
                listPendingQueueFilesLocked(taskKey, stage, category).isNotEmpty()
            }
        }
    }

    override fun loadTransferCheckpoint(taskKey: Long, entryId: String): TaskRuntimeTransferCheckpoint? = withOperationLock {
        loadProtoFile<TaskRuntimeTransferCheckpoint>(
            buildTransferCheckpointPath(taskKey, entryId),
            AppStrings.ui_file_recovery_checkpoint,
            taskKey,
        )
            ?.takeIf { checkpoint -> checkpoint.entryId == entryId }
    }

    override fun saveTransferCheckpoint(checkpoint: TaskRuntimeTransferCheckpoint) = withOperationLock {
        writeTextFile(
            path = buildTransferCheckpointPath(checkpoint.taskKey, checkpoint.entryId),
            content = encodeProtoLine(checkpoint),
            label = AppStrings.ui_file_recovery_checkpoint,
            taskKey = checkpoint.taskKey,
        )
    }

    override fun deleteTransferCheckpoint(taskKey: Long, entryId: String) = withOperationLock {
        deleteFileIfExists(buildTransferCheckpointPath(taskKey, entryId), AppStrings.ui_file_recovery_checkpoint, taskKey)
    }

    override fun clearTaskRuntime(taskKey: Long) = withOperationLock {
        val taskDir = buildTaskDirPath(taskKey)
        queueLineCache.keys.removeAll { key -> key.taskKey == taskKey }
        if (!PathUtils.exists(FileAccessPermission.Allowed, taskDir)) return@withOperationLock
        runCatching {
            PathUtils.deleteDirectory(FileAccessPermission.Allowed, taskDir)
        }.onFailure { error ->
            LogKit.e(AppStrings.ui_failed_delete_task_runtime_directory_taskkey_arg0_path_arg1.format(arg0 = (taskKey).toString(), arg1 = taskDir), error)
        }
    }

    private inline fun <reified T> loadProtoFile(
        path: String,
        label: String,
        taskKey: Long? = null,
    ): T? {
        if (!PathUtils.exists(FileAccessPermission.Allowed, path)) return null
        return runCatching {
            val content = FileUtils.readFile(FileAccessPermission.Allowed, path).getOrElse { failure -> throw failure }
                .decodeToString()
            decodeProtoLine<T>(content)
        }.onFailure { error ->
            LogKit.e(AppStrings.ui_failed_read_arg0_path_arg1_taskkey_arg2.format(arg0 = label, arg1 = path, arg2 = (taskKey ?: "unknown").toString()), error)
        }.getOrNull()
    }

    private fun writeTextFile(
        path: String,
        content: String,
        label: String,
        taskKey: Long,
    ) {
        replaceTextFile(path, content, label, taskKey)
    }

    private fun replaceTextFile(
        path: String,
        content: String,
        label: String,
        taskKey: Long,
    ) {
        ensureParentDirectory(path)
        val tempPath = "$path.tmp"
        runCatching {
            if (PathUtils.exists(FileAccessPermission.Allowed, tempPath)) {
                FileUtils.deleteFile(FileAccessPermission.Allowed, tempPath).getOrElse { failure -> throw failure }
            }
            FileUtils.createFile(FileAccessPermission.Allowed, tempPath).getOrElse { failure -> throw failure }
            FileUtils.appendToFile(FileAccessPermission.Allowed, tempPath, content)
            if (PathUtils.exists(FileAccessPermission.Allowed, path)) {
                FileUtils.deleteFile(FileAccessPermission.Allowed, path).getOrElse { failure -> throw failure }
            }
            renameSiblingFile(tempPath, path).getOrElse { failure -> throw failure }
        }.onFailure { error ->
            LogKit.e(AppStrings.ui_replacement_arg0_failed_path_arg1_taskkey_arg2.format(arg0 = label, arg1 = path, arg2 = (taskKey).toString()), error)
        }
    }

    private fun deleteFileIfExists(
        path: String,
        label: String,
        taskKey: Long,
    ) {
        if (!PathUtils.exists(FileAccessPermission.Allowed, path)) return
        FileUtils.deleteFile(FileAccessPermission.Allowed, path).onFailure { error ->
            LogKit.e(AppStrings.ui_deletion_arg0_failed_path_arg1_taskkey_arg2.format(arg0 = label, arg1 = path, arg2 = (taskKey).toString()), error)
        }
    }

    private fun ensureParentDirectory(path: String) {
        PathUtils.createDirectoryIfNotExists(FileAccessPermission.Allowed, buildParentPath(path))
    }

    private fun ensurePendingCategoryDirectory(
        taskKey: Long,
        stage: TaskRuntimeStage,
        category: TaskRuntimeQueueCategory,
    ) {
        PathUtils.createDirectoryIfNotExists(FileAccessPermission.Allowed, buildPendingCategoryDirPath(taskKey, stage, category))
    }

    private fun readQueueLines(
        key: RuntimeQueueFileKey,
        path: String,
        taskKey: Long,
        useCache: Boolean = true,
    ): List<String> {
        if (useCache) {
            queueLineCache[key]?.let { lines -> return lines }
        }
        return runCatching {
            FileUtils.readFileLines(FileAccessPermission.Allowed, path)
        }.getOrElse { error ->
            LogKit.e(AppStrings.ui_failed_read_task_queue_shards_path_arg0_taskkey_arg1.format(arg0 = path, arg1 = (taskKey).toString()), error)
            throw IllegalStateException(AppStrings.ui_read_task_queue_partition_failed_taskkey_arg0_path_arg1.format(arg0 = (taskKey).toString(), arg1 = (path)), error)
        }.also { lines ->
            if (useCache) {
                queueLineCache[key] = lines
            }
        }
    }

    private fun parseQueueEntry(
        line: String,
        taskKey: Long,
        stage: TaskRuntimeStage,
        fileName: String,
    ): TaskRuntimeQueueEntry {
        return runCatching {
            decodeProtoLine<TaskRuntimeQueueEntry>(line)
        }.getOrElse { error ->
            throw IllegalStateException(
                AppStrings.ui_parsing_task_queue_partition_failed_taskkey_arg0_stage_arg1_file_arg2.format(arg0 = (taskKey).toString(), arg1 = (stage).toString(), arg2 = (fileName)),
                error,
            )
        }
    }

    private fun renameSiblingFile(
        fromPath: String,
        toPath: String,
    ): Result<Boolean> {
        val parentPath = buildParentPath(fromPath)
        return FileUtils.rename(FileAccessPermission.Allowed,
            path = parentPath,
            oldName = fromPath.substringAfterLast(PathUtils.getPathSeparator()),
            newName = toPath.substringAfterLast(PathUtils.getPathSeparator()),
        )
    }

    private fun nextQueueFileIndex(
        taskKey: Long,
        stage: TaskRuntimeStage,
        category: TaskRuntimeQueueCategory,
    ): Int {
        val lastFile = listPendingQueueFilesLocked(taskKey, stage, category).lastOrNull() ?: return 1
        return lastFile.substringBefore('.').toIntOrNull()?.plus(1) ?: 1
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

    private fun buildRuntimeBaseDir(): String {
        return buildCacheChildPath(TASK_RUNTIME_BASE_DIR_NAME)
    }

    private fun buildTaskDirPath(taskKey: Long): String {
        return buildRuntimeBaseDir() + PathUtils.getPathSeparator() + taskKey.toString()
    }

    private fun buildTaskSnapshotPath(taskKey: Long): String {
        return buildTaskDirPath(taskKey) + PathUtils.getPathSeparator() + TASK_RUNTIME_TASK_FILE_NAME
    }

    private fun buildMetaFilePath(taskKey: Long): String {
        return buildTaskDirPath(taskKey) + PathUtils.getPathSeparator() + TASK_RUNTIME_META_FILE_NAME
    }

    private fun buildRunStateFilePath(taskKey: Long): String {
        return buildTaskDirPath(taskKey) + PathUtils.getPathSeparator() + TASK_RUNTIME_RUN_FILE_NAME
    }

    private fun buildPendingStageDirPath(taskKey: Long, stage: TaskRuntimeStage): String {
        return buildTaskDirPath(taskKey) +
            PathUtils.getPathSeparator() +
            TASK_RUNTIME_PENDING_DIR_NAME +
            PathUtils.getPathSeparator() +
            stage.toDirectoryName()
    }

    private fun buildTransferCheckpointDirPath(taskKey: Long): String {
        return buildTaskDirPath(taskKey) +
            PathUtils.getPathSeparator() +
            TASK_RUNTIME_TRANSFER_DIR_NAME
    }

    private fun buildPendingCategoryDirPath(
        taskKey: Long,
        stage: TaskRuntimeStage,
        category: TaskRuntimeQueueCategory,
    ): String {
        return buildPendingStageDirPath(taskKey, stage) +
            PathUtils.getPathSeparator() +
            category.toDirectoryName()
    }

    private fun buildQueueFilePath(
        taskKey: Long,
        stage: TaskRuntimeStage,
        category: TaskRuntimeQueueCategory,
        fileName: String,
    ): String {
        return buildPendingCategoryDirPath(taskKey, stage, category) + PathUtils.getPathSeparator() + fileName
    }

    private fun buildTransferCheckpointPath(taskKey: Long, entryId: String): String {
        return buildTransferCheckpointDirPath(taskKey) +
            PathUtils.getPathSeparator() +
            buildTransferCheckpointFileName(entryId)
    }

    private fun buildCacheChildPath(name: String): String {
        val separator = PathUtils.getPathSeparator()
        val cachePath = PathUtils.getCachePath().trimEnd('/', '\\')
        return if (cachePath.endsWith(separator)) {
            cachePath + name
        } else {
            cachePath + separator + name
        }
    }

    private fun buildParentPath(path: String): String {
        val separator = PathUtils.getPathSeparator()
        val normalized = path.trimEnd('/', '\\')
        val lastSeparatorIndex = normalized.lastIndexOf(separator)
        return if (lastSeparatorIndex <= 0) {
            separator
        } else {
            normalized.substring(0, lastSeparatorIndex)
        }
    }

    private fun buildQueueFileName(index: Int): String {
        return index.toString().padStart(6, '0') + TASK_RUNTIME_QUEUE_FILE_SUFFIX
    }

    private fun buildTransferCheckpointFileName(entryId: String): String {
        val suffix = entryId
            .takeLast(64)
            .map { char -> if (char.isLetterOrDigit() || char == '-' || char == '_') char else '_' }
            .joinToString("")
            .ifBlank { "entry" }
        val hash = entryId.fold(1125899906842597L) { acc, char -> acc * 31L + char.code.toLong() }
            .toString()
            .replace("-", "n")
        return "$suffix-$hash$TASK_RUNTIME_TRANSFER_FILE_SUFFIX"
    }

    private fun TaskRuntimeStage.toDirectoryName(): String {
        return when (this) {
            TaskRuntimeStage.COPY -> "copy"
            TaskRuntimeStage.DELETE_SOURCE -> "delete-source"
            TaskRuntimeStage.DELETE -> "delete"
        }
    }

    private fun TaskRuntimeQueueCategory.toDirectoryName(): String {
        return when (this) {
            TaskRuntimeQueueCategory.DIRECTORIES -> "directories"
            TaskRuntimeQueueCategory.EMPTY_FILES -> "empty-files"
            TaskRuntimeQueueCategory.FILES -> "files"
        }
    }
}

fun buildTaskRuntimeRunId(taskKey: Long): String {
    return "${taskKey}-${Clock.System.now().toEpochMilliseconds()}"
}
