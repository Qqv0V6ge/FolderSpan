@file:OptIn(ExperimentalWasmJsInterop::class, kotlin.io.encoding.ExperimentalEncodingApi::class)

package com.folderspan.pro.presentation.screen.feedback

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.folderspan.pro.domain.model.FeedbackDownload
import com.folderspan.pro.domain.model.FeedbackUpload
import com.folderspan.pro.domain.model.MAX_FEEDBACK_ATTACHMENT_BYTES
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import kotlin.io.encoding.Base64
import kotlin.js.Promise

@Composable
actual fun rememberFeedbackAttachmentController(): FeedbackAttachmentController {
    val scope = rememberCoroutineScope()
    return remember(scope) {
        object : FeedbackAttachmentController {
            override fun select(onResult: (Result<FeedbackUpload?>) -> Unit) {
                scope.launch {
                    val result = runCatching {
                        val picked = pickBrowserFeedbackFile().await<JsAny?>() ?: return@runCatching null
                        validateBrowserFeedbackMetadata(
                            browserFeedbackFileName(picked),
                            browserFeedbackFileSize(picked).toLong(),
                        )
                        validateFeedbackUpload(
                            FeedbackUpload(
                                fileName = browserFeedbackFileName(picked),
                                contentType = browserFeedbackFileType(picked),
                                bytes = Base64.decode(browserFeedbackFileBase64(picked)),
                            ),
                        ).getOrThrow()
                    }
                    onResult(result)
                }
            }

            override fun save(download: FeedbackDownload, onResult: (Result<Boolean>) -> Unit) {
                onResult(
                    runCatching {
                        saveBrowserFeedbackFile(
                            sanitizeFeedbackAttachmentFileName(download.fileName) ?: "feedback-attachment.bin",
                            download.contentType ?: "application/octet-stream",
                            Base64.encode(download.bytes),
                        )
                    },
                )
            }
        }
    }
}

@JsFun(
    """
    () => new Promise((resolve, reject) => {
      try {
        const input = document.createElement('input');
        input.type = 'file';
        input.accept = '.jpg,.jpeg,.png,.webp,.pdf,.txt,.log,image/jpeg,image/png,image/webp,application/pdf,text/plain';
        input.style.display = 'none';
        document.body.appendChild(input);
        let done = false;
        const focusHandler = () => {
          setTimeout(() => {
            if (!done && (!input.files || input.files.length === 0)) finish(null);
          }, 0);
        };
        const finish = (value) => {
          if (done) return;
          done = true;
          window.removeEventListener('focus', focusHandler);
          try { input.remove(); } catch (_) {}
          resolve(value);
        };
        window.addEventListener('focus', focusHandler, { once: true });
        input.oncancel = () => finish(null);
        input.onchange = () => {
          const file = input.files && input.files[0];
          if (!file) { finish(null); return; }
          const extension = String(file.name || '').split('.').pop().toLowerCase();
          const supported = ['jpg', 'jpeg', 'png', 'webp', 'pdf', 'txt', 'log'].includes(extension);
          if (file.size > 10 * 1024 * 1024 || !supported) {
            finish({ name: file.name || 'attachment', type: file.type || '', size: Number(file.size || 0), base64: '' });
            return;
          }
          file.arrayBuffer().then((buffer) => {
            const bytes = new Uint8Array(buffer);
            let binary = '';
            for (let i = 0; i < bytes.length; i += 0x8000) {
              binary += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
            }
            finish({ name: file.name || 'attachment', type: file.type || '', size: Number(file.size || 0), base64: btoa(binary) });
          }, reject);
        };
        input.click();
      } catch (error) { reject(error); }
    })
    """,
)
private external fun pickBrowserFeedbackFile(): Promise<JsAny?>

@JsFun("(value) => String(value && value.name || 'attachment')")
private external fun browserFeedbackFileName(value: JsAny): String

@JsFun("(value) => String(value && value.type || '')")
private external fun browserFeedbackFileType(value: JsAny): String

@JsFun("(value) => Number(value && value.size || 0)")
private external fun browserFeedbackFileSize(value: JsAny): Double

@JsFun("(value) => String(value && value.base64 || '')")
private external fun browserFeedbackFileBase64(value: JsAny): String

@JsFun(
    """
    (fileName, contentType, base64) => {
      const binary = atob(base64 || '');
      const bytes = new Uint8Array(binary.length);
      for (let i = 0; i < binary.length; i += 1) bytes[i] = binary.charCodeAt(i);
      const url = URL.createObjectURL(new Blob([bytes], { type: contentType || 'application/octet-stream' }));
      try {
        const anchor = document.createElement('a');
        anchor.href = url;
        anchor.download = fileName || 'feedback-attachment.bin';
        anchor.style.display = 'none';
        document.body.appendChild(anchor);
        anchor.click();
        anchor.remove();
        return true;
      } finally {
        setTimeout(() => URL.revokeObjectURL(url), 0);
      }
    }
    """,
)
private external fun saveBrowserFeedbackFile(fileName: String, contentType: String, base64: String): Boolean

internal actual fun runtimeFeedbackPlatform(): String = "js"

private fun validateBrowserFeedbackMetadata(fileName: String, size: Long) {
    if (size > MAX_FEEDBACK_ATTACHMENT_BYTES) {
        throw IllegalArgumentException(strings.AppStrings.ui_feedback_attachment_limit)
    }
    if (fileName.substringAfterLast('.', "").lowercase() !in SupportedFeedbackAttachmentExtensions) {
        throw IllegalArgumentException(strings.AppStrings.ui_feedback_attachment_type_unsupported)
    }
}
