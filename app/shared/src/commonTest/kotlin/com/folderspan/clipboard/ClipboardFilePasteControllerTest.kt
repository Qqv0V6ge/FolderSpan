package com.folderspan.clipboard

import strings.AppStrings

import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.main.Local
import com.folderspan.ui.state.file.ExternalFileImportFailure
import com.folderspan.ui.state.file.ExternalFileImportResult
import com.folderspan.ui.state.file.ExternalFileImportTarget
import com.folderspan.ui.state.file.ExternalFileImportTargetCapture
import com.folderspan.ui.state.file.PreparedExternalFileBatch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.folderspan.ui.state.file.ExternalFileResourceLease
import com.folderspan.ui.state.file.ExternalFileResourceLeaseRegistry
import com.folderspan.ui.state.file.ExternalFileSkip
import com.folderspan.ui.state.file.ExternalFileSkipReason

class ClipboardFilePasteControllerTest {
    @Test
    fun permissionFailureDoesNotReadClipboard() = runTest {
        var clipboardRead = false

        val result = executeClipboardFilePaste(
            captureTarget = {
                ExternalFileImportTargetCapture(failure = ExternalFileImportFailure.PermissionDenied)
            },
            reader = {
                clipboardRead = true
                PreparedExternalFileBatch()
            },
            importBatch = { _, _ -> error(AppStrings.ui_test_clipboard_file_paste_controller_should_not_import) },
        )

        assertFalse(clipboardRead)
        assertEquals(ExternalFileImportFailure.PermissionDenied, result.failure)
    }

    @Test
    fun importerReceivesTheTargetCapturedBeforeClipboardRead() = runTest {
        val captured = target("/captured")
        val navigatedElsewhere = target("/later")
        var currentTarget = captured
        var importedTarget: ExternalFileImportTarget? = null

        executeClipboardFilePaste(
            captureTarget = { ExternalFileImportTargetCapture(target = currentTarget) },
            reader = {
                currentTarget = navigatedElsewhere
                PreparedExternalFileBatch(files = listOf(file("source.txt", "/source.txt")))
            },
            importBatch = { target, _ ->
                importedTarget = target
                ExternalFileImportResult(taskKeys = listOf(1L), acceptedCount = 1)
            },
        )

        assertSame(captured, importedTarget)
    }

    @Test
    fun unifiedEntryOpensTextEvenWhenCurrentDirectoryCannotAcceptFiles() = runTest {
        val content = ClipboardContent(texts = listOf("https://example.com/a"))
        var opened: ClipboardContent? = null
        val result = executeClipboardOpen(
            captureTarget = { ExternalFileImportTargetCapture(failure = ExternalFileImportFailure.PermissionDenied) },
            readFiles = { PreparedExternalFileBatch() },
            readText = { content },
            importBatch = { _, _ -> error("Text must not be imported as a file") },
            openText = { opened = it },
        )
        assertNull(result)
        assertSame(content, opened)
    }

    @Test
    fun unifiedEntryPrioritizesFilesAndPreservesCapturedDestination() = runTest {
        val captured = target("/captured")
        var currentTarget = captured
        var importedTarget: ExternalFileImportTarget? = null
        executeClipboardOpen(
            captureTarget = { ExternalFileImportTargetCapture(target = currentTarget) },
            readFiles = {
                currentTarget = target("/changed")
                PreparedExternalFileBatch(files = listOf(file("source.txt", "/source.txt")))
            },
            readText = { error("File payload must take priority over text") },
            importBatch = { target, _ ->
                importedTarget = target
                ExternalFileImportResult(acceptedCount = 1)
            },
            openText = { error("File payload must not open a path") },
        )
        assertSame(captured, importedTarget)
    }

    @Test
    fun unifiedEntryReleasesPreparedFilesWhenPermissionIsDenied() = runTest {
        var released = false
        val lease = ExternalFileResourceLease("unified-clipboard-denied")
        ExternalFileResourceLeaseRegistry.register(lease) { released = true }
        val result = executeClipboardOpen(
            captureTarget = { ExternalFileImportTargetCapture(failure = ExternalFileImportFailure.PermissionDenied) },
            readFiles = { PreparedExternalFileBatch(files = listOf(file("a", "/a")), lease = lease) },
            readText = { error("Should not read text") },
            importBatch = { _, _ -> error("Should not import") },
            openText = { error("Should not open") },
        )
        assertEquals(ExternalFileImportFailure.PermissionDenied, result?.failure)
        assertTrue(released)
    }

    @Test
    fun unreadableFilesDoNotFallBackToText() = runTest {
        val result = executeClipboardOpen(
            captureTarget = { ExternalFileImportTargetCapture(target = target("/target")) },
            readFiles = { PreparedExternalFileBatch(skipped = listOf(ExternalFileSkip(ExternalFileSkipReason.Unreadable))) },
            readText = { error("Unreadable files must not become text") },
            importBatch = { _, _ -> ExternalFileImportResult(failure = ExternalFileImportFailure.AllSkipped) },
            openText = { error("Should not open") },
        )
        assertEquals(ExternalFileImportFailure.AllSkipped, result?.failure)
    }

    private fun target(path: String): ExternalFileImportTarget = ExternalFileImportTarget(
        desk = Local(),
        destination = file(path.substringAfterLast('/'), path, isDirectory = true),
        capturedPath = path,
    )

    private fun file(name: String, path: String, isDirectory: Boolean = false) = FileSimpleInfo(
        name = name,
        isDirectory = isDirectory,
        isHidden = false,
        path = path,
        mineType = if (isDirectory) "" else "text/plain",
        size = 1,
        createdDate = 1,
        updatedDate = 1,
        protocol = FileProtocol.Local,
    )
}
