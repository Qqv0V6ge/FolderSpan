package com.folderspan.ui.components.dialog

import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.pro.domain.model.FeedbackDownload
import com.folderspan.pro.domain.model.MAX_FEEDBACK_ATTACHMENT_BYTES
import com.folderspan.pro.presentation.screen.feedback.LocalFeedbackAttachmentPicker
import com.folderspan.pro.presentation.screen.feedback.LocalFeedbackDownloadDestinationPicker
import com.folderspan.ui.components.file.FileSelectorConstraintResult
import com.folderspan.ui.components.file.FileSelectorConstraintViolation
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CancellationException
import strings.AppStrings
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

@OptIn(ExperimentalTestApi::class)
class FeedbackAttachmentPickerDialogTest {
    @Test
    fun providerOpensFolderSpanFileSelector() = runComposeUiTest {
        setContent {
            MaterialTheme {
                FeedbackAttachmentPickerProvider {
                    val picker = LocalFeedbackAttachmentPicker.current
                    Button(onClick = { picker?.select {} }) {
                        Text("open-app-file-selector")
                    }
                }
            }
        }

        onNodeWithText("open-app-file-selector").performClick()
        onNodeWithText(AppStrings.ui_feedback_upload_attachment).assertIsDisplayed()
        onNodeWithTag(FULL_SIZE_FILE_SELECTOR_DIALOG_LAYOUT_TEST_TAG).assertIsDisplayed()
        onNodeWithText(AppStrings.ui_cancel).assertIsDisplayed()
        onNodeWithText(AppStrings.ui_add).assertIsNotEnabled()
    }

    @Test
    fun uploadConstraintsHideUnsupportedFilesButKeepDirectoriesNavigable() {
        val unsupported = file("/tmp/archive.zip", "archive.zip", 128)
        val directory = unsupported.copy(
            name = "logs",
            path = "/tmp/logs",
            isDirectory = true,
        )

        assertTrue(
            FeedbackAttachmentDisplayConstraints.evaluate(unsupported) is
                FileSelectorConstraintResult.Rejected,
        )
        assertEquals(
            FileSelectorConstraintResult.Allowed,
            FeedbackAttachmentDisplayConstraints.evaluate(directory),
        )
        assertTrue(
            FeedbackAttachmentSelectionConstraints.evaluate(directory) is
                FileSelectorConstraintResult.Rejected,
        )
    }

    @Test
    fun uploadSelectionKeepsOversizedSupportedFileVisibleButRejectsSelection() {
        val oversized = file(
            path = "/tmp/large.log",
            name = "large.log",
            size = MAX_FEEDBACK_ATTACHMENT_BYTES + 1,
        )

        assertEquals(
            FileSelectorConstraintResult.Allowed,
            FeedbackAttachmentDisplayConstraints.evaluate(oversized),
        )
        val selection = FeedbackAttachmentSelectionConstraints.evaluate(oversized)
        assertTrue(selection is FileSelectorConstraintResult.Rejected)
        assertTrue(selection.violation is FileSelectorConstraintViolation.FileTooLarge)
    }

    @Test
    fun downloadDestinationHidesFilesAndOnlyShowsDirectories() {
        val notes = file("/tmp/notes.txt", "notes.txt", 8)
        val directory = notes.copy(
            name = "logs",
            path = "/tmp/logs",
            isDirectory = true,
        )

        assertTrue(
            FeedbackDownloadDestinationConstraints.evaluate(notes) is
                FileSelectorConstraintResult.Rejected,
        )
        assertEquals(
            FileSelectorConstraintResult.Allowed,
            FeedbackDownloadDestinationConstraints.evaluate(directory),
        )
    }

    @Test
    fun downloadDestinationPickerIsFullSizeAndCancelDoesNotStartTransfer() = runComposeUiTest {
        var selected = false
        setContent {
            MaterialTheme {
                FeedbackAttachmentPickerProvider {
                    val picker = LocalFeedbackDownloadDestinationPicker.current
                    Button(
                        onClick = {
                            picker?.select("diagnostic.log") { result ->
                                selected = result.getOrThrow() != null
                            }
                        },
                    ) {
                        Text("choose-download-folder")
                    }
                }
            }
        }

        onNodeWithText("choose-download-folder").performClick()
        onNodeWithText(AppStrings.ui_please_select_directory_save).assertIsDisplayed()
        onNodeWithTag(FULL_SIZE_FILE_SELECTOR_DIALOG_LAYOUT_TEST_TAG).assertIsDisplayed()
        onNodeWithText(AppStrings.ui_confirm).assertIsEnabled()
        assertFalse(selected)
        onNodeWithText(AppStrings.ui_cancel).performClick()
        assertFalse(selected)
    }

