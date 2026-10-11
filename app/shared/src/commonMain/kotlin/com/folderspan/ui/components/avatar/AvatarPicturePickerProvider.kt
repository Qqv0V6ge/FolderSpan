package com.folderspan.ui.components.avatar

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.folderspan.data.file.FileFilterType
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.image.decodeImage
import com.folderspan.pro.presentation.screen.profile.AvatarPictureOutcome
import com.folderspan.pro.presentation.screen.profile.AvatarPicturePicker
import com.folderspan.pro.presentation.screen.profile.LocalAvatarPicturePicker
import com.folderspan.ui.components.dialog.FullSizeFileSelectorDialog
import com.folderspan.ui.components.file.FileSelectorEntryRegion
import com.folderspan.ui.components.file.FileSelectorConstraintResult
import com.folderspan.ui.components.file.FileSelectorConstraints
import com.folderspan.ui.components.file.FileSelectorEntryKind
import com.folderspan.ui.components.model.FileFilterTypeListUiState
import com.folderspan.ui.components.model.FileSelectionUiState
import com.folderspan.utils.FileAccessPermission
import com.folderspan.utils.FileUtils
import com.folderspan.utils.PathUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import strings.AppStrings

internal const val MAX_AVATAR_SOURCE_BYTES = 32L * 1024L * 1024L
internal const val AVATAR_OUTPUT_EDGE_PIXELS = 512
private const val AVATAR_SOURCE_READ_CHUNK_BYTES = 256L * 1024L
internal const val AVATAR_JPEG_QUALITY = 85

internal val SupportedAvatarSourceExtensions = setOf("jpg", "jpeg", "png", "webp")

internal val AvatarSourceDisplayConstraints = FileSelectorConstraints(
    allowedExtensions = SupportedAvatarSourceExtensions,
)

internal val AvatarSourceSelectionConstraints = FileSelectorConstraints(
    allowedKinds = setOf(FileSelectorEntryKind.File),
    allowedExtensions = SupportedAvatarSourceExtensions,
    maxSizeBytes = MAX_AVATAR_SOURCE_BYTES,
)

internal fun interface BrowserAvatarSourcePicker {
    fun select(onResult: (BrowserAvatarSourceResult) -> Unit)
}

internal sealed interface BrowserAvatarSourceResult {
    data class Selected(val bytes: ByteArray) : BrowserAvatarSourceResult
    data object Cancelled : BrowserAvatarSourceResult
    data class Failed(val message: String) : BrowserAvatarSourceResult
}

internal expect val browserAvatarSourcePicker: BrowserAvatarSourcePicker?

private sealed interface AvatarPickerStage {
    data class Selecting(val requestId: Long) : AvatarPickerStage
    data class Editing(
        val requestId: Long,
        val image: androidx.compose.ui.graphics.ImageBitmap,
        val isProcessing: Boolean = false,
    ) : AvatarPickerStage
}

