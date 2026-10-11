package com.folderspan.editor

import kotlinx.coroutines.flow.Flow
import strings.AppStrings

data class FileEditorSourceCapabilities(
    val supportsRangeRead: Boolean = true,
    val supportsRangeWrite: Boolean = false,
    val supportsStreamedReplace: Boolean = false,
    val supportsSaveAs: Boolean = true,
    val supportsAtomicReplace: Boolean = false,
)

data class FileEditorSourceSnapshot(
    val size: Long,
    val updatedAt: Long? = null,
    val revision: String? = null,
)

class FileEditorSourceChangedException(
    val expected: FileEditorSourceSnapshot,
    val actual: FileEditorSourceSnapshot,
) : IllegalStateException(AppStrings.ui_file_has_been_modified_externally_please_reload_save_as_or_confirm_overwrite)

class FileEditorUnsupportedOperationException(
    operation: String,
) : UnsupportedOperationException(AppStrings.ui_current_content_source_does_not_support_arg0.format(arg0 = (operation)))

/**
 * Protocol-independent, bounded access to the contents edited by [FileEditorDocument].
 *
 * [readRange] uses an end-exclusive byte range. [replaceContent] must consume [content]
 * sequentially and replace the complete backing file only when the stream succeeds.
 */
interface FileEditorContentSource {
    val size: Long
    val canWrite: Boolean
    /**
     * 内容已经位于本地文件系统时返回其路径，供图片等支持采样读取的消费者使用。
     * 远端缓存的生命周期仍由 [close] 管理，调用方不得长期持有该路径。
     */
    val localPath: String?
        get() = null
    val capabilities: FileEditorSourceCapabilities
        get() = FileEditorSourceCapabilities(
            supportsStreamedReplace = canWrite,
        )

    suspend fun currentSnapshot(): Result<FileEditorSourceSnapshot> = Result.success(
        FileEditorSourceSnapshot(size = size)
    )

    suspend fun readRange(
        startOffset: Long,
        endOffsetExclusive: Long,
    ): Result<ByteArray>

    suspend fun replaceContent(
        newSize: Long,
        content: Flow<ByteArray>,
        expectedSnapshot: FileEditorSourceSnapshot? = null,
        onProgress: suspend (writtenBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): Result<Unit>

    suspend fun writeRange(
        startOffset: Long,
        data: ByteArray,
        expectedSnapshot: FileEditorSourceSnapshot? = null,
        onProgress: suspend (writtenBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): Result<Unit> = Result.failure(
        FileEditorUnsupportedOperationException(AppStrings.ui_long_range_writing)
    )

    suspend fun saveAs(
        destination: FileEditorContentSource,
        newSize: Long,
        content: Flow<ByteArray>,
        expectedDestinationSnapshot: FileEditorSourceSnapshot? = null,
        onProgress: suspend (writtenBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): Result<Unit> {
        if (!capabilities.supportsSaveAs) {
            return Result.failure(FileEditorUnsupportedOperationException(AppStrings.ui_save_as))
        }
        return destination.replaceContent(
            newSize = newSize,
            content = content,
            expectedSnapshot = expectedDestinationSnapshot,
            onProgress = onProgress,
        )
    }

    suspend fun close() = Unit
}
