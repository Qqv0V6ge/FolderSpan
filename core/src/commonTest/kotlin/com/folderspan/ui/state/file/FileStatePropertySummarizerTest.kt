package com.folderspan.ui.state.file

import strings.AppStrings

import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.service.operation.OperationParallelismConfig
import com.folderspan.test.runSuspendTest
import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FileStatePropertySummarizerTest {

    @Test
    fun pathErrorsProducePartialSummaryAndDoNotFailStatistics() = runSuspendTest {
        val progress = mutableListOf<FilePropertySummary>()
        val summarizer = summarizer { _, _, onScanIssues, onEntriesDiscovered ->
            onEntriesDiscovered(
                listOf(
                    file("/root/before.txt", size = 3L),
                    directory("/root/unreadable"),
                )
            )
            onScanIssues(
                1,
                listOf(FilePropertyScanIssue("/root/unreadable", AppStrings.message_task_permission_denied)),
            )
            onEntriesDiscovered(listOf(file("/root/after.txt", size = 7L)))
            emptyList()
        }

        val result = summarizer.summarizeFileProperties(directory("/root")) { summary ->
            progress += summary
        }

        assertTrue(result.isSuccess)
        val summary = result.getOrThrow()
        assertEquals(10L, summary.totalSize)
        assertEquals(2, summary.fileCount)
        assertEquals(1, summary.folderCount)
        assertEquals(1, summary.skippedItemCount)
        assertEquals(
            listOf(FilePropertyScanIssue("/root/unreadable", AppStrings.message_task_permission_denied)),
            summary.scanIssues,
        )
        assertEquals(summary, progress.last())
    }

    @Test
    fun pathErrorDetailsAreBoundedWhileTotalCountIsPreserved() = runSuspendTest {
        val issueCount = FILE_PROPERTY_SCAN_ISSUE_LIMIT + 5
        val summarizer = summarizer { _, _, onScanIssues, _ ->
            onScanIssues(
                issueCount,
                List(FILE_PROPERTY_SCAN_ISSUE_LIMIT) { index ->
                    FilePropertyScanIssue("/root/error-$index", AppStrings.ui_read_failed)
                },
            )
            emptyList()
        }

        val summary = summarizer.summarizeFileProperties(directory("/root")).getOrThrow()

        assertEquals(issueCount, summary.skippedItemCount)
        assertEquals(FILE_PROPERTY_SCAN_ISSUE_LIMIT, summary.scanIssues.size)
        assertEquals("/root/error-0", summary.scanIssues.first().path)
        assertEquals("/root/error-${FILE_PROPERTY_SCAN_ISSUE_LIMIT - 1}", summary.scanIssues.last().path)
    }

    @Test
    fun directoryStatisticsReleasesTemporaryMemoryAfterSuccess() = runSuspendTest {
        var releaseCount = 0
        val summarizer = summarizer(
            releaseTemporaryMemory = { releaseCount++ },
        ) { _, _, _, onEntriesDiscovered ->
            onEntriesDiscovered(listOf(file("/root/file.txt", size = 5L)))
            emptyList()
        }

        val summary = summarizer.summarizeFileProperties(directory("/root")).getOrThrow()

        assertEquals(5L, summary.totalSize)
        assertEquals(1, releaseCount)
    }

    @Test
    fun directoryStatisticsReleasesTemporaryMemoryAfterFailureAndCancellation() = runSuspendTest {
        var failureReleaseCount = 0
        val failedSummarizer = summarizer(
            releaseTemporaryMemory = { failureReleaseCount++ },
        ) { _, _, _, _ ->
            throw IllegalStateException(AppStrings.ui_statistics_failed)
        }

        assertTrue(failedSummarizer.summarizeFileProperties(directory("/root")).isFailure)
        assertEquals(1, failureReleaseCount)

        var cancellationReleaseCount = 0
        val cancelledSummarizer = summarizer(
            releaseTemporaryMemory = { cancellationReleaseCount++ },
        ) { _, _, _, _ ->
            throw CancellationException(AppStrings.ui_test_file_state_property_summarizer_count_canceled)
        }

        assertFailsWith<CancellationException> {
            cancelledSummarizer.summarizeFileProperties(directory("/root"))
        }
        assertEquals(1, cancellationReleaseCount)
    }

    @Test
    fun regularFileStatisticsDoesNotRequestGarbageCollection() = runSuspendTest {
        var releaseCount = 0
        val summarizer = summarizer(
            releaseTemporaryMemory = { releaseCount++ },
        ) { _, _, _, _ ->
            error(AppStrings.ui_test_file_state_property_summarizer_ordinary_files_should_not_initiate)
        }

        val summary = summarizer.summarizeFileProperties(file("/root/file.txt", size = 3L)).getOrThrow()

        assertEquals(3L, summary.totalSize)
        assertEquals(0, releaseCount)
    }

    @Test
    fun selectedDirectoriesReleaseTemporaryMemoryOnlyOnceAfterBatchStatistics() = runSuspendTest {
        var traversalCount = 0
        var releaseCount = 0
        val summarizer = summarizer(
            releaseTemporaryMemory = { releaseCount++ },
        ) { _, _, _, _ ->
            traversalCount++
            emptyList()
        }

        val summary = summarizer.summarizeFileProperties(
            listOf(directory("/root/first"), directory("/root/second")),
        ).getOrThrow()

        assertEquals(2, summary.folderCount)
        assertEquals(2, traversalCount)
        assertEquals(1, releaseCount)
    }

    @Test
    fun memoryReleaseFailureDoesNotReplaceStatisticsResult() = runSuspendTest {
        val summarizer = summarizer(
            releaseTemporaryMemory = { error(AppStrings.ui_test_file_state_property_summarizer_platform_refuses_to_recycle) },
        ) { _, _, _, onEntriesDiscovered ->
            onEntriesDiscovered(listOf(file("/root/file.txt", size = 5L)))
            emptyList()
        }

        val result = summarizer.summarizeFileProperties(directory("/root"))

        assertTrue(result.isSuccess)
        assertEquals(5L, result.getOrThrow().totalSize)
    }

    private fun summarizer(
        releaseTemporaryMemory: suspend () -> Unit = {},
        collectDirectoryEntries: suspend (
            FileSimpleInfo,
            suspend () -> Unit,
            suspend (Int, List<FilePropertyScanIssue>) -> Unit,
            suspend (List<FileSimpleInfo>) -> Unit,
        ) -> List<FileSimpleInfo>,
    ): FileStatePropertySummarizer {
        return FileStatePropertySummarizer(
            collectDirectoryEntries = collectDirectoryEntries,
            resolveParallelism = { _, _ ->
                OperationParallelismConfig(
                    initialParallelism = 1,
                    maxParallelism = 1,
                    queueCapacity = 4,
                )
            },
            resolveRuntimeMax = { _, _ -> 1 },
            releaseTemporaryMemory = releaseTemporaryMemory,
        )
    }

    private fun directory(path: String): FileSimpleInfo = file(path = path, isDirectory = true, size = 0L)

    private fun file(
        path: String,
        isDirectory: Boolean = false,
        size: Long = 1L,
    ): FileSimpleInfo {
        return FileSimpleInfo(
            name = path.substringAfterLast('/'),
            isDirectory = isDirectory,
            isHidden = false,
            path = path,
            mineType = if (isDirectory) "" else "text/plain",
            size = size,
            createdDate = 0L,
            updatedDate = 0L,
        )
    }
}
