@file:OptIn(ExperimentalWasmJsInterop::class)

package com.folderspan.ui.effects

import strings.AppStrings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import com.folderspan.browser.BrowserExternalItem
import com.folderspan.browser.normalizeBrowserExternalRelativePath
import com.folderspan.browser.readBrowserExternalItemBytes
import com.folderspan.browser.stageBrowserExternalItems
import com.folderspan.service.webui.WebUiAttachment
import com.folderspan.service.webui.WebUiPlatformClient
import com.folderspan.service.mcp.file.FileLocator
import com.folderspan.service.webui.joinWebUiRelative
import com.folderspan.ui.state.file.FileState
import com.folderspan.ui.state.file.FileDropSessionRegistry
import com.folderspan.ui.state.file.ExternalFileResourceLease
import com.folderspan.ui.state.file.ExternalFileResourceLeaseRegistry
import com.folderspan.ui.state.file.PreparedExternalFileBatch
import com.folderspan.ui.state.file.buildPreparedExternalFileBatch
import com.folderspan.ui.state.file.newExternalFileLeaseId
import com.folderspan.ui.state.file.ShareListDropRegistry
import com.folderspan.ui.state.file.ShareListDropResourceRegistry
import com.folderspan.ui.state.file.normalizeShareListDropFiles
import com.folderspan.utils.LogKit
import kotlinx.browser.document
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import org.w3c.dom.DataTransfer
import org.w3c.dom.DragEvent
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.events.Event
import kotlin.js.Promise
import kotlin.time.Clock

@Composable
fun ExternalFileDropEffect(fileState: FileState) {
    val scope = rememberCoroutineScope()
    val operations = org.koin.compose.koinInject<com.folderspan.ui.state.file.FileOperationState>()

    DisposableEffect(fileState) {
        val dragOverListener: (Event) -> Unit = { rawEvent ->
            val event = rawEvent.unsafeCast<DragEvent>()
            val dataTransfer = event.dataTransfer
            if (dataTransfer != null && hasFilePayload(dataTransfer)) {
                moveBrowserFileDrag(event)
                event.preventDefault()
                event.stopPropagation()
                dataTransfer.dropEffect = "copy"
            }
        }

        val dropListener: (Event) -> Unit = listener@{ rawEvent ->
            val event = rawEvent.unsafeCast<DragEvent>()
            val dataTransfer = event.dataTransfer
            if (dataTransfer != null && hasFilePayload(dataTransfer)) {
                event.preventDefault()
                event.stopPropagation()
                moveBrowserFileDrag(event)
                val destination = FileDropSessionRegistry.captureEndpoint()?.let(fileState::captureFileDropDestination)
                FileDropSessionRegistry.end()
                if (destination != null) {
                    val sourcePromise = extractDroppedItemsPromise(dataTransfer)
                    fileState.dropCopyCoordinator.submit(destination) {
                        val items = sourcePromise.await().unsafeCast<Array<BrowserExternalItem>>().toList()
                        prepareBrowserDropCopy(items)
                    }
                    return@listener
                }
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    runCatching {
                        val droppedItems = extractDroppedItems(dataTransfer)
                        applyDroppedItems(fileState, operations, droppedItems)
                    }.onFailure { error ->
                        LogKit.e(AppStrings.ui_web_drag_drop_import_failed, error)
                    }
                }
            }
        }

        val leaveListener: (Event) -> Unit = { rawEvent ->
            val event = rawEvent.unsafeCast<DragEvent>()
            if (event.relatedTarget == null) FileDropSessionRegistry.end()
        }
        document.addEventListener("dragenter", dragOverListener)
        document.addEventListener("dragover", dragOverListener)
        document.addEventListener("dragleave", leaveListener)
        document.addEventListener("drop", dropListener)
        onDispose {
            FileDropSessionRegistry.end()
            document.removeEventListener("dragenter", dragOverListener)
            document.removeEventListener("dragover", dragOverListener)
            document.removeEventListener("dragleave", leaveListener)
            document.removeEventListener("drop", dropListener)
        }
    }
}

