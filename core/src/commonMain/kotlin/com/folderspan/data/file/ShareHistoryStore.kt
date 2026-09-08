package com.folderspan.data.file

import strings.AppStrings

import com.folderspan.utils.FileAccessPermission
import com.folderspan.data.main.device.DeviceType
import com.folderspan.ui.state.file.FileShareStatus
import com.folderspan.ui.state.main.TaskRuntimeStoreLock
import com.folderspan.utils.FileUtils
import com.folderspan.utils.LogKit
import com.folderspan.utils.PathUtils
import com.folderspan.utils.ProtoBufCodec
import kotlinx.serialization.Serializable
import kotlin.io.encoding.Base64
import kotlin.time.Clock

private const val SHARE_HISTORY_STORE_DIR_NAME = "share-history"
private const val SHARE_HISTORY_STORE_META_FILE_NAME = "meta.pb64"
private const val SHARE_HISTORY_STORE_FILE_NAME = "history.pb64l"

enum class ShareHistoryDirection {
    OUTGOING,
    INCOMING,
}

data class ShareHistoryInput(
    val fileName: String,
    val filePath: String,
    val fileSize: Long,
    val isDirectory: Boolean,
    val sourceDeviceId: String,
    val sourceDeviceName: String,
    val sourceDeviceType: DeviceType,
    val targetDeviceId: String,
    val targetDeviceName: String,
    val targetDeviceType: DeviceType,
    val isOutgoing: Boolean,
    val timestamp: Long = Clock.System.now().toEpochMilliseconds(),
    val status: FileShareStatus,
    val errorMessage: String? = "",
    val savePath: String? = "",
)

interface ShareHistoryStore {
    fun add(input: ShareHistoryInput): FileShareHistory

    fun query(
        direction: ShareHistoryDirection? = null,
        status: FileShareStatus? = null,
    ): List<FileShareHistory>

    fun delete(id: Long)

    fun clear()
}

@Serializable
private data class ShareHistoryStoreMeta(
    val nextHistoryId: Long = 1L,
)

@Serializable
private data class ShareHistoryRecord(
    val id: Long,
    val fileName: String,
    val filePath: String,
    val fileSize: Long,
    val isDirectory: Boolean,
    val sourceDeviceId: String,
    val sourceDeviceName: String,
    val sourceDeviceType: DeviceType,
    val targetDeviceId: String,
    val targetDeviceName: String,
    val targetDeviceType: DeviceType,
    val isOutgoing: Boolean,
    val timestamp: Long,
    val status: FileShareStatus,
    val errorMessage: String? = "",
    val savePath: String? = "",
) {
    fun toHistory(): FileShareHistory {
        return FileShareHistory(
            id = id,
            fileName = fileName,
            filePath = filePath,
            fileSize = fileSize,
            isDirectory = isDirectory,
            sourceDeviceId = sourceDeviceId,
            sourceDeviceName = sourceDeviceName,
            sourceDeviceType = sourceDeviceType,
            targetDeviceId = targetDeviceId,
            targetDeviceName = targetDeviceName,
            targetDeviceType = targetDeviceType,
            isOutgoing = isOutgoing,
            timestamp = timestamp,
            status = status,
            errorMessage = errorMessage,
            savePath = savePath,
        )
    }
}

