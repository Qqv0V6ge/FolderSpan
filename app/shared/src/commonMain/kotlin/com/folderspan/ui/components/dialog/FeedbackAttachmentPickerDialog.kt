package com.folderspan.ui.components.dialog

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.folderspan.data.file.FileFilterType
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.pro.domain.model.FeedbackDownload
import com.folderspan.pro.domain.model.FeedbackUpload
import com.folderspan.pro.domain.model.MAX_FEEDBACK_ATTACHMENT_BYTES
import com.folderspan.pro.domain.model.SUPPORTED_FEEDBACK_ATTACHMENT_EXTENSIONS
import com.folderspan.pro.presentation.screen.feedback.FeedbackAttachmentPicker
import com.folderspan.pro.presentation.screen.feedback.FeedbackAttachmentTransferSaver
import com.folderspan.pro.presentation.screen.feedback.FeedbackDownloadDestination
import com.folderspan.pro.presentation.screen.feedback.FeedbackDownloadDestinationPicker
import com.folderspan.pro.presentation.screen.feedback.LocalFeedbackAttachmentPicker
import com.folderspan.pro.presentation.screen.feedback.LocalFeedbackAttachmentTransferSaver
import com.folderspan.pro.presentation.screen.feedback.LocalFeedbackDownloadDestinationPicker
import com.folderspan.pro.presentation.screen.feedback.sanitizeFeedbackAttachmentFileName
import com.folderspan.ui.components.file.FileSelectorEntryRegion
import com.folderspan.ui.components.file.FileSelectorConstraints
import com.folderspan.ui.components.file.FileSelectorEntryKind
import com.folderspan.ui.components.model.FileFilterTypeListUiState
import com.folderspan.ui.components.model.FileSelectionUiState
import com.folderspan.utils.FileAccessPermission
import com.folderspan.utils.FileUtils
import com.folderspan.utils.PathUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import strings.AppStrings

private const val MAX_FEEDBACK_DOWNLOAD_NAME_ATTEMPTS = 1_000
private const val FEEDBACK_ATTACHMENT_READ_CHUNK_BYTES = 64L * 1024L

internal val FeedbackAttachmentDisplayConstraints = FileSelectorConstraints(
    allowedExtensions = SUPPORTED_FEEDBACK_ATTACHMENT_EXTENSIONS.toSet(),
)

internal val FeedbackAttachmentSelectionConstraints = FileSelectorConstraints(
    allowedKinds = setOf(FileSelectorEntryKind.File),
    allowedExtensions = SUPPORTED_FEEDBACK_ATTACHMENT_EXTENSIONS.toSet(),
    maxSizeBytes = MAX_FEEDBACK_ATTACHMENT_BYTES,
)

internal val FeedbackDownloadDestinationConstraints = FileSelectorConstraints(
    allowedKinds = setOf(FileSelectorEntryKind.Directory),
)

@Composable
fun FeedbackAttachmentPickerProvider(
    content: @Composable () -> Unit,
) {
    var pendingResult by remember { mutableStateOf<((Result<FeedbackUpload?>) -> Unit)?>(null) }
    var pendingDestination by remember {
        mutableStateOf<Pair<String?, (Result<FeedbackDownloadDestination?>) -> Unit>?>(null)
    }
    var fileJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val picker = remember {
        FeedbackAttachmentPicker { onResult ->
            pendingResult?.invoke(Result.success(null))
            pendingResult = onResult
        }
    }
    val destinationPicker = remember {
        FeedbackDownloadDestinationPicker { suggestedFileName, onResult ->
            pendingDestination?.second?.invoke(Result.success(null))
            pendingDestination = suggestedFileName to onResult
        }
    }
    val transferSaver = remember {
        FeedbackAttachmentTransferSaver { destination, download, onProgress ->
            saveFeedbackAttachmentDownload(destination.directory, download, onProgress)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            fileJob?.cancel()
            pendingResult?.invoke(Result.success(null))
            pendingResult = null
            pendingDestination?.second?.invoke(Result.success(null))
            pendingDestination = null
        }
    }

    CompositionLocalProvider(
        LocalFeedbackAttachmentPicker provides picker,
        LocalFeedbackDownloadDestinationPicker provides destinationPicker,
        LocalFeedbackAttachmentTransferSaver provides transferSaver,
    ) {
        content()
        pendingResult?.let { onResult ->
            key(onResult) {
                FeedbackAttachmentPickerDialog(
                    onDismiss = {
                        pendingResult = null
                        onResult(Result.success(null))
                    },
                    onConfirm = { file ->
                        pendingResult = null
                        fileJob?.cancel()
                        fileJob = scope.launch {
                            onResult(
                                withContext(Dispatchers.Default) {
                                    readFeedbackAttachment(file)
                                },
                            )
                        }
                    },
                )
            }
        }
        pendingDestination?.let { (_, onResult) ->
            key(onResult) {
                FeedbackDownloadDestinationPickerDialog(
                    onConfirm = { directory ->
                        pendingDestination = null
                        onResult(Result.success(FeedbackDownloadDestination(directory)))
                    },
                    onDismiss = {
                        pendingDestination = null
                        onResult(Result.success(null))
                    },
                )
            }
        }
    }
}