@Composable
fun AvatarPicturePickerProvider(content: @Composable () -> Unit) {
    val coordinator = remember { AvatarPictureRequestCoordinator() }
    val scope = rememberCoroutineScope()
    var stage by remember { mutableStateOf<AvatarPickerStage?>(null) }
    var workJob by remember { mutableStateOf<Job?>(null) }

    fun finish(requestId: Long, outcome: AvatarPictureOutcome) {
        if (coordinator.current()?.id != requestId) return
        workJob?.cancel()
        workJob = null
        stage = null
        coordinator.finish(requestId, outcome)
    }

    fun decodeSource(requestId: Long, bytes: ByteArray) {
        workJob?.cancel()
        workJob = scope.launch {
            val decoded = withContext(Dispatchers.Default) { decodeImage(bytes) }
            if (coordinator.current()?.id != requestId) return@launch
            decoded.fold(
                onSuccess = { image -> stage = AvatarPickerStage.Editing(requestId, image) },
                onFailure = {
                    finish(
                        requestId,
                        AvatarPictureOutcome.Failed(AppStrings.ui_profile_avatar_source_unreadable),
                    )
                },
            )
        }
    }

    fun handleBrowserResult(requestId: Long, result: BrowserAvatarSourceResult) {
        if (coordinator.current()?.id != requestId) return
        when (result) {
            BrowserAvatarSourceResult.Cancelled -> finish(requestId, AvatarPictureOutcome.Cancelled)
            is BrowserAvatarSourceResult.Failed -> finish(
                requestId,
                AvatarPictureOutcome.Failed(result.message),
            )
            is BrowserAvatarSourceResult.Selected -> {
                if (result.bytes.size > MAX_AVATAR_SOURCE_BYTES) {
                    finish(
                        requestId,
                        AvatarPictureOutcome.Failed(AppStrings.ui_profile_avatar_source_too_large),
                    )
                } else {
                    decodeSource(requestId, result.bytes)
                }
            }
        }
    }

    val picker = remember(coordinator, scope) {
        AvatarPicturePicker { maxOutputBytes, onOutcome ->
            workJob?.cancel()
            stage = null
            val request = coordinator.start(maxOutputBytes, onOutcome)
            val browserPicker = browserAvatarSourcePicker
            if (browserPicker == null) {
                stage = AvatarPickerStage.Selecting(request.id)
            } else {
                browserPicker.select { result -> handleBrowserResult(request.id, result) }
            }
        }
    }

    DisposableEffect(coordinator) {
        onDispose {
            workJob?.cancel()
            workJob = null
            stage = null
            coordinator.dispose()
        }
    }

    CompositionLocalProvider(LocalAvatarPicturePicker provides picker) {
        content()
        when (val currentStage = stage) {
            null -> Unit
            is AvatarPickerStage.Selecting -> AvatarSourcePickerDialog(
                onDismiss = {
                    finish(currentStage.requestId, AvatarPictureOutcome.Cancelled)
                },
                onConfirm = { file ->
                    workJob?.cancel()
                    workJob = scope.launch {
                        val bytes = withContext(Dispatchers.Default) { readAvatarSource(file) }
                        if (coordinator.current()?.id != currentStage.requestId) return@launch
                        bytes.fold(
                            onSuccess = { decodeSource(currentStage.requestId, it) },
                            onFailure = { error ->
                                finish(
                                    currentStage.requestId,
                                    AvatarPictureOutcome.Failed(
                                        error.message ?: AppStrings.ui_profile_avatar_source_unreadable,
                                    ),
                                )
                            },
                        )
                    }
                },
            )
            is AvatarPickerStage.Editing -> AvatarImageEditorDialog(
                source = currentStage.image,
                isProcessing = currentStage.isProcessing,
                onDismiss = {
                    finish(currentStage.requestId, AvatarPictureOutcome.Cancelled)
                },
                onConfirm = { editorState, cropSize ->
                    if (currentStage.isProcessing || cropSize <= 0f) return@AvatarImageEditorDialog
                    stage = currentStage.copy(isProcessing = true)
                    workJob?.cancel()
                    workJob = scope.launch {
                        val encoded = withContext(Dispatchers.Default) {
                            prepareAvatarPicture(
                                source = currentStage.image,
                                state = editorState,
                                cropSize = cropSize,
                                maxOutputBytes = coordinator.current()?.maxOutputBytes
                                    ?: return@withContext Result.failure(CancellationException()),
                            )
                        }
                        if (coordinator.current()?.id != currentStage.requestId) return@launch
                        encoded.fold(
                            onSuccess = { output ->
                                finish(
                                    currentStage.requestId,
                                    AvatarPictureOutcome.Delivered(output),
                                )
                            },
                            onFailure = { error ->
                                finish(
                                    currentStage.requestId,
                                    AvatarPictureOutcome.Failed(
                                        if (error is AvatarOutputTooLargeException) {
                                            AppStrings.ui_profile_avatar_output_too_large
                                        } else {
                                            AppStrings.ui_profile_avatar_format_unsupported
                                        },
                                    ),
                                )
                            },
                        )
                    }
                },
            )
        }
    }
}

@Composable
internal fun AvatarSourcePickerDialog(
    onDismiss: () -> Unit,
    onConfirm: (FileSimpleInfo) -> Unit,
    openPath: String = PathUtils.getHomePath(),
) {
    var selectedFile by remember { mutableStateOf<FileSimpleInfo?>(null) }
    FullSizeFileSelectorDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = AppStrings.ui_profile_avatar_choose,
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
            ) { Text(AppStrings.ui_confirm) }
        },
    ) {
        FileSelectorEntryRegion(
            openPath = openPath,
            initialSelectionUiState = FileSelectionUiState(
                selectedFile?.let(::listOf).orEmpty(),
            ),
            onFilesSelected = { files -> selectedFile = selectedAvatarSource(files) },
            selectionFilterTypesUiState = FileFilterTypeListUiState(listOf(FileFilterType.File)),
            isSingleSelection = true,
            displayConstraints = AvatarSourceDisplayConstraints,
            selectionConstraints = AvatarSourceSelectionConstraints,
        )
    }
}

internal fun selectedAvatarSource(files: List<FileSimpleInfo>): FileSimpleInfo? = files.singleOrNull()

internal suspend fun readAvatarSource(file: FileSimpleInfo): Result<ByteArray> {
    if (AvatarSourceSelectionConstraints.evaluate(file) !is FileSelectorConstraintResult.Allowed) {
        val message = if (file.size > MAX_AVATAR_SOURCE_BYTES) {
            AppStrings.ui_profile_avatar_source_too_large
        } else {
            AppStrings.ui_profile_avatar_format_unsupported
        }
        return Result.failure(IllegalArgumentException(message))
    }
    return try {
        val chunks = mutableListOf<ByteArray>()
        var totalBytes = 0L
        FileUtils.readFileChunks(
            permission = FileAccessPermission.Allowed,
            path = file.path,
            chunkSize = AVATAR_SOURCE_READ_CHUNK_BYTES,
        ).collect { chunkResult ->
            val bytes = chunkResult.getOrElse {
                throw IllegalStateException(AppStrings.ui_profile_avatar_source_unreadable)
            }.second
            totalBytes += bytes.size
            if (totalBytes > MAX_AVATAR_SOURCE_BYTES) {
                throw IllegalArgumentException(AppStrings.ui_profile_avatar_source_too_large)
            }
            chunks += bytes
        }
        val bytes = ByteArray(totalBytes.toInt())
        var offset = 0
        chunks.forEach { chunk ->
            chunk.copyInto(bytes, destinationOffset = offset)
            offset += chunk.size
        }
        Result.success(bytes)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        Result.failure(error)
    }
}