private fun moveBrowserFileDrag(event: DragEvent) {
    val canvas = document.querySelector("canvas") as? HTMLCanvasElement
    val rect = canvas?.getBoundingClientRect()
    val horizontal = if (canvas != null && rect != null && rect.width > 0.0) {
        (event.clientX - rect.left) * canvas.width / rect.width
    } else event.clientX.toDouble()
    val vertical = if (canvas != null && rect != null && rect.height > 0.0) {
        (event.clientY - rect.top) * canvas.height / rect.height
    } else event.clientY.toDouble()
    FileDropSessionRegistry.moveExternal(horizontal.toFloat(), vertical.toFloat())
}

private suspend fun prepareBrowserDropCopy(items: List<BrowserExternalItem>): PreparedExternalFileBatch {
    val lease = ExternalFileResourceLease(newExternalFileLeaseId())
    val root = joinWebUiRelative(WebUiAttachment.cachePath, ".folderspan-copy-drop-${lease.id}")
    ExternalFileResourceLeaseRegistry.register(lease) { WebUiPlatformClient.releaseTemporary(root) }
    return try {
        val staged = stageBrowserExternalItems(root, items)
        buildPreparedExternalFileBatch(staged.files, staged.skipped, lease)
    } catch (error: Throwable) {
        ExternalFileResourceLeaseRegistry.releaseProducer(lease.id)
        throw error
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
    val rawItems = runCatching {
        extractDroppedItemsPromise(dataTransfer).await()
    }.onFailure { error ->
        LogKit.e(AppStrings.ui_web_drag_drop_parsing_failed, error)
    }.getOrNull() ?: return emptyList()
    return rawItems.unsafeCast<Array<BrowserExternalItem>>().toList()
}

private suspend fun applyDroppedItems(fileState: FileState, operations: com.folderspan.ui.state.file.FileOperationState, droppedItems: List<BrowserExternalItem>) {
    if (droppedItems.isEmpty()) {
        LogKit.w(AppStrings.ui_share_drop_no_importable_content)
        return
    }
    val deliverToShareList = ShareListDropRegistry.hasReceiver()
    if (!deliverToShareList) {
        val target = fileState.captureExternalFileImportTarget().target ?: error("Import destination unavailable")
        val batch = com.folderspan.clipboard.prepareBrowserClipboardBatch(droppedItems)
        val result = fileState.pasteExternalFiles(target, batch, operations)
        check(result.failure == null) { "Browser file import failed: ${result.failure}" }
        return
    }
    val dropRootName = ".folderspan-share-drop-${Clock.System.now().toEpochMilliseconds()}"
    val basePath = joinWebUiRelative(WebUiAttachment.cachePath, dropRootName)
    val staged = stageBrowserExternalItems(basePath, droppedItems)
    val topLevelFiles = normalizeShareListDropFiles(staged.files)
    if (topLevelFiles.isEmpty()) {
        com.folderspan.service.webui.WebUiPlatformClient.releaseTemporary(basePath)
        LogKit.w(AppStrings.ui_share_drop_no_importable_content)
        return
    }
    if (ShareListDropRegistry.deliver(topLevelFiles)) {
        ShareListDropResourceRegistry.register(topLevelFiles.map { item -> item.path }) {
            com.folderspan.service.webui.WebUiPlatformClient.releaseTemporary(basePath)
        }
    } else {
        com.folderspan.service.webui.WebUiPlatformClient.releaseTemporary(basePath)
    }
}

private fun extractDroppedItemsPromise(dataTransfer: DataTransfer): Promise<JsAny?> {
    val extractor = js(
        """
        (function (dataTransfer) {
          function joinRelativePath(parent, child) {
            return parent ? parent + "/" + child : child;
          }

          function filePayload(file) {
            return fileToPayload(file, (file && (file.webkitRelativePath || file.name)) || "");
          }

          function fileToPayload(file, relativePath) {
            return {
              relativePath: relativePath || (file && (file.webkitRelativePath || file.name)) || "",
              isDirectory: false,
              size: file && typeof file.size === "number" ? file.size : 0,
              lastModified: file && typeof file.lastModified === "number" ? file.lastModified : null,
              source: file || null
            };
          }

          function readAllEntries(reader) {
            return new Promise(function (resolve) {
              var entries = [];
              function readBatch() {
                reader.readEntries(
                  function (batch) {
                    if (!batch || batch.length === 0) {
                      resolve(entries);
                      return;
                    }
                    Array.prototype.push.apply(entries, batch);
                    readBatch();
                  },
                  function () { resolve(entries); }
                );
              }
              readBatch();
            });
          }

          function traverseEntry(entry, parentPath, output) {
            if (!entry) return Promise.resolve();
            if (entry.isFile) {
              return new Promise(function (resolve) {
                entry.file(
                  function (file) {
                    output.push(fileToPayload(file, joinRelativePath(parentPath, entry.name || (file && file.name) || "")));
                    resolve();
                  },
                  function () { resolve(); }
                );
              });
            }
            if (entry.isDirectory) {
              var nextParent = joinRelativePath(parentPath, entry.name || "");
              if (nextParent) {
                output.push({
                  relativePath: nextParent,
                  isDirectory: true,
                  size: 0,
                  lastModified: null
                });
              }
              var reader = entry.createReader ? entry.createReader() : null;
              if (!reader) return Promise.resolve();
              return readAllEntries(reader).then(function (children) {
                var tasks = children.map(function (child) {
                  return traverseEntry(child, nextParent, output);
                });
                return Promise.all(tasks).then(function () {});
              });
            }
            return Promise.resolve();
          }

          function traverseHandle(handle, parentPath, output) {
            if (!handle) return;
            if (handle.kind === "file") {
              if (!handle.getFile) return Promise.resolve();
              return handle.getFile().then(function (file) {
                output.push(fileToPayload(file, joinRelativePath(parentPath, handle.name || (file && file.name) || "")));
              }).catch(function () {});
            }
            if (handle.kind === "directory") {
              var nextParent = joinRelativePath(parentPath, handle.name || "");
              if (nextParent) {
                output.push({
                  relativePath: nextParent,
                  isDirectory: true,
                  size: 0,
                  lastModified: null
                });
              }
              if (!handle.entries) return Promise.resolve();
              return Promise.resolve(handle.entries()).then(function (iterator) {
                if (!iterator || typeof iterator.next !== "function") return;
                function iterateEntries() {
                  return iterator.next().then(function (step) {
                    if (!step || step.done) return;
                    var pair = step.value;
                    var childHandle = pair && pair.length > 1 ? pair[1] : null;
                    return Promise.resolve(traverseHandle(childHandle, nextParent, output)).then(iterateEntries);
                  }, function () { return; });
                }
                return iterateEntries();
              }).catch(function () {});
            }
            return Promise.resolve();
          }

          function traverseLegacyItem(item, output) {
            var entry = item.webkitGetAsEntry ? item.webkitGetAsEntry() : null;
            if (entry) return traverseEntry(entry, "", output);
            var file = item.getAsFile ? item.getAsFile() : null;
            if (file) output.push(filePayload(file));
            return Promise.resolve();
          }

          if (!dataTransfer) return Promise.resolve([]);

          var output = [];
          var items = dataTransfer.items ? Array.from(dataTransfer.items) : [];
          if (items.length > 0) {
            var tasks = [];
            items.forEach(function (item) {
              if (!item || item.kind !== "file") return;
              if (item.getAsFileSystemHandle) {
                tasks.push(
                  item.getAsFileSystemHandle()
                    .then(function (handle) {
                      if (handle) return traverseHandle(handle, "", output);
                      return traverseLegacyItem(item, output);
                    })
                    .catch(function () {
                      return traverseLegacyItem(item, output);
                    })
                );
                return;
              }
              tasks.push(traverseLegacyItem(item, output));
            });
            return Promise.all(tasks).then(function () {
              if (output.length > 0) return output;
              var files = dataTransfer.files ? Array.from(dataTransfer.files) : [];
              return files.map(filePayload);
            });
          }

          var files = dataTransfer.files ? Array.from(dataTransfer.files) : [];
          return Promise.resolve(files.map(filePayload));
        })
        """
    )
    return extractor(dataTransfer).unsafeCast<Promise<JsAny?>>()
}
