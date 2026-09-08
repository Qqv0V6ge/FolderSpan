@file:OptIn(ExperimentalWasmJsInterop::class)

package com.folderspan.clipboard

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import com.folderspan.browser.BrowserExternalItem
import com.folderspan.browser.stageBrowserExternalItems
import com.folderspan.ui.state.file.ExternalFileResourceLease
import com.folderspan.ui.state.file.ExternalFileResourceLeaseRegistry
import com.folderspan.ui.state.file.PreparedExternalFileBatch
import com.folderspan.ui.state.file.buildPreparedExternalFileBatch
import com.folderspan.ui.state.file.newExternalFileLeaseId
import com.folderspan.utils.WebInMemoryFileStore
import kotlinx.browser.document
import kotlinx.coroutines.await
import org.w3c.dom.DataTransfer
import org.w3c.dom.events.Event
import kotlin.js.Promise

private external interface ClipboardPasteEvent : JsAny {
    val clipboardData: DataTransfer?
    fun preventDefault()
    fun stopPropagation()
}

actual suspend fun readClipboardFileBatch(): PreparedExternalFileBatch {
    val promiseSource = readNavigatorClipboardFiles() ?: return PreparedExternalFileBatch()
    val rawItems: JsAny? = try {
        promiseSource.unsafeCast<Promise<JsAny?>>().await<JsAny?>()
    } catch (_: Throwable) {
        null
    }
    if (rawItems == null) return PreparedExternalFileBatch()
    return prepareBrowserClipboardBatch(clipboardItemsFromJs(rawItems))
}

@Composable
actual fun PlatformClipboardFilePasteEffect(
    enabled: Boolean,
    onPasteRequest: (ClipboardFileBatchReader) -> Unit,
    onTextPasteRequest: (ClipboardContent) -> Unit,
) {
    DisposableEffect(enabled, onPasteRequest, onTextPasteRequest) {
        val listener: (Event) -> Unit = listener@{ rawEvent ->
            if (!enabled) return@listener
            val event = rawEvent.unsafeCast<ClipboardPasteEvent>()
            val dataTransfer = event.clipboardData ?: return@listener
            if (isEditablePasteTarget(rawEvent)) {
                return@listener
            }
            if (hasBrowserClipboardFiles(dataTransfer)) {
                val items = browserClipboardEventItems(dataTransfer)
                event.preventDefault()
                event.stopPropagation()
                onPasteRequest {
                    prepareBrowserClipboardBatch(items)
                }
            } else {
                val text = dataTransfer.getData("text/plain").takeIf(String::isNotBlank)
                    ?: return@listener
                event.preventDefault()
                event.stopPropagation()
                onTextPasteRequest(ClipboardContent(texts = listOf(text)))
            }
        }
        document.addEventListener("paste", listener, true)
        onDispose { document.removeEventListener("paste", listener, true) }
    }
}

internal fun prepareBrowserClipboardBatch(items: List<BrowserExternalItem>): PreparedExternalFileBatch {
    if (items.isEmpty()) return PreparedExternalFileBatch()
    val leaseId = newExternalFileLeaseId()
    val rootName = ".folderspan-clipboard-$leaseId"
    val basePath = "/$rootName"
    WebInMemoryFileStore.overwriteDirectory("/", rootName)
    val staged = stageBrowserExternalItems(basePath, items)
    if (staged.files.isEmpty()) {
        WebInMemoryFileStore.delete(basePath)
        return PreparedExternalFileBatch(
            skipped = staged.skipped,
            representedItemCount = items.size,
        )
    }
    val lease = ExternalFileResourceLease(leaseId)
    ExternalFileResourceLeaseRegistry.register(lease) {
        WebInMemoryFileStore.delete(basePath)
    }
    return buildPreparedExternalFileBatch(
        files = staged.files,
        skipped = staged.skipped,
        lease = lease,
        representedItemCount = items.size,
    )
}

internal fun browserClipboardEventItems(dataTransfer: DataTransfer): List<BrowserExternalItem> =
    clipboardItemsFromJs(extractClipboardEventFiles(dataTransfer))

private fun clipboardItemsFromJs(value: JsAny?): List<BrowserExternalItem> {
    if (value == null) return emptyList()
    return value.unsafeCast<JsArray<BrowserExternalItem>>().toArray().toList()
}

