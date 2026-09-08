@file:OptIn(ExperimentalWasmJsInterop::class)

package com.folderspan.ui.effects

import strings.AppStrings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import com.folderspan.browser.BrowserExternalItem
import com.folderspan.browser.stageBrowserExternalItems
import com.folderspan.ui.state.file.FileState
import com.folderspan.ui.state.file.ShareListDropRegistry
import com.folderspan.ui.state.file.ShareListDropResourceRegistry
import com.folderspan.ui.state.file.normalizeShareListDropFiles
import com.folderspan.utils.LogKit
import com.folderspan.utils.WebInMemoryFileStore
import kotlinx.browser.document
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import org.w3c.dom.DataTransfer
import org.w3c.dom.DragEvent
import org.w3c.dom.events.Event
import kotlin.js.Promise
import kotlin.time.Clock

@Composable
fun ExternalFileDropEffect(fileState: FileState) {
    val scope = rememberCoroutineScope()

    DisposableEffect(fileState) {
        val dragOverListener: (Event) -> Unit = { rawEvent ->
            val event = rawEvent.unsafeCast<DragEvent>()
            val dataTransfer = event.dataTransfer
            if (dataTransfer != null && hasFilePayload(dataTransfer)) {
                event.preventDefault()
                event.stopPropagation()
                dataTransfer.dropEffect = "copy"
            }
        }

        val dropListener: (Event) -> Unit = { rawEvent ->
            val event = rawEvent.unsafeCast<DragEvent>()
            val dataTransfer = event.dataTransfer
            if (dataTransfer != null && hasFilePayload(dataTransfer)) {
                event.preventDefault()
                event.stopPropagation()
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    runCatching {
                        val droppedItems = extractDroppedItems(dataTransfer)
                        applyDroppedItems(fileState, droppedItems)
                    }.onFailure { error ->
                        LogKit.e(AppStrings.ui_web_drag_drop_import_failed, error)
                    }
                }
            }
        }

        document.addEventListener("dragover", dragOverListener)
        document.addEventListener("drop", dropListener)
        onDispose {
            document.removeEventListener("dragover", dragOverListener)
            document.removeEventListener("drop", dropListener)
        }
    }
}

private fun hasFilePayload(dataTransfer: DataTransfer): Boolean {
    val items = dataTransfer.items
    for (index in 0 until items.length) {
        val item = dataTransferItemAt(items, index) ?: continue
        if (item.kind == "file") {
            return true
        }
    }
    return dataTransfer.files.length > 0
}

private suspend fun extractDroppedItems(dataTransfer: DataTransfer): List<BrowserExternalItem> {
    val promiseSource = extractDroppedItemsJs(dataTransfer) ?: return emptyList()
    val rawItems: JsAny? = try {
        val promise = promiseSource.unsafeCast<Promise<JsAny?>>()
        promise.await()
    } catch (error: Throwable) {
        LogKit.e(AppStrings.ui_web_drag_drop_parsing_failed, error)
        return emptyList()
    }
    return droppedItemsFromJs(rawItems).toList()
}

private suspend fun applyDroppedItems(fileState: FileState, droppedItems: List<BrowserExternalItem>) {
    if (droppedItems.isEmpty()) {
        LogKit.w(AppStrings.ui_share_drop_no_importable_content)
        return
    }
    val deliverToShareList = ShareListDropRegistry.hasReceiver()
    val dropRootName = ".folderspan-share-drop-${Clock.System.now().toEpochMilliseconds()}"
    val basePath = if (deliverToShareList) "/$dropRootName" else fileState.path.value.ifBlank { "/" }
    if (deliverToShareList) {
        WebInMemoryFileStore.overwriteDirectory("/", dropRootName)
    }

    val staged = stageBrowserExternalItems(basePath, droppedItems)

    if (deliverToShareList) {
        val topLevelFiles = normalizeShareListDropFiles(
            staged.files
        )
        if (topLevelFiles.isEmpty()) {
            WebInMemoryFileStore.delete(basePath)
            LogKit.w(AppStrings.ui_share_drop_no_importable_content)
            return
        }
        if (ShareListDropRegistry.deliver(topLevelFiles)) {
            ShareListDropResourceRegistry.register(topLevelFiles.map { item -> item.path }) {
                WebInMemoryFileStore.delete(basePath)
            }
        } else {
            WebInMemoryFileStore.delete(basePath)
        }
    } else {
        fileState.updateFileAndFolder()
    }
}

private fun droppedItemsFromJs(value: JsAny?): Array<BrowserExternalItem> {
    if (value == null) return emptyArray()
    val jsArray = value.unsafeCast<JsArray<BrowserExternalItem>>()
    return jsArray.toArray()
}

