package com.folderspan.pro.presentation.screen.feedback

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.folderspan.pro.domain.model.FeedbackDownload
import com.folderspan.pro.domain.model.FeedbackUpload
import com.folderspan.pro.domain.model.MAX_FEEDBACK_ATTACHMENT_BYTES
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JFileChooser

@Composable
actual fun rememberFeedbackAttachmentController(): FeedbackAttachmentController = remember {
    object : FeedbackAttachmentController {
        override fun select(onResult: (Result<FeedbackUpload?>) -> Unit) {
            runCatching {
                val chooser = JFileChooser().apply {
                    dialogTitle = strings.AppStrings.ui_feedback_upload_attachment
                    isMultiSelectionEnabled = false
                }
                if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return@runCatching null
                readDesktopFeedbackUpload(chooser.selectedFile.toPath()).getOrThrow()
            }.also(onResult)
        }

        override fun save(download: FeedbackDownload, onResult: (Result<Boolean>) -> Unit) {
            runCatching {
                val suggestedName = sanitizeFeedbackAttachmentFileName(download.fileName) ?: "feedback-attachment.bin"
                val chooser = JFileChooser().apply {
                    dialogTitle = strings.AppStrings.ui_feedback_download_attachment
                    selectedFile = java.io.File(suggestedName)
                }
                if (chooser.showSaveDialog(null) != JFileChooser.APPROVE_OPTION) return@runCatching false
                saveDesktopFeedbackDownload(chooser.selectedFile.toPath(), download).getOrThrow()
            }.also(onResult)
        }
    }
}

internal fun readDesktopFeedbackUpload(path: Path): Result<FeedbackUpload> = runCatching {
    if (Files.size(path) > MAX_FEEDBACK_ATTACHMENT_BYTES) {
        throw IllegalArgumentException(strings.AppStrings.ui_feedback_attachment_limit)
    }
    val fileName = path.fileName?.toString().orEmpty()
    validateFeedbackUpload(
        FeedbackUpload(
            fileName = fileName,
            contentType = Files.probeContentType(path) ?: feedbackMimeType(fileName),
            bytes = Files.readAllBytes(path),
        ),
    ).getOrThrow()
}

internal fun saveDesktopFeedbackDownload(path: Path, download: FeedbackDownload): Result<Boolean> = runCatching {
    Files.write(path, download.bytes)
    true
}

internal actual fun runtimeFeedbackPlatform(): String {
    val osName = System.getProperty("os.name").orEmpty().lowercase()
    return when {
        "win" in osName -> "windows"
        "mac" in osName || "darwin" in osName -> "macos"
        "linux" in osName -> "linux"
        else -> "other"
    }
}
