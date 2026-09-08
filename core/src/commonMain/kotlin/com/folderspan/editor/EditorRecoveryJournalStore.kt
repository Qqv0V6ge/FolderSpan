package com.folderspan.editor

import com.folderspan.utils.FileAccessPermission
import com.folderspan.utils.FileUtils
import com.folderspan.utils.PathUtils
import com.folderspan.utils.ProtoBufCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import strings.AppStrings

private const val EDITOR_RECOVERY_DIRECTORY = "file-editor-recovery"
private const val EDITOR_RECOVERY_EXTENSION = ".recovery.pb"

@Serializable
data class EditorRecoveryJournal(
    val sourceSize: Long,
    val sourceUpdatedAt: Long? = null,
    val sourceRevision: String? = null,
    val pageSize: Int,
    val encoding: String,
    val newlineConversion: String? = null,
    val currentPage: Long,
    val pages: List<EditorRecoveryPage>,
)

@Serializable
data class EditorRecoveryPage(
    val pageIndex: Long,
    val originalBytes: ByteArray,
    val replacementBytes: ByteArray,
    val modificationKind: String,
    val encodingReplacementCount: Int = 0,
    val encodingReplacementSamples: List<EditorRecoveryReplacementSample> = emptyList(),
)

@Serializable
data class EditorRecoveryReplacementSample(
    val characterIndex: Int,
    val characterCode: Int,
)

interface EditorRecoveryJournalStore {
    suspend fun read(key: String): Result<EditorRecoveryJournal?>
    suspend fun write(key: String, journal: EditorRecoveryJournal): Result<Unit>
    suspend fun remove(key: String): Result<Unit>
    suspend fun cleanup(maxEntries: Int = 20)
}

class ApplicationPrivateEditorRecoveryJournalStore : EditorRecoveryJournalStore {
    override suspend fun read(key: String): Result<EditorRecoveryJournal?> =
        withContext(Dispatchers.Default) {
            runCatching {
                val path = recoveryPath(key)
                if (!PathUtils.exists(FileAccessPermission.Allowed, path)) null
                else ProtoBufCodec.decode<EditorRecoveryJournal>(FileUtils.readFile(FileAccessPermission.Allowed, path).getOrThrow())
            }
        }

    override suspend fun write(key: String, journal: EditorRecoveryJournal): Result<Unit> =
        withContext(Dispatchers.Default) {
            runCatching {
                val path = recoveryPath(key)
                val payload = ProtoBufCodec.encode(journal)
                if (PathUtils.exists(FileAccessPermission.Allowed, path)) FileUtils.deleteFile(FileAccessPermission.Allowed, path).getOrThrow()
                check(FileUtils.createFile(FileAccessPermission.Allowed, path).getOrThrow()) { AppStrings.ui_unable_to_create_an_editor_to_restore_logs }
                check(FileUtils.writeBytes(FileAccessPermission.Allowed, path, payload.size.toLong(), payload, 0L).getOrThrow()) {
                    AppStrings.ui_unable_to_restore_logs_to_the_editor
                }
            }
        }

    override suspend fun remove(key: String): Result<Unit> = withContext(Dispatchers.Default) {
        runCatching {
            val path = recoveryPath(key)
            if (PathUtils.exists(FileAccessPermission.Allowed, path)) FileUtils.deleteFile(FileAccessPermission.Allowed, path).getOrThrow()
        }
    }

    override suspend fun cleanup(maxEntries: Int) = withContext(Dispatchers.Default) {
        require(maxEntries >= 0)
        val entries = PathUtils.getFileAndFolder(FileAccessPermission.Allowed, recoveryDirectory()).getOrDefault(emptyList())
            .filter { !it.isDirectory && it.name.endsWith(EDITOR_RECOVERY_EXTENSION) }
            .sortedByDescending { it.updatedDate }
        entries.drop(maxEntries).forEach { FileUtils.deleteFile(FileAccessPermission.Allowed, it.path) }
    }

    private fun recoveryPath(key: String): String = joinRecoveryPath(
        recoveryDirectory(),
        key.filter { it.isLetterOrDigit() || it == '_' || it == '-' }
            .take(120)
            .ifBlank { "editor" } + EDITOR_RECOVERY_EXTENSION,
    )

    private fun recoveryDirectory(): String {
        val directory = joinRecoveryPath(PathUtils.getCachePath(), EDITOR_RECOVERY_DIRECTORY)
        PathUtils.createDirectoryIfNotExists(FileAccessPermission.Allowed, directory)
        return directory
    }
}

private fun joinRecoveryPath(parent: String, child: String): String {
    val separator = PathUtils.getPathSeparator()
    return if (parent.endsWith(separator)) parent + child else parent + separator + child
}
