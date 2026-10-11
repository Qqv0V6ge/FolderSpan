package com.folderspan.clipboard

import com.folderspan.ui.state.file.ExternalFileImportFailure
import com.folderspan.ui.state.file.ExternalFileImportResult
import com.folderspan.ui.state.file.ExternalFileSkip
import com.folderspan.ui.state.file.ExternalFileSkipReason
import kotlin.test.Test
import kotlin.test.assertEquals
import strings.AppStrings

class ClipboardFilePasteFeedbackTest {
    @Test
    fun feedbackCoversEmptyPartialAndTargetFailureWithoutPaths() {
        assertEquals(
            AppStrings.ui_clipboard_file_paste_no_files,
            clipboardFilePasteFeedbackMessage(
                ExternalFileImportResult(failure = ExternalFileImportFailure.NoFiles)
            ),
        )
        assertEquals(
            AppStrings.ui_clipboard_file_paste_target_unavailable,
            clipboardFilePasteFeedbackMessage(
                ExternalFileImportResult(failure = ExternalFileImportFailure.TargetUnavailable)
            ),
        )

        val partialMessage = clipboardFilePasteFeedbackMessage(
            ExternalFileImportResult(
                taskKeys = listOf(1L),
                acceptedCount = 1,
                skipped = listOf(
                    ExternalFileSkip(
                        reason = ExternalFileSkipReason.Unreadable,
                        displayName = "/private/source/secret.txt",
                    )
                ),
            )
        )
        assertEquals(
            AppStrings.ui_clipboard_file_paste_partial_arg0_arg1.format(arg0 = "1", arg1 = "1"),
            partialMessage,
        )
    }
}
