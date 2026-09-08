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
        val file = createBrowserFile("png-bytes", "photo.png", "image/png")
        val items = browserClipboardEventItems(
            createBrowserDataTransfer(
                files = arrayOf(file).toJsArray(),
                imageFiles = arrayOf<JsAny?>(file).toJsArray(),
                includeText = false,
            ).unsafeCast<DataTransfer>()
        )

        assertEquals(listOf("photo.png"), items.map { item -> item.relativePath })
    }

    @Test
    fun pasteEventIgnoresTextAndInvalidImagesAndNamesUnnamedImageBlob() {
        val items = browserClipboardEventItems(
            createBrowserDataTransfer(
                files = emptyArray<JsAny>().toJsArray(),
                imageFiles = arrayOf<JsAny?>(null, createBrowserBlob("png-bytes", "image/png")).toJsArray(),
                includeText = true,
            ).unsafeCast<DataTransfer>()
        )

        val item = items.single()
        assertTrue(item.relativePath.orEmpty().matches(Regex("clipboard-image-\\d+\\.png")))
        assertEquals("png-bytes".length.toDouble(), item.size)
        assertTrue(
            browserClipboardEventItems(
                createBrowserDataTransfer(
                    files = emptyArray<JsAny>().toJsArray(),
                    imageFiles = emptyArray<JsAny?>().toJsArray(),
                    includeText = true,
                ).unsafeCast<DataTransfer>()
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
                    source = createBrowserBlob("png-bytes", "image/png"),
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
        val source = createBrowserBlob("image", "image/png")
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
    ): BrowserExternalItem = createBrowserItem(
        relativePath,
        isDirectory,
        size,
        lastModified,
        source,
    ).unsafeCast<BrowserExternalItem>()
}

@JsFun("(content, mimeType) => new Blob([content], { type: mimeType })")
private external fun createBrowserBlob(content: String, mimeType: String): JsAny

@JsFun("(content, name, mimeType) => new File([content], name, { type: mimeType })")
private external fun createBrowserFile(content: String, name: String, mimeType: String): JsAny

@JsFun(
    """
    (files, imageFiles, includeText) => {
      const items = imageFiles.map((file) => ({
        kind: "file",
        type: "image/png",
        getAsFile: () => file,
      }));
      if (includeText) items.push({ kind: "string", type: "text/plain" });
      return { files, items };
    }
    """
)
private external fun createBrowserDataTransfer(
    files: JsArray<JsAny>,
    imageFiles: JsArray<JsAny?>,
    includeText: Boolean,
): JsAny

@JsFun(
    """
    (relativePath, isDirectory, size, lastModified, source) => ({
      relativePath,
      isDirectory,
      size,
      lastModified,
      source,
    })
    """
)
private external fun createBrowserItem(
    relativePath: String,
    isDirectory: Boolean,
    size: Double,
    lastModified: Double,
    source: JsAny?,
): JsAny