@JsFun(
    """
    (dataTransfer) => {
      const joinRelativePath = (parent, child) => parent ? parent + "/" + child : child;
      const filePayload = (file, relativePath) => ({
          relativePath: relativePath || (file && (file.webkitRelativePath || file.name)) || "",
          isDirectory: false,
          size: file && typeof file.size === "number" ? file.size : 0,
          lastModified: file && typeof file.lastModified === "number" ? file.lastModified : null,
          source: file || null
        });

      const readAllEntries = (reader) => new Promise((resolve) => {
        const entries = [];
        const readBatch = () => {
          reader.readEntries(
            (batch) => {
              if (!batch || batch.length === 0) {
                resolve(entries);
                return;
              }
              entries.push(...batch);
              readBatch();
            },
            () => resolve(entries)
          );
        };
        readBatch();
      });

      const traverseEntry = (entry, parentPath, output) => {
        if (!entry) return Promise.resolve();
        if (entry.isFile) {
          return new Promise((resolve) => {
            entry.file(
              (file) => {
                output.push(filePayload(file, joinRelativePath(parentPath, entry.name || (file && file.name) || "")));
                resolve();
              },
              () => resolve()
            );
          });
        }
        if (entry.isDirectory) {
          const nextParent = joinRelativePath(parentPath, entry.name || "");
          if (nextParent) {
            output.push({
              relativePath: nextParent,
              isDirectory: true,
              size: 0,
              lastModified: null
            });
          }
          const reader = entry.createReader ? entry.createReader() : null;
          if (!reader) return Promise.resolve();
          return readAllEntries(reader).then((children) =>
            Promise.all(children.map((child) => traverseEntry(child, nextParent, output))).then(() => {})
          );
        }
        return Promise.resolve();
      };

      const traverseHandle = (handle, parentPath, output) => {
        if (!handle) return Promise.resolve();
        if (handle.kind === "file") {
          if (!handle.getFile) return Promise.resolve();
          return handle.getFile().then((file) => {
            output.push(filePayload(file, joinRelativePath(parentPath, handle.name || (file && file.name) || "")));
          }).catch(() => {});
        }
        if (handle.kind === "directory") {
          const nextParent = joinRelativePath(parentPath, handle.name || "");
          if (nextParent) {
            output.push({
              relativePath: nextParent,
              isDirectory: true,
              size: 0,
              lastModified: null
            });
          }
          if (!handle.entries) return Promise.resolve();
          return Promise.resolve(handle.entries()).then((iterator) => {
            if (!iterator || typeof iterator.next !== "function") return;
            const iterateEntries = () =>
              iterator.next().then((step) => {
                if (!step || step.done) return;
                const pair = step.value;
                const childHandle = pair && pair.length > 1 ? pair[1] : null;
                return Promise.resolve(traverseHandle(childHandle, nextParent, output)).then(iterateEntries);
              }, () => undefined);
            return iterateEntries();
          }).catch(() => {});
        }
        return Promise.resolve();
      };

      const traverseLegacyItem = (item, output) => {
        const entry = item.webkitGetAsEntry ? item.webkitGetAsEntry() : null;
        if (entry) return traverseEntry(entry, "", output);
        const file = item.getAsFile ? item.getAsFile() : null;
        if (file) output.push(filePayload(file, (file && (file.webkitRelativePath || file.name)) || ""));
        return Promise.resolve();
      };

      if (!dataTransfer) return Promise.resolve([]);

      const output = [];
      const items = dataTransfer.items ? Array.from(dataTransfer.items) : [];
      if (items.length > 0) {
        const tasks = [];
        items.forEach((item) => {
          if (!item || item.kind !== "file") return;
          if (item.getAsFileSystemHandle) {
            tasks.push(
              item.getAsFileSystemHandle()
                .then((handle) => handle ? traverseHandle(handle, "", output) : traverseLegacyItem(item, output))
                .catch(() => traverseLegacyItem(item, output))
            );
            return;
          }
          tasks.push(traverseLegacyItem(item, output));
        });
        return Promise.all(tasks).then(() => {
          if (output.length > 0) return output;
          const files = dataTransfer.files ? Array.from(dataTransfer.files) : [];
          return files.map((file) => filePayload(file, (file && (file.webkitRelativePath || file.name)) || ""));
        });
      }

      const files = dataTransfer.files ? Array.from(dataTransfer.files) : [];
      return Promise.resolve(files.map((file) => filePayload(file, (file && (file.webkitRelativePath || file.name)) || "")));
    }
    """
)
private external fun extractDroppedItemsJs(dataTransfer: DataTransfer): JsAny?