@Composable
internal fun FeedbackDownloadDestinationPickerDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    openPath: String = PathUtils.getHomePath(),
) {
    var selectedDirectory by remember { mutableStateOf<FileSimpleInfo?>(null) }
    FullSizeFileSelectorDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = AppStrings.ui_please_select_directory_save,
                style = MaterialTheme.typography.headlineSmall,
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(AppStrings.ui_cancel) }
        },
        confirmButton = {
            TextButton(
                onClick = { selectedDirectory?.path?.let(onConfirm) },
                enabled = selectedDirectory != null,
            ) { Text(AppStrings.ui_confirm) }
        },
    ) {
        FileSelectorEntryRegion(
            openPath = openPath,
            initialSelectionUiState = FileSelectionUiState(
                selectedDirectory?.let(::listOf).orEmpty(),
            ),
            onFilesSelected = { files -> selectedDirectory = files.lastOrNull() },
            isSingleSelection = true,
            displayConstraints = FeedbackDownloadDestinationConstraints,
        )
    }
}

@Composable
internal fun FeedbackAttachmentPickerDialog(
    onDismiss: () -> Unit,
    onConfirm: (FileSimpleInfo) -> Unit,
    openPath: String = PathUtils.getHomePath(),
) {
    var selectedFile by remember { mutableStateOf<FileSimpleInfo?>(null) }

    FullSizeFileSelectorDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = AppStrings.ui_feedback_upload_attachment,
                style = MaterialTheme.typography.headlineSmall,
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(AppStrings.ui_cancel) }
        },
        confirmButton = {
            TextButton(
                onClick = { selectedFile?.let(onConfirm) },
                enabled = selectedFile != null,
            ) { Text(AppStrings.ui_add) }
        },
    ) {
        FileSelectorEntryRegion(
            openPath = openPath,
            initialSelectionUiState = FileSelectionUiState(
                selectedFile?.let(::listOf).orEmpty(),
            ),
            onFilesSelected = { files -> selectedFile = files.lastOrNull() },
            selectionFilterTypesUiState = FileFilterTypeListUiState(
                listOf(FileFilterType.File),
            ),
            isSingleSelection = true,
            displayConstraints = FeedbackAttachmentDisplayConstraints,
            selectionConstraints = FeedbackAttachmentSelectionConstraints,
        )
    }
}

internal suspend fun readFeedbackAttachment(file: FileSimpleInfo): Result<FeedbackUpload> {
    if (file.isDirectory) {
        return Result.failure(IllegalArgumentException(AppStrings.ui_feedback_attachment_type_unsupported))
    }
    if (file.size > MAX_FEEDBACK_ATTACHMENT_BYTES) {
        return Result.failure(IllegalArgumentException(AppStrings.ui_feedback_attachment_limit))
    }
    if (FeedbackAttachmentSelectionConstraints.evaluate(file) !is
        com.folderspan.ui.components.file.FileSelectorConstraintResult.Allowed
    ) {
        return Result.failure(IllegalArgumentException(AppStrings.ui_feedback_attachment_type_unsupported))
    }
    return runCatching {
        val chunks = mutableListOf<ByteArray>()
        var totalBytes = 0L
        FileUtils.readFileChunks(
            permission = FileAccessPermission.Allowed,
            path = file.path,
            chunkSize = FEEDBACK_ATTACHMENT_READ_CHUNK_BYTES,
        ).collect { chunkResult ->
            val bytes = chunkResult.getOrElse {
                throw IllegalStateException(AppStrings.file_read_failed)
            }.second
            totalBytes += bytes.size
            if (totalBytes > MAX_FEEDBACK_ATTACHMENT_BYTES) {
                throw IllegalArgumentException(AppStrings.ui_feedback_attachment_limit)
            }
            chunks += bytes
        }
        val bytes = ByteArray(totalBytes.toInt())
        var offset = 0
        chunks.forEach { chunk ->
            chunk.copyInto(bytes, destinationOffset = offset)
            offset += chunk.size
        }
        FeedbackUpload(
            fileName = file.name,
            contentType = "",
            bytes = bytes,
        )
    }
}

