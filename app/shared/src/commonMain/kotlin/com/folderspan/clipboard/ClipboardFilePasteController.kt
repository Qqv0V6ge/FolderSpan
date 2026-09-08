package com.folderspan.clipboard

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.folderspan.ui.state.file.ExternalFileImportFailure
import com.folderspan.ui.state.file.ExternalFileResourceLeaseRegistry
import com.folderspan.ui.state.file.ExternalFileImportResult
import com.folderspan.ui.state.file.ExternalFileImportTarget
import com.folderspan.ui.state.file.ExternalFileImportTargetCapture
import com.folderspan.ui.state.file.FileOperationState
import com.folderspan.ui.state.file.FileState
import com.folderspan.ui.state.file.PreparedExternalFileBatch
import com.folderspan.utils.LogKit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import strings.AppStrings

typealias ClipboardFileBatchReader = suspend () -> PreparedExternalFileBatch

internal suspend fun executeClipboardFilePaste(
    captureTarget: suspend () -> ExternalFileImportTargetCapture,
    reader: ClipboardFileBatchReader,
    importBatch: suspend (ExternalFileImportTarget, PreparedExternalFileBatch) -> ExternalFileImportResult,
): ExternalFileImportResult {
    val capture = captureTarget()
    val target = capture.target ?: return ExternalFileImportResult(
        failure = capture.failure ?: ExternalFileImportFailure.TargetUnavailable,
    )
    val batch = try {
        reader()
    } catch (error: Throwable) {
        if (error is CancellationException) throw error
        LogKit.e(AppStrings.ui_clipboard_file_reading_failed_type_arg0.format(arg0 = (error::class.simpleName.orEmpty()).toString()))
        return ExternalFileImportResult(failure = ExternalFileImportFailure.ReadFailed)
    }
    return importBatch(target, batch)
}

internal suspend fun executeClipboardOpen(
    captureTarget: suspend () -> ExternalFileImportTargetCapture,
    readFiles: ClipboardFileBatchReader,
    readText: suspend () -> ClipboardContent?,
    importBatch: suspend (ExternalFileImportTarget, PreparedExternalFileBatch) -> ExternalFileImportResult,
    openText: (ClipboardContent) -> Unit,
): ExternalFileImportResult? {
    val capture = captureTarget()
    val batch = readFiles()
    try {
        if (batch.files.isEmpty() && batch.skipped.isEmpty()) {
            val content = readText()
            if (content != null && (content.texts.any(String::isNotBlank) || content.filePaths.isNotEmpty())) {
                openText(content)
                return null
            }
            return ExternalFileImportResult(failure = ExternalFileImportFailure.NoFiles)
        }
        val target = capture.target ?: return ExternalFileImportResult(
            failure = capture.failure ?: ExternalFileImportFailure.TargetUnavailable,
        )
        return importBatch(target, batch)
    } finally {
        batch.lease?.let { ExternalFileResourceLeaseRegistry.releaseProducer(it.id) }
    }
}

private object ClipboardFilePasteRequestGate {
    private var active = false

    fun tryEnter(): Boolean {
        if (active) return false
        active = true
        return true
    }

    fun exit() {
        active = false
    }
}

expect suspend fun readClipboardFileBatch(): PreparedExternalFileBatch

@Composable
expect fun PlatformClipboardFilePasteEffect(
    enabled: Boolean,
    onPasteRequest: (ClipboardFileBatchReader) -> Unit,
    onTextPasteRequest: (ClipboardContent) -> Unit,
)

object ClipboardFilePasteFeedbackBus {
    private val mutableEvents = MutableSharedFlow<ExternalFileImportResult>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<ExternalFileImportResult> = mutableEvents

    fun publish(result: ExternalFileImportResult) {
        mutableEvents.tryEmit(result)
    }
}

fun clipboardFilePasteFeedbackMessage(result: ExternalFileImportResult): String = when (result.failure) {
    ExternalFileImportFailure.PermissionDenied -> AppStrings.ui_clipboard_file_paste_permission_denied
    ExternalFileImportFailure.TargetUnavailable -> AppStrings.ui_clipboard_file_paste_target_unavailable
    ExternalFileImportFailure.NoFiles -> AppStrings.ui_clipboard_file_paste_no_files
    ExternalFileImportFailure.AllSkipped -> AppStrings.ui_clipboard_file_paste_all_skipped
    ExternalFileImportFailure.ReadFailed -> AppStrings.ui_clipboard_file_paste_read_failed
    null -> if (result.skipped.isEmpty()) {
        AppStrings.ui_clipboard_file_paste_queued_arg0.format(arg0 = result.acceptedCount.toString())
    } else {
        AppStrings.ui_clipboard_file_paste_partial_arg0_arg1.format(
            arg0 = result.acceptedCount.toString(),
            arg1 = result.skipped.size.toString(),
        )
    }
}

@Stable
class ClipboardFilePasteController internal constructor(
    private val fileState: FileState,
    private val fileOperationState: FileOperationState,
    private val scope: CoroutineScope,
) {
    var isRunning by mutableStateOf(false)
        private set

    fun openFromClipboard(onText: (ClipboardContent) -> Unit) {
        if (isRunning || !ClipboardFilePasteRequestGate.tryEnter()) return
        isRunning = true
        scope.launch {
            try {
                executeClipboardOpen(
                    captureTarget = fileState::captureExternalFileImportTarget,
                    readFiles = ::readClipboardFileBatch,
                    readText = ::readClipboardContent,
                    importBatch = { target, batch ->
                        fileState.pasteExternalFiles(target, batch, fileOperationState)
                    },
                    openText = onText,
                )?.let(ClipboardFilePasteFeedbackBus::publish)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                ClipboardFilePasteFeedbackBus.publish(
                    ExternalFileImportResult(failure = ExternalFileImportFailure.ReadFailed),
                )
            } finally {
                isRunning = false
                ClipboardFilePasteRequestGate.exit()
            }
        }
    }

    fun pasteFromClipboard() {
        pasteFiles(::readClipboardFileBatch)
    }

    fun pasteFiles(reader: ClipboardFileBatchReader) {
        if (isRunning || !ClipboardFilePasteRequestGate.tryEnter()) return
        isRunning = true
        scope.launch {
            try {
                ClipboardFilePasteFeedbackBus.publish(
                    executeClipboardFilePaste(
                        captureTarget = fileState::captureExternalFileImportTarget,
                        reader = reader,
                        importBatch = { target, batch ->
                            fileState.pasteExternalFiles(
                                target = target,
                                batch = batch,
                                fileOperationState = fileOperationState,
                            )
                        },
                    )
                )
            } finally {
                isRunning = false
                ClipboardFilePasteRequestGate.exit()
            }
        }
    }
}

@Composable
fun rememberClipboardFilePasteController(
    fileState: FileState,
    fileOperationState: FileOperationState,
): ClipboardFilePasteController {
    val scope = rememberCoroutineScope()
    return remember(fileState, fileOperationState, scope) {
        ClipboardFilePasteController(fileState, fileOperationState, scope)
    }
}
