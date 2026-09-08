package com.folderspan.pro.presentation.screen.feedback

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.folderspan.pro.domain.model.FeedbackDownload
import com.folderspan.pro.domain.model.FeedbackUpload
import com.folderspan.pro.domain.model.MAX_FEEDBACK_ATTACHMENT_BYTES
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
actual fun rememberFeedbackAttachmentController(): FeedbackAttachmentController {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectionCallback by remember { mutableStateOf<((Result<FeedbackUpload?>) -> Unit)?>(null) }
    var saveRequest by remember { mutableStateOf<Pair<FeedbackDownload, (Result<Boolean>) -> Unit>?>(null) }

    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val callback = selectionCallback.also { selectionCallback = null } ?: return@rememberLauncherForActivityResult
        if (uri == null) {
            callback(Result.success(null))
        } else {
            scope.launch {
                val result = withContext(Dispatchers.IO) { readAndroidFeedbackUpload(context.contentResolver, uri) }
                callback(result)
            }
        }
    }
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val request = saveRequest.also { saveRequest = null } ?: return@rememberLauncherForActivityResult
        if (uri == null) {
            request.second(Result.success(false))
        } else {
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri, "wt")?.use { stream ->
                            stream.write(request.first.bytes)
                        } ?: error(strings.AppStrings.ui_feedback_attachment_save_failed)
                        true
                    }
                }
                request.second(result)
            }
        }
    }

    return remember(openLauncher, saveLauncher) {
        object : FeedbackAttachmentController {
            override fun select(onResult: (Result<FeedbackUpload?>) -> Unit) {
                selectionCallback = onResult
                openLauncher.launch(arrayOf("image/jpeg", "image/png", "image/webp", "application/pdf", "text/plain"))
            }

            override fun save(download: FeedbackDownload, onResult: (Result<Boolean>) -> Unit) {
                saveRequest = download to onResult
                saveLauncher.launch(sanitizeFeedbackAttachmentFileName(download.fileName) ?: "feedback-attachment.bin")
            }
        }
    }
}

private fun readAndroidFeedbackUpload(
    resolver: android.content.ContentResolver,
    uri: Uri,
): Result<FeedbackUpload?> = runCatching {
    var name = "attachment"
    var declaredSize: Long? = null
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (nameIndex >= 0) name = cursor.getString(nameIndex) ?: name
            if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) declaredSize = cursor.getLong(sizeIndex)
        }
    }
    if ((declaredSize ?: 0L) > MAX_FEEDBACK_ATTACHMENT_BYTES) {
        throw IllegalArgumentException(strings.AppStrings.ui_feedback_attachment_limit)
    }
    val bytes = resolver.openInputStream(uri)?.use { input ->
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > MAX_FEEDBACK_ATTACHMENT_BYTES) {
                throw IllegalArgumentException(strings.AppStrings.ui_feedback_attachment_limit)
            }
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    } ?: error(strings.AppStrings.ui_feedback_attachment_type_unsupported)
    validateFeedbackUpload(
        FeedbackUpload(
            fileName = name,
            contentType = resolver.getType(uri) ?: feedbackMimeType(name),
            bytes = bytes,
        ),
    ).getOrThrow()
}

internal actual fun runtimeFeedbackPlatform(): String = "android"