internal suspend fun saveFeedbackAttachmentDownload(
    directory: String,
    download: FeedbackDownload,
    onProgress: com.folderspan.pro.domain.model.FeedbackTransferProgressCallback = {},
): Result<Boolean> {
    val fileName = sanitizeFeedbackAttachmentFileName(download.fileName) ?: "feedback-attachment.bin"
    val destination = availableFeedbackAttachmentPath(directory, fileName)
    val finalName = destination.substringAfterLast('/').substringAfterLast('\\')
    val temporaryName = availableFeedbackTemporaryName(directory, finalName)
    val separator = PathUtils.getPathSeparator()
    val temporaryPath = directory.trimEnd('/', '\\') + separator + temporaryName
    var moved = false
    return try {
        onProgress(com.folderspan.pro.domain.model.FeedbackTransferProgress(0L, download.bytes.size.toLong()))
        if (download.bytes.isEmpty()) {
            check(FileUtils.createFile(FileAccessPermission.Allowed, temporaryPath).getOrThrow())
        } else {
            var offset = 0
            while (offset < download.bytes.size) {
                val count = minOf(64 * 1024, download.bytes.size - offset)
                val chunk = download.bytes.copyOfRange(offset, offset + count)
                check(
                    FileUtils.writeBytes(
                        permission = FileAccessPermission.Allowed,
                        path = temporaryPath,
                        fileSize = download.bytes.size.toLong(),
                        data = chunk,
                        offset = offset.toLong(),
                    ).getOrThrow(),
                ) { AppStrings.ui_feedback_attachment_save_failed }
                offset += count
                onProgress(
                    com.folderspan.pro.domain.model.FeedbackTransferProgress(
                        offset.toLong(),
                        download.bytes.size.toLong(),
                    ),
                )
            }
        }
        check(
            FileUtils.rename(
                permission = FileAccessPermission.Allowed,
                path = directory,
                oldName = temporaryName,
                newName = finalName,
            ).getOrThrow(),
        ) { AppStrings.ui_feedback_attachment_save_failed }
        moved = true
        Result.success(true)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        Result.failure(error)
    } finally {
        if (!moved && PathUtils.exists(FileAccessPermission.Allowed, temporaryPath)) {
            FileUtils.deleteFile(FileAccessPermission.Allowed, temporaryPath)
        }
    }
}

private fun availableFeedbackTemporaryName(directory: String, finalName: String): String {
    val separator = PathUtils.getPathSeparator()
    val prefix = ".$finalName.part"
    for (index in 0 until MAX_FEEDBACK_DOWNLOAD_NAME_ATTEMPTS) {
        val name = if (index == 0) prefix else "$prefix-$index"
        val path = directory.trimEnd('/', '\\') + separator + name
        if (!PathUtils.exists(FileAccessPermission.Allowed, path)) return name
    }
    throw IllegalStateException(AppStrings.ui_feedback_attachment_save_failed)
}

private fun availableFeedbackAttachmentPath(directory: String, fileName: String): String {
    val separator = PathUtils.getPathSeparator()
    fun path(name: String): String = directory.trimEnd('/', '\\') + separator + name

    val original = path(fileName)
    if (!PathUtils.exists(FileAccessPermission.Allowed, original)) return original

    val extensionStart = fileName.lastIndexOf('.').takeIf { it > 0 } ?: fileName.length
    val baseName = fileName.substring(0, extensionStart)
    val extension = fileName.substring(extensionStart)
    for (index in 1 until MAX_FEEDBACK_DOWNLOAD_NAME_ATTEMPTS) {
        val candidate = path("$baseName ($index)$extension")
        if (!PathUtils.exists(FileAccessPermission.Allowed, candidate)) return candidate
    }
    throw IllegalStateException(AppStrings.ui_feedback_attachment_save_failed)
}