    @Test
    fun saveDownloadUsesSelectedDirectorySanitizesNameAndAvoidsOverwrite() = runTest {
        val directory = Files.createTempDirectory("feedback-download-")
        val existing = directory.resolve("diagnostic.log")
        val expectedBytes = "downloaded".encodeToByteArray()
        try {
            Files.write(existing, "existing".encodeToByteArray())

            val saved = saveFeedbackAttachmentDownload(
                directory = directory.toString(),
                download = FeedbackDownload(
                    fileName = "../diagnostic.log",
                    contentType = "text/plain",
                    bytes = expectedBytes,
                ),
            ).getOrThrow()

            assertTrue(saved)
            assertContentEquals("existing".encodeToByteArray(), Files.readAllBytes(existing))
            assertContentEquals(expectedBytes, Files.readAllBytes(directory.resolve("diagnostic (1).log")))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun cancelledSaveRemovesTemporarySiblingAndLeavesFinalFileUntouched() = runTest {
        val directory = Files.createTempDirectory("feedback-download-cancel-")
        val finalFile = directory.resolve("diagnostic.log")
        Files.write(finalFile, "existing".encodeToByteArray())
        try {
            assertFailsWith<CancellationException> {
                saveFeedbackAttachmentDownload(
                    directory = directory.toString(),
                    download = FeedbackDownload(
                        fileName = "diagnostic.log",
                        contentType = "text/plain",
                        bytes = ByteArray(130_000),
                    ),
                    onProgress = { progress ->
                        if (progress.transferredBytes > 0) throw CancellationException("cancel")
                    },
                )
            }

            assertContentEquals("existing".encodeToByteArray(), Files.readAllBytes(finalFile))
            Files.list(directory).use { files ->
                assertFalse(files.anyMatch { it.fileName.toString().contains(".part") })
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun failedSaveRemovesTemporarySiblingAndDoesNotPublishFinalFile() = runTest {
        val directory = Files.createTempDirectory("feedback-download-failure-")
        try {
            val result = saveFeedbackAttachmentDownload(
                directory = directory.toString(),
                download = FeedbackDownload(
                    fileName = null,
                    contentType = "application/octet-stream",
                    bytes = ByteArray(70_000),
                ),
                onProgress = { progress ->
                    if (progress.transferredBytes > 0) error("simulated save failure")
                },
            )

            assertTrue(result.isFailure)
            assertFalse(Files.exists(directory.resolve("feedback-attachment.bin")))
            Files.list(directory).use { files ->
                assertFalse(files.anyMatch { it.fileName.toString().contains(".part") })
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun selectedAppFileIsReadIntoFeedbackUpload() = runTest {
        val path = Files.createTempFile("feedback-attachment-", ".log")
        val bytes = "diagnostic".encodeToByteArray()
        try {
            Files.write(path, bytes)

            val upload = readFeedbackAttachment(file(path.toString(), path.fileName.toString(), bytes.size.toLong()))
                .getOrThrow()

            assertEquals(path.fileName.toString(), upload.fileName)
            assertEquals("", upload.contentType)
            assertContentEquals(bytes, upload.bytes)
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun changedUnsupportedTypeIsRejectedAgainBeforeReading() = runTest {
        val path = Files.createTempFile("feedback-attachment-", ".zip")
        try {
            Files.write(path, "archive".encodeToByteArray())

            val result = readFeedbackAttachment(
                file(path.toString(), path.fileName.toString(), Files.size(path)),
            )

            assertTrue(result.isFailure)
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun oversizedSelectionIsRejectedBeforeReading() = runTest {
        val result = readFeedbackAttachment(
            file(
                path = "/path/that/does/not/need/to/exist.log",
                name = "too-large.log",
                size = MAX_FEEDBACK_ATTACHMENT_BYTES + 1,
            ),
        )

        assertTrue(result.isFailure)
    }

    private fun file(path: String, name: String, size: Long) = FileSimpleInfo(
        name = name,
        isDirectory = false,
        isHidden = false,
        path = path,
        mineType = "log",
        size = size,
        createdDate = 0,
        updatedDate = 0,
    )
}