@JsFun(
    """
    () => {
      if (!navigator.clipboard || !navigator.clipboard.read) return Promise.resolve([]);
      return navigator.clipboard.read().then((clipboardItems) => {
        const output = [];
        const tasks = [];
        clipboardItems.forEach((clipboardItem, itemIndex) => {
          const types = (clipboardItem.types || []).filter((type) =>
            type !== "text/plain" && type !== "text/html"
          );
          tasks.push(Promise.all(types.map((type) =>
            clipboardItem.getType(type)
              .then((blob) => ({ blob: blob, type: type }))
              .catch(() => null)
          )).then((representations) => {
            let selected = representations.find((representation) =>
              representation && representation.blob instanceof File && representation.blob.name
            );
            if (!selected) {
              selected = representations.find((representation) => {
                const mimeType = representation && (representation.blob.type || representation.type || "");
                return representation && representation.blob && /^image\//i.test(mimeType);
              });
            }
            if (!selected) return;
            const blob = selected.blob;
            const mimeType = blob.type || selected.type || "";
            const name = blob instanceof File && blob.name
              ? blob.name
              : clipboardImageName(mimeType, itemIndex);
            if (!name) return;
            output[itemIndex] = {
              relativePath: name,
              isDirectory: false,
              size: typeof blob.size === "number" ? blob.size : 0,
              lastModified: typeof blob.lastModified === "number" ? blob.lastModified : Date.now(),
              source: blob
            };
          }));
        });
        return Promise.all(tasks).then(() => output.filter(Boolean));

        function clipboardImageName(mimeType, itemIndex) {
          const normalized = String(mimeType || "").toLowerCase().split(";", 1)[0];
          if (!/^image\//.test(normalized)) return null;
          const extensions = {
            "image/png": "png", "image/jpeg": "jpg", "image/jpg": "jpg",
            "image/gif": "gif", "image/webp": "webp", "image/bmp": "bmp",
            "image/x-ms-bmp": "bmp", "image/tiff": "tiff", "image/heic": "heic",
            "image/heif": "heic", "image/avif": "avif", "image/svg+xml": "svg",
            "image/x-icon": "ico", "image/vnd.microsoft.icon": "ico"
          };
          let extension = extensions[normalized];
          if (!extension) {
            extension = normalized.substring(6).replace(/^x-/, "").split("+", 1)[0];
            if (!/^[a-z0-9]{1,8}$/.test(extension)) return null;
          }
          const suffix = itemIndex > 0 ? "-" + (itemIndex + 1) : "";
          return "clipboard-image-" + Date.now() + suffix + "." + extension;
        }
      }).catch(() => []);
    }
    """
)
private external fun readNavigatorClipboardFiles(): JsAny?

@JsFun(
    """
    (dataTransfer) => {
      let files = dataTransfer && dataTransfer.files ? Array.from(dataTransfer.files) : [];
      if (files.length === 0 && dataTransfer && dataTransfer.items) {
        files = Array.from(dataTransfer.items)
          .filter((item) => item && item.kind === "file" && /^image\//i.test(item.type || ""))
          .map((item) => item.getAsFile())
          .filter(Boolean);
      }
      return files.map((file, itemIndex) => {
        const mimeType = file && file.type ? String(file.type).toLowerCase().split(";", 1)[0] : "";
        let extension = ({
          "image/png": "png", "image/jpeg": "jpg", "image/jpg": "jpg",
          "image/gif": "gif", "image/webp": "webp", "image/bmp": "bmp",
          "image/x-ms-bmp": "bmp", "image/tiff": "tiff", "image/heic": "heic",
          "image/heif": "heic", "image/avif": "avif", "image/svg+xml": "svg",
          "image/x-icon": "ico", "image/vnd.microsoft.icon": "ico"
        })[mimeType];
        if (!extension && /^image\//.test(mimeType)) {
          extension = mimeType.substring(6).replace(/^x-/, "").split("+", 1)[0];
          if (!/^[a-z0-9]{1,8}$/.test(extension)) extension = null;
        }
        const suffix = itemIndex > 0 ? "-" + (itemIndex + 1) : "";
        const fallbackName = extension
          ? "clipboard-image-" + Date.now() + suffix + "." + extension
          : "clipboard-file" + suffix;
        return {
          relativePath: (file && file.name) || fallbackName,
          isDirectory: false,
          size: file && typeof file.size === "number" ? file.size : 0,
          lastModified: file && typeof file.lastModified === "number" ? file.lastModified : Date.now(),
          source: file || null
        };
      });
    }
    """
)
private external fun extractClipboardEventFiles(dataTransfer: DataTransfer): JsAny

@JsFun(
    """
    (value) => {
      if (!value) return false;
      if (value.files && value.files.length > 0) return true;
      return !!(value.items && Array.from(value.items).some((item) =>
        item && item.kind === "file" && /^image\//i.test(item.type || "")
      ));
    }
    """
)
private external fun hasBrowserClipboardFiles(dataTransfer: DataTransfer): Boolean

@JsFun(
    """
    (event) => {
      const target = event && event.target;
      if (!target) return false;
      const tag = String(target.tagName || "").toLowerCase();
      return tag === "input" || tag === "textarea" || !!target.isContentEditable;
    }
    """
)
private external fun isEditablePasteTarget(event: Event): Boolean
