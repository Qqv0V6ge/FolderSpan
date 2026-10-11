package com.folderspan.service.http.client

import com.folderspan.utils.FileAccessPermission
import com.folderspan.service.http.archive.*
import io.ktor.utils.io.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.flow
import strings.AppStrings

internal class FolderSpanArchiveTransferException(
    message: String,
    val completedRelativePaths: Set<String>,
    cause: Throwable? = null,
) : Exception(message, cause)

internal fun Throwable.completedArchiveRelativePaths(): Set<String> =
    when (this) {
        is FolderSpanArchiveTransferException -> completedRelativePaths
        else -> cause?.completedArchiveRelativePaths().orEmpty()
    }

internal suspend fun extractFolderSpanArchiveToLocal(
    channel: ByteReadChannel,
    destinationRootPath: String,
    onEntryWritten: suspend (String) -> Unit = {},
): Set<String> {
    return coroutineScope {
        try {
            ArchiveStreamDecoder.decode(
                chunks = flow {
                    val buffer = ByteArray(FOLDER_SPAN_ARCHIVE_BUFFER_BYTES)
                    while (true) {
                        val read = channel.readAvailable(buffer, 0, buffer.size)
                        if (read < 0) break
                        if (read > 0) emit(buffer.copyOf(read))
                    }
                },
                sink = ArchiveEntryExtractor(
                    scope = this,
                    destinationRootPath = destinationRootPath,
                    onEntryCommitted = onEntryWritten,
                ),
            ).completedRelativePaths.toSet()
        } catch (error: CancellationException) {
            throw error
        } catch (error: FolderSpanArchiveStreamException) {
            throw FolderSpanArchiveTransferException(
                message = error.message ?: AppStrings.ui_archive_transmission_failed,
                completedRelativePaths = error.partialResult.completedRelativePaths.toSet(),
                cause = error,
            )
        }
    }
}
