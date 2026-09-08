package com.folderspan.pro.presentation.screen.feedback

import com.folderspan.pro.domain.model.FeedbackDownload
import com.folderspan.pro.domain.model.FeedbackUpload
import com.folderspan.pro.domain.model.MAX_FEEDBACK_ATTACHMENT_BYTES
import java.nio.file.Files
import java.util.Comparator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FeedbackAttachmentPolicyTest {
    @Test
    fun acceptsSupportedTypeAndSanitizesPath() {
        val result = validateFeedbackUpload(
            FeedbackUpload("../folder/report.pdf", "", byteArrayOf(1, 2)),
        ).getOrThrow()

        assertEquals("report.pdf", result.fileName)
        assertEquals("application/pdf", result.contentType)
    }

    @Test
    fun rejectsUnsupportedTypeAndOversizedFile() {
        assertTrue(validateFeedbackUpload(FeedbackUpload("script.exe", "", byteArrayOf())).isFailure)
        assertTrue(
            validateFeedbackUpload(
                FeedbackUpload("large.txt", "text/plain", ByteArray((MAX_FEEDBACK_ATTACHMENT_BYTES + 1).toInt())),
            ).isFailure,
        )
    }

    @Test
    fun filenameFallbackRemovesPathsAndControls() {
        assertEquals("safe.log", sanitizeFeedbackAttachmentFileName("../bad\u0000/safe.log"))
        assertEquals(null, sanitizeFeedbackAttachmentFileName(".."))
    }

    @Test
    fun desktopAdapterReadsAndSavesSupportedFiles() {
        val directory = Files.createTempDirectory("feedback-attachment-test-")
        try {
            val source = directory.resolve("diagnostic.log")
            Files.write(source, byteArrayOf(1, 2, 3))

            val upload = readDesktopFeedbackUpload(source).getOrThrow()
            assertEquals("diagnostic.log", upload.fileName)
            assertTrue(upload.bytes.contentEquals(byteArrayOf(1, 2, 3)))

            val destination = directory.resolve("saved.log")
            assertTrue(
                saveDesktopFeedbackDownload(
                    destination,
                    FeedbackDownload("saved.log", "text/plain", byteArrayOf(4, 5)),
                ).getOrThrow(),
            )
            assertTrue(Files.readAllBytes(destination).contentEquals(byteArrayOf(4, 5)))
        } finally {
            Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }

    @Test
    fun securityScopedAccessIsBalancedAfterSuccess() {
        var stopCount = 0

        val value = withFeedbackSecurityScopedResource(
            startAccess = { true },
            stopAccess = { stopCount += 1 },
        ) {
            "loaded"
        }

        assertEquals("loaded", value)
        assertEquals(1, stopCount)
    }

    @Test
    fun securityScopedAccessIsBalancedAfterFailure() {
        var stopCount = 0

        assertFailsWith<IllegalStateException> {
            withFeedbackSecurityScopedResource(
                startAccess = { true },
                stopAccess = { stopCount += 1 },
            ) {
                error("read failed")
            }
        }

        assertEquals(1, stopCount)
    }

    @Test
    fun deniedSecurityScopedAccessIsNotStopped() {
        var stopped = false

        val value = withFeedbackSecurityScopedResource(
            startAccess = { false },
            stopAccess = { stopped = true },
        ) {
            "sandboxed"
        }

        assertEquals("sandboxed", value)
        assertFalse(stopped)
    }

    @Test
    fun exportCompletionCleansTemporaryFileExactlyOnce() {
        var cleanupCount = 0
        val results = mutableListOf<Result<Boolean>>()
        val completion = FeedbackExportCompletion(
            cleanup = { cleanupCount += 1 },
            onResult = { results += it },
        )

        completion.finish(Result.success(true))
        completion.finish(Result.success(false))

        assertEquals(1, cleanupCount)
        assertEquals(1, results.size)
        assertTrue(results.single().getOrThrow())
    }

    @Test
    fun exportCancellationCleansTemporaryFile() {
        var cleaned = false
        var saved: Boolean? = null
        val completion = FeedbackExportCompletion(
            cleanup = { cleaned = true },
            onResult = { saved = it.getOrThrow() },
        )

        completion.finish(Result.success(false))

        assertTrue(cleaned)
        assertEquals(false, saved)
    }

    @Test
    fun exportFailureCleansTemporaryFileAndPreservesFailure() {
        var cleaned = false
        var result: Result<Boolean>? = null
        val failure = IllegalStateException("export failed")
        val completion = FeedbackExportCompletion(
            cleanup = { cleaned = true },
            onResult = { result = it },
        )

        completion.finish(Result.failure(failure))

        assertTrue(cleaned)
        assertEquals(failure, result?.exceptionOrNull())
    }

    @Test
    fun exportCleanupFailureIsReported() {
        var result: Result<Boolean>? = null
        val cleanupFailure = IllegalStateException("cleanup failed")
        val completion = FeedbackExportCompletion(
            cleanup = { throw cleanupFailure },
            onResult = { result = it },
        )

        completion.finish(Result.success(true))

        assertEquals(cleanupFailure, result?.exceptionOrNull())
    }
}
