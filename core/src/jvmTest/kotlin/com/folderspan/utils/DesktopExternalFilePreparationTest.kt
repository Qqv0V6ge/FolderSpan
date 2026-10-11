package com.folderspan.utils

import com.folderspan.cleanup.resolveDesktopApplicationDataDirectory
import com.folderspan.data.file.FileProtocol
import com.folderspan.ui.state.file.ExternalFileSkipReason
import java.nio.file.Files
import kotlin.io.path.createDirectory
import kotlin.io.path.createFile
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopExternalFilePreparationTest {
    @Test
    fun dragAndClipboardPreparationKeepsFilesDirectoriesAndDedupeSemantics() {
        val root = Files.createTempDirectory("folderspan-external-preparation")
        try {
            val file = root.resolve("report.txt").createFile().also { path -> path.writeText("report") }
            val directory = root.resolve("folder").createDirectory()
            val missing = root.resolve("missing.txt")

            val batch = prepareDesktopExternalFiles(
                listOf(file.toFile(), file.toFile(), directory.toFile(), missing.toFile())
            )

            assertEquals(listOf("report.txt", "folder"), batch.files.map { item -> item.name })
            assertTrue(batch.files.all { item -> item.protocol == FileProtocol.Local })
            assertEquals(4, batch.representedItemCount)
            assertEquals(
                setOf(ExternalFileSkipReason.Duplicate, ExternalFileSkipReason.Unreadable),
                batch.skipped.map { item -> item.reason }.toSet(),
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun clipboardPreparationRejectsApplicationPrivateDataWithoutReadingIt() {
        val privateFile = resolveDesktopApplicationDataDirectory()
            .resolve("clipboard-import-probe.bin")
            .toFile()
        privateFile.parentFile?.mkdirs()
        privateFile.writeText("should-not-be-imported")
        try {
            val batch = prepareDesktopExternalFiles(listOf(privateFile))

            assertTrue(batch.files.isEmpty())
            assertEquals(listOf(ExternalFileSkipReason.Unsupported), batch.skipped.map { item -> item.reason })
            assertEquals(privateFile.name, batch.skipped.single().displayName)
        } finally {
            privateFile.delete()
        }
    }
}
