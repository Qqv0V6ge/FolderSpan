package com.folderspan.pro.presentation.screen.feedback

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import com.folderspan.pro.domain.model.FeedbackDownload
import com.folderspan.pro.domain.model.FeedbackUpload
import com.folderspan.pro.domain.model.FeedbackTransferProgressCallback
import com.folderspan.pro.domain.model.MAX_FEEDBACK_ATTACHMENT_BYTES
import com.folderspan.pro.domain.model.SUPPORTED_FEEDBACK_ATTACHMENT_EXTENSIONS
import strings.AppStrings

interface FeedbackAttachmentController {
    fun select(onResult: (Result<FeedbackUpload?>) -> Unit)
    fun save(download: FeedbackDownload, onResult: (Result<Boolean>) -> Unit)
}

fun interface FeedbackAttachmentPicker {
    fun select(onResult: (Result<FeedbackUpload?>) -> Unit)
}

fun interface FeedbackAttachmentSaver {
    fun save(download: FeedbackDownload, onResult: (Result<Boolean>) -> Unit)
}

data class FeedbackDownloadDestination(
    val directory: String,
    val displayName: String = directory,
)

fun interface FeedbackDownloadDestinationPicker {
    fun select(
        suggestedFileName: String?,
        onResult: (Result<FeedbackDownloadDestination?>) -> Unit,
    )
}

fun interface FeedbackAttachmentTransferSaver {
    suspend fun save(
        destination: FeedbackDownloadDestination,
        download: FeedbackDownload,
        onProgress: FeedbackTransferProgressCallback,
    ): Result<Boolean>
}

val LocalFeedbackAttachmentPicker = staticCompositionLocalOf<FeedbackAttachmentPicker?> { null }
val LocalFeedbackAttachmentSaver = staticCompositionLocalOf<FeedbackAttachmentSaver?> { null }
val LocalFeedbackDownloadDestinationPicker =
    staticCompositionLocalOf<FeedbackDownloadDestinationPicker?> { null }
val LocalFeedbackAttachmentTransferSaver =
    staticCompositionLocalOf<FeedbackAttachmentTransferSaver?> { null }

@Composable
expect fun rememberFeedbackAttachmentController(): FeedbackAttachmentController

internal fun validateFeedbackUpload(upload: FeedbackUpload): Result<FeedbackUpload> {
    if (upload.bytes.size.toLong() > MAX_FEEDBACK_ATTACHMENT_BYTES) {
        return Result.failure(IllegalArgumentException(AppStrings.ui_feedback_attachment_limit))
    }
    val extension = upload.fileName.substringAfterLast('.', "").lowercase()
    if (extension !in SupportedFeedbackAttachmentExtensions) {
        return Result.failure(IllegalArgumentException(AppStrings.ui_feedback_attachment_type_unsupported))
    }
    val safeName = sanitizeFeedbackAttachmentFileName(upload.fileName)
        ?: return Result.failure(IllegalArgumentException(AppStrings.ui_feedback_attachment_type_unsupported))
    return Result.success(upload.copy(fileName = safeName, contentType = upload.contentType.ifBlank { feedbackMimeType(safeName) }))
}

fun sanitizeFeedbackAttachmentFileName(value: String?): String? = value
    ?.replace('\\', '/')
    ?.substringAfterLast('/')
    ?.filterNot { it.code < 32 || it == '\u007F' }
    ?.trim()
    ?.trim('.')
    ?.take(180)
    ?.takeIf { it.isNotBlank() && it != ".." }

internal fun feedbackMimeType(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase()) {
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "webp" -> "image/webp"
    "pdf" -> "application/pdf"
    "txt", "log" -> "text/plain"
    else -> "application/octet-stream"
}

val SupportedFeedbackAttachmentExtensions = SUPPORTED_FEEDBACK_ATTACHMENT_EXTENSIONS

internal fun supportedFeedbackAttachmentExtensionsLabel(): String =
    SupportedFeedbackAttachmentExtensions.joinToString(", ") { it.uppercase() }

internal class FeedbackExportCompletion(
    private val cleanup: () -> Unit,
    private val onResult: (Result<Boolean>) -> Unit,
) {
    private var isFinished = false

    fun finish(result: Result<Boolean>) {
        if (isFinished) return
        isFinished = true
        val cleanupFailure = runCatching(cleanup).exceptionOrNull()
        onResult(cleanupFailure?.let(Result.Companion::failure) ?: result)
    }
}

internal inline fun <T> withFeedbackSecurityScopedResource(
    startAccess: () -> Boolean,
    stopAccess: () -> Unit,
    block: () -> T,
): T {
    val accessed = startAccess()
    return try {
        block()
    } finally {
        if (accessed) stopAccess()
    }
}