class TempShareHistoryStore(
    private val baseDir: String = buildDefaultShareHistoryStoreDir(),
) : ShareHistoryStore {
    private companion object {
        val operationLock = TaskRuntimeStoreLock()
    }

    override fun add(input: ShareHistoryInput): FileShareHistory = withOperationLock {
        val record = input.toRecord(nextHistoryId())
        appendRecord(record)
        record.toHistory()
    }

    override fun query(
        direction: ShareHistoryDirection?,
        status: FileShareStatus?,
    ): List<FileShareHistory> = withOperationLock {
        loadRecords()
            .asSequence()
            .filter { record ->
                when (direction) {
                    ShareHistoryDirection.OUTGOING -> record.isOutgoing
                    ShareHistoryDirection.INCOMING -> !record.isOutgoing
                    null -> true
                }
            }
            .filter { record -> status == null || record.status == status }
            .sortedWith(compareByDescending<ShareHistoryRecord> { record -> record.timestamp }.thenByDescending { it.id })
            .map { record -> record.toHistory() }
            .toList()
    }

    override fun delete(id: Long) = withOperationLock {
        val remaining = loadRecords().filterNot { record -> record.id == id }
        replaceRecords(remaining)
    }

    override fun clear() = withOperationLock {
        deleteFileIfExists(buildHistoryFilePath(), AppStrings.ui_share_history)
    }

    private fun ShareHistoryInput.toRecord(id: Long): ShareHistoryRecord {
        return ShareHistoryRecord(
            id = id,
            fileName = fileName,
            filePath = filePath,
            fileSize = fileSize,
            isDirectory = isDirectory,
            sourceDeviceId = sourceDeviceId,
            sourceDeviceName = sourceDeviceName,
            sourceDeviceType = sourceDeviceType,
            targetDeviceId = targetDeviceId,
            targetDeviceName = targetDeviceName,
            targetDeviceType = targetDeviceType,
            isOutgoing = isOutgoing,
            timestamp = timestamp,
            status = status,
            errorMessage = errorMessage,
            savePath = savePath,
        )
    }

    private fun appendRecord(record: ShareHistoryRecord) {
        ensureParentDirectory(buildHistoryFilePath())
        FileUtils.createFile(FileAccessPermission.Allowed, buildHistoryFilePath()).onFailure { error ->
            LogKit.e(AppStrings.ui_create_sharing_history_file_failure, error)
        }
        runCatching {
            FileUtils.appendToFile(FileAccessPermission.Allowed,
                buildHistoryFilePath(),
                encodeProtoLine(record) + "\n",
            )
        }.onFailure { error ->
            LogKit.e(AppStrings.ui_append_history_failure_id_arg0.format(arg0 = (record.id).toString()), error)
        }
    }

    private fun loadRecords(): List<ShareHistoryRecord> {
        val path = buildHistoryFilePath()
        if (!PathUtils.exists(FileAccessPermission.Allowed, path)) return emptyList()
        return FileUtils.readFileLines(FileAccessPermission.Allowed, path)
            .mapNotNull { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty()) return@mapNotNull null
                runCatching { decodeProtoLine<ShareHistoryRecord>(trimmed) }
                    .onFailure { error -> LogKit.e(AppStrings.ui_parse_history_failure_path_arg0.format(arg0 = (path)), error) }
                    .getOrNull()
            }
    }

    private fun replaceRecords(records: List<ShareHistoryRecord>) {
        if (records.isEmpty()) {
            deleteFileIfExists(buildHistoryFilePath(), AppStrings.ui_share_history)
            return
        }
        replaceTextFile(
            path = buildHistoryFilePath(),
            content = records.joinToString(separator = "\n", postfix = "\n") { record ->
                encodeProtoLine(record)
            },
            label = AppStrings.ui_share_history,
        )
    }

    private fun nextHistoryId(): Long {
        val meta = loadMeta()
        val nextId = meta.nextHistoryId.coerceAtLeast(1L)
        saveMeta(meta.copy(nextHistoryId = nextId + 1L))
        return nextId
    }

    private fun loadMeta(): ShareHistoryStoreMeta {
        val path = buildMetaFilePath()
        if (!PathUtils.exists(FileAccessPermission.Allowed, path)) return ShareHistoryStoreMeta()
        return runCatching {
            val content = FileUtils.readFile(FileAccessPermission.Allowed, path).getOrElse { failure -> throw failure }.decodeToString()
            decodeProtoLine<ShareHistoryStoreMeta>(content)
        }.onFailure { error ->
            LogKit.e(AppStrings.ui_reading_metadata_for_shared_history_failed_path_arg0.format(arg0 = (path)), error)
        }.getOrDefault(ShareHistoryStoreMeta())
    }

    private fun saveMeta(meta: ShareHistoryStoreMeta) {
        replaceTextFile(
            path = buildMetaFilePath(),
            content = encodeProtoLine(meta),
            label = AppStrings.ui_share_historical_metadata,
        )
    }

    private fun replaceTextFile(path: String, content: String, label: String) {
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
            LogKit.e(AppStrings.ui_replacement_arg0_failed_path_arg1.format(arg0 = (label), arg1 = (path)), error)
        }
    }

    private fun deleteFileIfExists(path: String, label: String) {
        if (!PathUtils.exists(FileAccessPermission.Allowed, path)) return
        FileUtils.deleteFile(FileAccessPermission.Allowed, path).onFailure { error ->
            LogKit.e(AppStrings.ui_failed_delete_arg0_path_arg1.format(arg0 = (label), arg1 = (path)), error)
        }
    }

    private fun renameSiblingFile(fromPath: String, toPath: String): Result<Boolean> {
        val separator = PathUtils.getPathSeparator()
        return FileUtils.rename(FileAccessPermission.Allowed,
            path = buildParentPath(fromPath),
            oldName = fromPath.substringAfterLast(separator),
            newName = toPath.substringAfterLast(separator),
        )
    }

    private fun ensureParentDirectory(path: String) {
        PathUtils.createDirectoryIfNotExists(FileAccessPermission.Allowed, buildParentPath(path))
    }

    private fun buildMetaFilePath(): String {
        return joinPath(baseDir, SHARE_HISTORY_STORE_META_FILE_NAME)
    }

    private fun buildHistoryFilePath(): String {
        return joinPath(baseDir, SHARE_HISTORY_STORE_FILE_NAME)
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

private fun buildDefaultShareHistoryStoreDir(): String {
    return joinPath(PathUtils.getAppPath().trimEnd('/', '\\'), SHARE_HISTORY_STORE_DIR_NAME)
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
