package com.folderspan.editor

import com.folderspan.utils.FileAccessPermission
import com.folderspan.utils.FileUtils
import com.folderspan.utils.PathUtils
import com.folderspan.utils.ProtoBufCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import strings.AppStrings

private const val EDITOR_BACKUP_DIRECTORY = "file-editor-backups"
private const val EDITOR_COMPLETE_BACKUP_EXTENSION = ".full.bak"
private const val EDITOR_RANGE_BACKUP_EXTENSION = ".ranges.pb"

data class EditorBackupRange(
    val startOffset: Long,
    val originalBytes: ByteArray,
)

data class EditorBackupHandle(
    val id: String,
    val path: String,
    val mode: EditorBackupMode,
    val storedBytes: Long,
)

interface EditorBackupStore {
    fun availableBytes(): Long?

    suspend fun createCompleteBackup(
        key: String,
        snapshot: FileEditorSourceSnapshot,
        size: Long,
        content: Flow<ByteArray>,
    ): Result<EditorBackupHandle>

    suspend fun createRangeBackup(
        key: String,
        snapshot: FileEditorSourceSnapshot,
        ranges: List<EditorBackupRange>,
    ): Result<EditorBackupHandle>

    suspend fun cleanup(maxEntries: Int = 5)
}

class ApplicationPrivateEditorBackupStore : EditorBackupStore {
    override fun availableBytes(): Long? = runCatching {
        FileUtils.freeSpace(FileAccessPermission.Allowed, backupDirectory()).takeIf { it >= 0L }
    }.getOrNull()

    override suspend fun createCompleteBackup(
        key: String,
        snapshot: FileEditorSourceSnapshot,
        size: Long,
        content: Flow<ByteArray>,
    ): Result<EditorBackupHandle> = withContext(Dispatchers.Default) {
        runCatching {
            require(size >= 0L)
            val id = backupId(key, snapshot)
            val path = joinPath(backupDirectory(), id + EDITOR_COMPLETE_BACKUP_EXTENSION)
            recreateFile(path)
            var offset = 0L
            val ranges = flow {
                content.collect { chunk ->
                    if (chunk.isNotEmpty()) {
                        emit(offset to chunk)
                        offset += chunk.size
                    }
                }
                check(offset == size) { AppStrings.ui_complete_backup_read_length_mismatch }
            }
            check(FileUtils.writeByteRanges(FileAccessPermission.Allowed, path, size, ranges) { _, _ -> }.getOrThrow()) {
                AppStrings.ui_unable_to_write_a_complete_backup
            }
            EditorBackupHandle(id, path, EditorBackupMode.CompleteFile, size)
        }
    }

    override suspend fun createRangeBackup(
        key: String,
        snapshot: FileEditorSourceSnapshot,
        ranges: List<EditorBackupRange>,
    ): Result<EditorBackupHandle> = withContext(Dispatchers.Default) {
        runCatching {
            val id = backupId(key, snapshot)
            val path = joinPath(backupDirectory(), id + EDITOR_RANGE_BACKUP_EXTENSION)
            val payload = ProtoBufCodec.encode(
                EditorRangeBackupEnvelope(
                    size = snapshot.size,
                    updatedAt = snapshot.updatedAt,
                    revision = snapshot.revision,
                    ranges = ranges.map { range ->
                        EditorRangeBackupRecord(range.startOffset, range.originalBytes)
                    },
                )
            )
            recreateFile(path)
            check(FileUtils.writeBytes(FileAccessPermission.Allowed, path, payload.size.toLong(), payload, 0L).getOrThrow()) {
                AppStrings.ui_the_range_backup_cannot_be_written
            }
            EditorBackupHandle(
                id = id,
                path = path,
                mode = EditorBackupMode.ModifiedRanges,
                storedBytes = ranges.sumOf { it.originalBytes.size.toLong() },
            )
        }
    }

    override suspend fun cleanup(maxEntries: Int) = withContext(Dispatchers.Default) {
        require(maxEntries >= 0)
        val entries = PathUtils.getFileAndFolder(FileAccessPermission.Allowed, backupDirectory()).getOrDefault(emptyList())
            .filter { entry ->
                !entry.isDirectory && (
                    entry.name.endsWith(EDITOR_COMPLETE_BACKUP_EXTENSION) ||
                        entry.name.endsWith(EDITOR_RANGE_BACKUP_EXTENSION)
                    )
            }
            .sortedByDescending { it.updatedDate }
        entries.drop(maxEntries).forEach { FileUtils.deleteFile(FileAccessPermission.Allowed, it.path) }
    }

    private fun recreateFile(path: String) {
        if (PathUtils.exists(FileAccessPermission.Allowed, path)) FileUtils.deleteFile(FileAccessPermission.Allowed, path).getOrThrow()
        check(FileUtils.createFile(FileAccessPermission.Allowed, path).getOrThrow()) { AppStrings.ui_unable_to_create_an_editor_backup }
    }

    private fun backupId(key: String, snapshot: FileEditorSourceSnapshot): String =
        sanitizeKey(key) + "_" + sanitizeKey(snapshot.revision ?: snapshot.updatedAt?.toString() ?: "unknown") +
            "_${snapshot.size}"

    private fun backupDirectory(): String {
        val directory = joinPath(PathUtils.getCachePath(), EDITOR_BACKUP_DIRECTORY)
        PathUtils.createDirectoryIfNotExists(FileAccessPermission.Allowed, directory)
        return directory
    }
}

@Serializable
private data class EditorRangeBackupEnvelope(
    val size: Long,
    val updatedAt: Long? = null,
    val revision: String? = null,
    val ranges: List<EditorRangeBackupRecord>,
)

@Serializable
private data class EditorRangeBackupRecord(
    val startOffset: Long,
    val originalBytes: ByteArray,
)

private fun sanitizeKey(value: String): String = value
    .filter { it.isLetterOrDigit() || it == '_' || it == '-' }
    .take(80)
    .ifBlank { "editor" }

private fun joinPath(parent: String, child: String): String {
    val separator = PathUtils.getPathSeparator()
    return if (parent.endsWith(separator)) parent + child else parent + separator + child
}
