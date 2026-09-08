package com.folderspan.service.http.archive

import com.folderspan.utils.FileAccessPermission
import com.folderspan.utils.FileUtils
import com.folderspan.utils.PathUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import strings.AppStrings

/**
 * Incrementally extracts archive entries. A file is considered committed only after the platform
 * writer has flushed and closed successfully. The current partial file is removed on failure.
 */
internal class ArchiveEntryExtractor(
    private val scope: CoroutineScope,
    private val destinationRootPath: String,
    private val prepareDirectory: suspend (path: String) -> Unit = {},
    private val prepareFile: suspend (path: String, size: Long) -> Unit = { _, _ -> },
    private val onEntryCommitted: suspend (relativePath: String) -> Unit = {},
) : FolderSpanArchiveEntrySink {
    private var active: ActiveFile? = null

    override suspend fun begin(header: FolderSpanArchiveEntryHeader) {
        check(active == null) { "an archive entry is already being extracted" }
        val targetPath = FolderSpanArchiveCodec.buildTargetPath(
            destinationRootPath,
            FolderSpanArchiveCodec.normalizeRelativePath(header.relativePath),
        )
        when (header.kind) {
            FolderSpanArchiveEntryKind.Directory -> prepareDirectory(targetPath)
            FolderSpanArchiveEntryKind.File -> {
                prepareFile(targetPath, header.size)
                createArchiveParentDirectoryIfNeeded(targetPath)
                val input = Channel<ByteArray>(capacity = 1)
                active = ActiveFile(
                    header = header,
                    targetPath = targetPath,
                    input = input,
                    writer = scope.async {
                        var pending = ByteArray(0)
                        var pendingOffset = 0
                        FileUtils.writeByteStream(
                            permission = FileAccessPermission.Allowed,
                            path = targetPath,
                            fileSize = header.size,
                            startOffset = 0L,
                            expectedBytes = header.size,
                            bufferSize = FOLDER_SPAN_ARCHIVE_BUFFER_BYTES,
                            readNext = { buffer, length ->
                                while (pendingOffset >= pending.size) {
                                    pending = input.receiveCatching().getOrNull() ?: return@writeByteStream -1
                                    pendingOffset = 0
                                    if (pending.isNotEmpty()) break
                                }
                                val copied = minOf(length, pending.size - pendingOffset)
                                pending.copyInto(
                                    destination = buffer,
                                    startIndex = pendingOffset,
                                    endIndex = pendingOffset + copied,
                                )
                                pendingOffset += copied
                                copied
                            },
                            onBytesWritten = { _, _ -> },
                        ).getOrThrow().also { written ->
                            check(written) { AppStrings.ui_inserting_archive_file_failed_arg0.format(arg0 = header.relativePath) }
                        }
                    },
                )
            }

            else -> throw IllegalArgumentException(AppStrings.ui_archive_entry_type_invalid)
        }
    }

    override suspend fun write(header: FolderSpanArchiveEntryHeader, chunk: ByteArray) {
        val current = checkNotNull(active) { "archive file entry has not started" }
        check(current.header.relativePath == header.relativePath) { "archive entry changed while writing" }
        if (chunk.isNotEmpty()) current.input.send(chunk)
    }

    override suspend fun complete(header: FolderSpanArchiveEntryHeader) {
        when (header.kind) {
            FolderSpanArchiveEntryKind.Directory -> {
                val targetPath = FolderSpanArchiveCodec.buildTargetPath(destinationRootPath, header.relativePath)
                PathUtils.createDirectoryIfNotExists(FileAccessPermission.Allowed, targetPath)
            }

            FolderSpanArchiveEntryKind.File -> {
                val current = checkNotNull(active) { "archive file entry has not started" }
                check(current.header.relativePath == header.relativePath) { "archive entry changed while completing" }
                current.input.close()
                try {
                    current.writer.await()
                } finally {
                    active = null
                }
            }
        }
        onEntryCommitted(header.relativePath)
    }

    override suspend fun abort(header: FolderSpanArchiveEntryHeader, cause: Throwable) {
        val current = active ?: return
        active = null
        current.input.close(cause)
        current.writer.cancelAndJoin()
        FileUtils.deleteFile(FileAccessPermission.Allowed, current.targetPath)
    }

    private data class ActiveFile(
        val header: FolderSpanArchiveEntryHeader,
        val targetPath: String,
        val input: Channel<ByteArray>,
        val writer: Deferred<Boolean>,
    )
}

private fun createArchiveParentDirectoryIfNeeded(path: String) {
    val separator = PathUtils.getPathSeparator().ifBlank { "/" }
    val parent = path.substringBeforeLast(separator, missingDelimiterValue = "")
    if (parent.isNotBlank()) {
        PathUtils.createDirectoryIfNotExists(FileAccessPermission.Allowed, parent)
    }
}
