@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.folderspan.clipboard

import com.folderspan.ui.state.file.ExternalFileResourceLeaseRegistry
import com.folderspan.ui.state.file.ExternalFileSkipReason
import com.folderspan.utils.FileAccessPermission
import com.folderspan.utils.FileUtils
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class IosClipboardFilePreparationTest {
    @Test
    fun stagesFilesDirectoriesImagesAndPartialFailuresIntoOneLease() = runBlocking {
        val rootPath = "${NSTemporaryDirectory().trimEnd('/')}/folderspan-ios-clipboard-${NSUUID().UUIDString}"
        val fileManager = NSFileManager.defaultManager
        val folderPath = "$rootPath/folder"
        val emptyPath = "$folderPath/empty"
        val imagePath = "$folderPath/clipboard-image-123.png"
        val missingPath = "$rootPath/missing.png"
        try {
            assertTrue(fileManager.createDirectoryAtPath(emptyPath, true, null, null))
            val imageBytes = "png-bytes".encodeToByteArray()
            assertTrue(
                FileUtils.writeBytes(
                    permission = FileAccessPermission.Allowed,
                    path = imagePath,
                    fileSize = imageBytes.size.toLong(),
                    data = imageBytes,
                    offset = 0L,
                ).isSuccess
            )

            val batch = prepareIosClipboardFileBatch(
                providerResponse = IosClipboardFileProviderResponse(
                    urls = listOf(
                        NSURL.fileURLWithPath(folderPath, isDirectory = true),
                        NSURL.fileURLWithPath(missingPath, isDirectory = false),
                    ),
                    representedItemCount = 2,
                    ownsStagedUrls = false,
                ),
                fallbackUrls = emptyList(),
            )
            val lease = assertNotNull(batch.lease)
            val preparedFolder = batch.files.single()
            try {
                assertEquals("folder", preparedFolder.name)
                assertTrue(preparedFolder.isDirectory)
                assertEquals(
                    setOf("empty", "clipboard-image-123.png"),
                    NSFileManager.defaultManager.contentsOfDirectoryAtPath(preparedFolder.path, null)
                        .orEmpty()
                        .map { item -> item.toString() }
                        .toSet(),
                )
                val stagedImage = "${preparedFolder.path}/clipboard-image-123.png"
                assertContentEquals(
                    imageBytes,
                    FileUtils.readFileRange(
                        FileAccessPermission.Allowed,
                        stagedImage,
                        0L,
                        imageBytes.size.toLong(),
                    ).getOrThrow(),
                )
                assertEquals(listOf(ExternalFileSkipReason.Unreadable), batch.skipped.map { it.reason })
                assertEquals(2, batch.representedItemCount)
            } finally {
                ExternalFileResourceLeaseRegistry.releaseProducer(lease.id)
            }
            assertFalse(fileManager.fileExistsAtPath(lease.rootPath.orEmpty()))
        } finally {
            fileManager.removeItemAtPath(rootPath, null)
        }
    }

    @Test
    fun prefersProviderFilesDeduplicatesRepresentationsAndIgnoresTextOnlyInput() = runBlocking {
        val rootPath = "${NSTemporaryDirectory().trimEnd('/')}/folderspan-ios-clipboard-dedupe-${NSUUID().UUIDString}"
        val filePath = "$rootPath/report.txt"
        val fileManager = NSFileManager.defaultManager
        try {
            assertTrue(fileManager.createDirectoryAtPath(rootPath, true, null, null))
            val bytes = "report".encodeToByteArray()
            assertTrue(
                FileUtils.writeBytes(
                    permission = FileAccessPermission.Allowed,
                    path = filePath,
                    fileSize = bytes.size.toLong(),
                    data = bytes,
                    offset = 0L,
                ).isSuccess
            )
            val fileUrl = NSURL.fileURLWithPath(filePath, isDirectory = false)
            val batch = prepareIosClipboardFileBatch(
                providerResponse = IosClipboardFileProviderResponse(
                    urls = listOf(fileUrl, fileUrl),
                    representedItemCount = 2,
                    ownsStagedUrls = false,
                ),
                fallbackUrls = listOf(NSURL.fileURLWithPath("$rootPath/fallback.txt")),
            )
            val lease = assertNotNull(batch.lease)
            try {
                assertEquals(listOf("report.txt"), batch.files.map { item -> item.name })
                assertTrue(batch.skipped.isEmpty())
                assertEquals(2, batch.representedItemCount)
            } finally {
                ExternalFileResourceLeaseRegistry.releaseProducer(lease.id)
            }

            val textOnly = prepareIosClipboardFileBatch(null, emptyList())
            assertTrue(textOnly.files.isEmpty())
            assertTrue(textOnly.skipped.isEmpty())
            assertEquals(0, textOnly.representedItemCount)
        } finally {
            fileManager.removeItemAtPath(rootPath, null)
        }
    }
}
