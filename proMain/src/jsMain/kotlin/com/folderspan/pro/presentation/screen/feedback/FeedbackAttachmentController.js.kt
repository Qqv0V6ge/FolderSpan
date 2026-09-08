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

private external interface BrowserFeedbackFile : JsAny {
    val name: String
    val type: String
    val size: Double
    val base64: String
}

@Composable
actual fun rememberFeedbackAttachmentController(): FeedbackAttachmentController {
    val scope = rememberCoroutineScope()
    return remember(scope) {
        object : FeedbackAttachmentController {
            override fun select(onResult: (Result<FeedbackUpload?>) -> Unit) {
                scope.launch {
                    val result = runCatching {
                        val picked = pickBrowserFeedbackFile().await() ?: return@runCatching null
                        val file = picked.unsafeCast<BrowserFeedbackFile>()
                        validateBrowserFeedbackMetadata(file.name, file.size.toLong())
                        validateFeedbackUpload(
                            FeedbackUpload(file.name, file.type, Base64.decode(file.base64)),
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

private fun pickBrowserFeedbackFile(): Promise<JsAny?> {
    val picker = js(
        """
        (function () {
          return new Promise(function (resolve, reject) {
            try {
              var input = document.createElement('input');
              input.type = 'file';
              input.accept = '.jpg,.jpeg,.png,.webp,.pdf,.txt,.log,image/jpeg,image/png,image/webp,application/pdf,text/plain';
              input.style.display = 'none';
              document.body.appendChild(input);
              var done = false;
              var focusHandler = function () {
                setTimeout(function () {
                  if (!done && (!input.files || input.files.length === 0)) finish(null);
                }, 0);
              };
              function finish(value) {
                if (done) return;
                done = true;
                window.removeEventListener('focus', focusHandler);
                try { input.remove(); } catch (_) {}
                resolve(value);
              }
              window.addEventListener('focus', focusHandler, { once: true });
              input.oncancel = function () { finish(null); };
              input.onchange = function () {
                var file = input.files && input.files[0];
                if (!file) { finish(null); return; }
                var extension = String(file.name || '').split('.').pop().toLowerCase();
                var supported = ['jpg', 'jpeg', 'png', 'webp', 'pdf', 'txt', 'log'].indexOf(extension) >= 0;
                if (file.size > 10 * 1024 * 1024 || !supported) {
                  finish({ name: file.name || 'attachment', type: file.type || '', size: Number(file.size || 0), base64: '' });
                  return;
                }
                file.arrayBuffer().then(function (buffer) {
                  var bytes = new Uint8Array(buffer);
                  var binary = '';
                  for (var i = 0; i < bytes.length; i += 0x8000) {
                    binary += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
                  }
                  finish({ name: file.name || 'attachment', type: file.type || '', size: Number(file.size || 0), base64: btoa(binary) });
                }, reject);
              };
              input.click();
            } catch (error) { reject(error); }
          });
        })
        """,
    )
    return picker().unsafeCast<Promise<JsAny?>>()
}

private fun saveBrowserFeedbackFile(fileName: String, contentType: String, base64: String): Boolean {
    val saver = js(
        """
        (function (fileName, contentType, base64) {
          var binary = atob(base64 || '');
          var bytes = new Uint8Array(binary.length);
          for (var i = 0; i < binary.length; i += 1) bytes[i] = binary.charCodeAt(i);
          var url = URL.createObjectURL(new Blob([bytes], { type: contentType || 'application/octet-stream' }));
          try {
            var anchor = document.createElement('a');
            anchor.href = url;
            anchor.download = fileName || 'feedback-attachment.bin';
            anchor.style.display = 'none';
            document.body.appendChild(anchor);
            anchor.click();
            anchor.remove();
            return true;
          } finally {
            setTimeout(function () { URL.revokeObjectURL(url); }, 0);
          }
        })
        """,
    )
    return saver(fileName, contentType, base64).unsafeCast<Boolean>()
}

internal actual fun runtimeFeedbackPlatform(): String = "js"

private fun validateBrowserFeedbackMetadata(fileName: String, size: Long) {
    if (size > MAX_FEEDBACK_ATTACHMENT_BYTES) {
        throw IllegalArgumentException(strings.AppStrings.ui_feedback_attachment_limit)
    }
    if (fileName.substringAfterLast('.', "").lowercase() !in SupportedFeedbackAttachmentExtensions) {
        throw IllegalArgumentException(strings.AppStrings.ui_feedback_attachment_type_unsupported)
    }
}
