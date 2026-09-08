@file:OptIn(ExperimentalWasmJsInterop::class)

package com.folderspan.clipboard

import com.folderspan.browser.BrowserExternalItem
import com.folderspan.ui.state.file.ExternalFileResourceLeaseRegistry
import com.folderspan.ui.state.file.ExternalFileSkipReason
import com.folderspan.utils.WebInMemoryFileStore
import kotlinx.coroutines.test.runTest
import org.w3c.dom.DataTransfer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BrowserClipboardFilePreparationTest {
    @Test
    fun pasteEventPrefersRealFilesAndDoesNotDuplicateImageRepresentations() {
        val file = browserFile("png-bytes", "photo.png", "image/png")
        val items = browserClipboardEventItems(
            browserDataTransfer(
                files = arrayOf(file),
                imageItems = arrayOf(file),
            )
        )

        assertEquals(listOf("photo.png"), items.map { item -> item.relativePath })
    }

    @Test
    fun pasteEventIgnoresTextAndInvalidImagesAndNamesUnnamedImageBlob() {
        val items = browserClipboardEventItems(
            browserDataTransfer(
                files = emptyArray(),
                imageItems = arrayOf(null, browserBlob("png-bytes", "image/png")),
                includeText = true,
            )
        )

        val item = items.single()
        assertTrue(item.relativePath.orEmpty().matches(Regex("clipboard-image-\\d+\\.png")))
        assertEquals("png-bytes".length.toDouble(), item.size)
        assertTrue(
            browserClipboardEventItems(
                browserDataTransfer(files = emptyArray(), imageItems = emptyArray(), includeText = true)
            ).isEmpty()
        )
    }

    @Test
    fun imageBlobIsReadableAndReleasedWithItsClipboardLease() = runTest {
        val content = "png-bytes".encodeToByteArray()
        val batch = prepareBrowserClipboardBatch(
            listOf(
                browserItem(
                    relativePath = "clipboard-image-123.png",
                    size = content.size.toDouble(),
                    lastModified = 123.0,
                    source = browserBlob("png-bytes", "image/png"),
                )
            )
        )
        val lease = assertNotNull(batch.lease)
        val source = batch.files.single()
        val batchRoot = source.path.substringBeforeLast('/')
        try {
            assertEquals("clipboard-image-123.png", source.name)
            assertContentEquals(
                content,
                WebInMemoryFileStore.readExternalFileRange(source.path, 0L, source.size).getOrThrow(),
            )
        } finally {
            ExternalFileResourceLeaseRegistry.releaseProducer(lease.id)
        }
        assertFalse(WebInMemoryFileStore.exists(batchRoot))
    }

    @Test
    fun browserAdapterPreservesDirectoriesAndReportsInvalidDuplicateAndUnreadableItems() {
        val source = browserBlob("image", "image/png")
        val batch = prepareBrowserClipboardBatch(
            listOf(
                browserItem("folder", isDirectory = true, source = source),
                browserItem("folder/empty", isDirectory = true, source = source),
                browserItem("folder/photo.png", size = 5.0, lastModified = 7.0, source = source),
                browserItem("folder/photo.png", size = 5.0, lastModified = 7.0, source = source),
                browserItem("folder/unreadable.txt", size = 1.0, lastModified = 8.0),
                browserItem("../secret.txt", size = 1.0, lastModified = 9.0, source = source),
            )
        )
        val lease = assertNotNull(batch.lease)
        try {
            val root = batch.files.single()
            assertEquals("folder", root.name)
            assertTrue(root.isDirectory)
            assertEquals(
                setOf("empty", "photo.png"),
                WebInMemoryFileStore.list(root.path).getOrThrow().map { item -> item.name }.toSet(),
            )
            assertEquals(
                setOf(
                    ExternalFileSkipReason.Duplicate,
                    ExternalFileSkipReason.Unreadable,
                    ExternalFileSkipReason.InvalidName,
                ),
                batch.skipped.map { item -> item.reason }.toSet(),
            )
            assertEquals(6, batch.representedItemCount)
        } finally {
            ExternalFileResourceLeaseRegistry.releaseProducer(lease.id)
        }
    }

    private fun browserItem(
        relativePath: String,
        isDirectory: Boolean = false,
        size: Double = 0.0,
        lastModified: Double = 0.0,
        source: JsAny? = null,
    ): BrowserExternalItem {
        val item = js("({})")
        item.relativePath = relativePath
        item.isDirectory = isDirectory
        item.size = size
        item.lastModified = lastModified
        item.source = source
        return item.unsafeCast<BrowserExternalItem>()
    }

    private fun browserBlob(content: String, mimeType: String): JsAny {
        val createBlob = js("(function (content, mimeType) { return new Blob([content], { type: mimeType }); })")
        return createBlob(content, mimeType).unsafeCast<JsAny>()
    }

    private fun browserFile(content: String, name: String, mimeType: String): JsAny {
        val createFile = js(
            "(function (content, name, mimeType) { return new File([content], name, { type: mimeType }); })"
        )
        return createFile(content, name, mimeType).unsafeCast<JsAny>()
    }

    private fun browserDataTransfer(
        files: Array<JsAny>,
        imageItems: Array<JsAny?>,
        includeText: Boolean = false,
    ): DataTransfer {
        val createDataTransfer = js(
            """
            (function (files, imageFiles, includeText) {
              var items = imageFiles.map(function (file) {
                return { kind: "file", type: "image/png", getAsFile: function () { return file; } };
              });
              if (includeText) items.push({ kind: "string", type: "text/plain" });
              return { files: files, items: items };
            })
            """
        )
        return createDataTransfer(files, imageItems, includeText).unsafeCast<DataTransfer>()
    }
}
