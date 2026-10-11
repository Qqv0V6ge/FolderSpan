package com.folderspan.utils

import com.folderspan.ui.state.file.ExternalFileResourceLease
import com.folderspan.ui.state.file.ExternalFileResourceLeaseRegistry
import com.folderspan.ui.state.file.ExternalFileSkipReason
import java.io.File
import java.nio.file.Files
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createFile
import kotlin.io.path.deleteRecursively
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalPathApi::class)
class AndroidClipboardFilePreparationTest {
    @Test
    fun stagesRealFilesAndDirectoriesWhileReportingPartialFailures() {
        val sourceRoot = Files.createTempDirectory("folderspan-android-clipboard-source")
        val photo = sourceRoot.resolve("photo.png").createFile().also { path -> path.writeText("image") }
        val missing = sourceRoot.resolve("missing.txt")
        try {
            val batch = ShareHandler.prepareAndroidClipboardSources(
                sources = listOf(
                    ShareHandler.AndroidClipboardSource(
                        identity = "content://provider/tree/folder",
                        entries = listOf(
                            ShareHandler.AndroidExternalFileEntry("", "folder", isDirectory = true),
                            ShareHandler.AndroidExternalFileEntry("", "folder/empty", isDirectory = true),
                            ShareHandler.AndroidExternalFileEntry(photo.toString(), "folder/photo.png"),
                            ShareHandler.AndroidExternalFileEntry(missing.toString(), "folder/missing.txt"),
                        ),
                    ),
                    ShareHandler.AndroidClipboardSource(
                        identity = "content://provider/unsupported-directory",
                        entries = emptyList(),
                    ),
                ),
                stageFile = ::copyReadableFile,
                stagingRootPath = { leaseId -> sourceRoot.resolve("staging-$leaseId").toString() },
                registerLease = ::registerTestLease,
            )
            val lease = assertNotNull(batch.lease)
            try {
                val folder = batch.files.single()
                assertEquals("folder", folder.name)
                assertTrue(folder.isDirectory)
                assertEquals(
                    setOf("empty", "photo.png"),
                    File(folder.path).listFiles().orEmpty().map { file -> file.name }.toSet(),
                )
                assertContentEquals("image".encodeToByteArray(), File(folder.path, "photo.png").readBytes())
                assertEquals(
                    setOf(ExternalFileSkipReason.Unreadable, ExternalFileSkipReason.Unsupported),
                    batch.skipped.map { item -> item.reason }.toSet(),
                )
                assertEquals(3, batch.representedItemCount)
            } finally {
                ExternalFileResourceLeaseRegistry.releaseProducer(lease.id)
            }
            assertFalse(File(lease.rootPath.orEmpty()).exists())
        } finally {
            sourceRoot.deleteRecursively()
        }
    }

    @Test
    fun ignoresTextOnlyInputAndCollapsesEquivalentSourceRepresentations() {
        val sourceRoot = Files.createTempDirectory("folderspan-android-clipboard-dedupe")
        val source = sourceRoot.resolve("report.txt").createFile().also { path -> path.writeText("report") }
        try {
            val textOnly = ShareHandler.prepareAndroidClipboardSources(emptyList(), ::copyReadableFile)
            assertTrue(textOnly.files.isEmpty())
            assertTrue(textOnly.skipped.isEmpty())

            val representation = ShareHandler.AndroidClipboardSource(
                identity = "content://provider/report",
                entries = listOf(ShareHandler.AndroidExternalFileEntry(source.toString(), "report.txt")),
            )
            val batch = ShareHandler.prepareAndroidClipboardSources(
                sources = listOf(representation, representation),
                stageFile = ::copyReadableFile,
                stagingRootPath = { leaseId -> sourceRoot.resolve("staging-$leaseId").toString() },
                registerLease = ::registerTestLease,
            )
            val lease = assertNotNull(batch.lease)
            try {
                assertEquals(listOf("report.txt"), batch.files.map { item -> item.name })
                assertEquals(1, batch.representedItemCount)
                assertTrue(batch.skipped.isEmpty())
            } finally {
                ExternalFileResourceLeaseRegistry.releaseProducer(lease.id)
            }
        } finally {
            sourceRoot.deleteRecursively()
        }
    }

    private fun copyReadableFile(sourcePath: String, destination: File): Boolean {
        val source = File(sourcePath)
        if (!source.isFile) return false
        destination.parentFile?.mkdirs()
        source.copyTo(destination)
        return true
    }

    private fun registerTestLease(lease: ExternalFileResourceLease) {
        ExternalFileResourceLeaseRegistry.register(lease) {
            lease.rootPath?.let { rootPath -> File(rootPath).deleteRecursively() }
        }
    }
}
