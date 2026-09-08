@file:OptIn(
    kotlinx.cinterop.BetaInteropApi::class,
    kotlinx.cinterop.ExperimentalForeignApi::class,
)

package com.folderspan.pro.presentation.screen.feedback

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.folderspan.pro.domain.model.FeedbackDownload
import com.folderspan.pro.domain.model.FeedbackUpload
import com.folderspan.pro.domain.model.MAX_FEEDBACK_ATTACHMENT_BYTES
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfURL
import platform.Foundation.writeToFile
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerMode
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UISceneActivationStateForegroundActive
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.UIKit.popoverPresentationController
import platform.darwin.NSObject
import platform.posix.memcpy

@Composable
actual fun rememberFeedbackAttachmentController(): FeedbackAttachmentController = remember {
    IosFeedbackAttachmentController()
}

private class IosFeedbackAttachmentController : FeedbackAttachmentController {
    private var activeDelegate: FeedbackDocumentPickerDelegate? = null

    override fun select(onResult: (Result<FeedbackUpload?>) -> Unit) {
        val host = feedbackTopViewController()
        if (host == null) {
            onResult(Result.failure(IllegalStateException(strings.AppStrings.ui_feedback_attachment_type_unsupported)))
            return
        }
        val picker = UIDocumentPickerViewController(
            documentTypes = listOf("public.jpeg", "public.png", "org.webmproject.webp", "com.adobe.pdf", "public.plain-text"),
            inMode = UIDocumentPickerMode.UIDocumentPickerModeOpen,
        )
        val delegate = FeedbackDocumentPickerDelegate(
            onPicked = { url ->
                activeDelegate = null
                if (url == null) onResult(Result.success(null)) else onResult(readIosFeedbackUpload(url))
            },
        )
        activeDelegate = delegate
        picker.delegate = delegate
        picker.allowsMultipleSelection = false
        picker.popoverPresentationController?.let { popover ->
            popover.sourceView = host.view
            popover.sourceRect = host.view.bounds
        }
        host.presentViewController(picker, animated = true, completion = null)
    }

    override fun save(download: FeedbackDownload, onResult: (Result<Boolean>) -> Unit) {
        val host = feedbackTopViewController()
        if (host == null) {
            onResult(Result.failure(IllegalStateException(strings.AppStrings.ui_feedback_attachment_save_failed)))
            return
        }
        val fileName = sanitizeFeedbackAttachmentFileName(download.fileName) ?: "feedback-attachment.bin"
        val tempPath = "${NSTemporaryDirectory().trimEnd('/')}/${NSUUID().UUIDString()}-$fileName"
        val writeResult = runCatching {
            if (download.bytes.isEmpty()) {
                check(NSFileManager.defaultManager.createFileAtPath(tempPath, contents = null, attributes = null))
            } else {
                val data = download.bytes.usePinned { pinned ->
                    NSData.create(bytes = pinned.addressOf(0), length = download.bytes.size.toULong())
                }
                check(data.writeToFile(tempPath, atomically = true))
            }
            NSURL.fileURLWithPath(tempPath, isDirectory = false)
        }
        val tempUrl = writeResult.getOrElse {
            onResult(Result.failure(it))
            return
        }
        val completion = FeedbackExportCompletion(
            cleanup = {
                val fileManager = NSFileManager.defaultManager
                val removed = fileManager.removeItemAtPath(tempPath, error = null)
                if (!removed && fileManager.fileExistsAtPath(tempPath)) {
                    error(strings.AppStrings.ui_feedback_attachment_save_failed)
                }
            },
            onResult = onResult,
        )
        val picker = UIDocumentPickerViewController(
            forExportingURLs = listOf(tempUrl),
            asCopy = true,
        )
        val delegate = FeedbackDocumentPickerDelegate(
            onPicked = { url ->
                activeDelegate = null
                completion.finish(Result.success(url != null))
            },
        )
        activeDelegate = delegate
        picker.delegate = delegate
        picker.popoverPresentationController?.let { popover ->
            popover.sourceView = host.view
            popover.sourceRect = host.view.bounds
        }
        host.presentViewController(picker, animated = true, completion = null)
    }
}

private class FeedbackDocumentPickerDelegate(
    private val onPicked: (NSURL?) -> Unit,
) : NSObject(), UIDocumentPickerDelegateProtocol {
    override fun documentPicker(
        controller: UIDocumentPickerViewController,
        didPickDocumentsAtURLs: List<*>,
    ) {
        onPicked(didPickDocumentsAtURLs.firstOrNull() as? NSURL)
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
        onPicked(null)
    }
}

private fun readIosFeedbackUpload(url: NSURL): Result<FeedbackUpload?> = runCatching {
    withFeedbackSecurityScopedResource(
        startAccess = url::startAccessingSecurityScopedResource,
        stopAccess = url::stopAccessingSecurityScopedResource,
    ) {
        val data = NSData.dataWithContentsOfURL(url)
            ?: error(strings.AppStrings.ui_feedback_attachment_type_unsupported)
        if (data.length > MAX_FEEDBACK_ATTACHMENT_BYTES.toULong()) {
            throw IllegalArgumentException(strings.AppStrings.ui_feedback_attachment_limit)
        }
        val bytes = ByteArray(data.length.toInt())
        if (bytes.isNotEmpty()) {
            bytes.usePinned { pinned -> memcpy(pinned.addressOf(0), data.bytes, data.length) }
        }
        val name = url.lastPathComponent ?: "attachment"
        validateFeedbackUpload(FeedbackUpload(name, feedbackMimeType(name), bytes)).getOrThrow()
    }
}

private fun feedbackTopViewController(): UIViewController? {
    val application = UIApplication.sharedApplication
    val activeScenes = application.connectedScenes
        .filterIsInstance<UIWindowScene>()
        .filter { it.activationState == UISceneActivationStateForegroundActive }
    val windows = activeScenes.flatMap { it.windows.filterIsInstance<UIWindow>() } +
        application.windows.filterIsInstance<UIWindow>()
    val keyWindow = activeScenes.firstNotNullOfOrNull { it.keyWindow }
        ?: application.keyWindow
        ?: windows.firstOrNull { it.isKeyWindow() }
        ?: windows.firstOrNull()
    var controller = keyWindow?.rootViewController
    while (controller?.presentedViewController != null) controller = controller.presentedViewController
    return controller
}

internal actual fun runtimeFeedbackPlatform(): String = "ios"
