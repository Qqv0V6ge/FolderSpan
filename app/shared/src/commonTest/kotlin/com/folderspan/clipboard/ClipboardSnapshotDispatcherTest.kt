package com.folderspan.clipboard

import strings.AppStrings

import com.folderspan.test.ChineseLocalizationTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ClipboardSnapshotDispatcherTest : ChineseLocalizationTest() {
    @Test
    fun existingPathsTakePriorityOverStandaloneUrl() {
        val parsed = parseClipboardContent(
            ClipboardContent(
                texts = listOf("https://example.com/file.zip"),
                filePaths = listOf("/tmp/shared-file"),
            )
        )

        val dispatch = dispatchClipboardTextSnapshot(parsed, resolvedPaths = listOf("/tmp/shared-file"))

        assertEquals(listOf("/tmp/shared-file"), assertIs<ClipboardTextDispatch.ExistingPaths>(dispatch).paths)
    }

    @Test
    fun standaloneUrlBecomesDownloadDraftOnlyWhenNoPathResolved() {
        val parsed = parseClipboardContent(ClipboardContent(texts = listOf("https://example.com/file.zip")))

        val dispatch = dispatchClipboardTextSnapshot(parsed, resolvedPaths = emptyList())

        assertEquals(
            "https://example.com/file.zip",
            assertIs<ClipboardTextDispatch.DownloadUrl>(dispatch).url,
        )
    }

    @Test
    fun unresolvedOrMixedTextBecomesContentFallback() {
        val parsed = parseClipboardContent(
            ClipboardContent(texts = listOf(AppStrings.ui_test_clipboard_snapshot_dispatcher_https_example_com_file_zip))
        )

        val dispatch = dispatchClipboardTextSnapshot(parsed, resolvedPaths = emptyList())

        assertEquals(
            AppStrings.ui_test_clipboard_snapshot_dispatcher_https_example_com_file_zip,
            assertIs<ClipboardTextDispatch.ShowContent>(dispatch).rawText,
        )
    }

    @Test
    fun emptySnapshotDoesNothing() {
        val parsed = parseClipboardContent(ClipboardContent())

        assertIs<ClipboardTextDispatch.Empty>(
            dispatchClipboardTextSnapshot(parsed, resolvedPaths = emptyList())
        )
    }
}
