package com.folderspan.ui.state.file

import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.service.operation.OperationParallelismConfig
import com.folderspan.service.operation.TraversalEndpointKind
import com.folderspan.service.operation.processItemsAdaptive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

private const val FILE_PROPERTY_PROGRESS_INTERVAL_MILLIS = 100L

internal class FileStatePropertySummarizer(
    private val collectDirectoryEntries: suspend (
        root: FileSimpleInfo,
        ensureRunning: suspend () -> Unit,
        onScanIssues: suspend (Int, List<FilePropertyScanIssue>) -> Unit,
        onEntriesDiscovered: suspend (List<FileSimpleInfo>) -> Unit,
    ) -> List<FileSimpleInfo>,
    private val resolveParallelism: (TraversalEndpointKind, String) -> OperationParallelismConfig,
    private val resolveRuntimeMax: (TraversalEndpointKind, String) -> Int,
    private val releaseTemporaryMemory: suspend () -> Unit = {},
) {
    suspend fun summarizeFileProperties(
        file: FileSimpleInfo,
        onProgress: suspend (FilePropertySummary) -> Unit = {},
    ): Result<FilePropertySummary> {
        if (!file.isDirectory) return summarizeSingleFileProperties(file, onProgress)
        return try {
            summarizeSingleFileProperties(file, onProgress)
        } finally {
            releaseTemporaryMemorySafely()
        }
    }

    private suspend fun summarizeSingleFileProperties(
        file: FileSimpleInfo,
        onProgress: suspend (FilePropertySummary) -> Unit,
    ): Result<FilePropertySummary> {
        return try {
            if (!file.isDirectory) {
                val summary = file.toSelectedFilePropertySummary()
                onProgress(summary)
                return Result.success(summary)
            }

            var summary = FilePropertySummary()
            val summaryMutex = Mutex()
            var lastProgressMillis: Long? = null
            suspend fun reportProgress(force: Boolean = false) {
                val nowMillis = Clock.System.now().toEpochMilliseconds()
                val lastMillis = lastProgressMillis
                if (force || lastMillis == null || nowMillis - lastMillis >= FILE_PROPERTY_PROGRESS_INTERVAL_MILLIS) {
                    lastProgressMillis = nowMillis
                    onProgress(summary)
                }
            }
            collectDirectoryEntries(
                file,
                { currentCoroutineContext().ensureActive() },
                { issueCount, issues ->
                    summaryMutex.withLock {
                        summary = summary.withScanIssues(issueCount, issues)
                        reportProgress()
                    }
                },
            ) { entries ->
                val delta = entries.toFilePropertySummary()
                if (delta.hasContent()) {
                    summaryMutex.withLock {
                        summary += delta
                        reportProgress()
                    }
                }
            }
            summaryMutex.withLock { reportProgress(force = true) }
            Result.success(summary)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    suspend fun summarizeFileProperties(
        files: List<FileSimpleInfo>,
        onProgress: suspend (FilePropertySummary) -> Unit = {},
    ): Result<FilePropertySummary> {
        if (files.none { item -> item.isDirectory }) {
            return summarizeSelectedFileProperties(files, onProgress)
        }
        return try {
            summarizeSelectedFileProperties(files, onProgress)
        } finally {
            releaseTemporaryMemorySafely()
        }
    }

    private suspend fun summarizeSelectedFileProperties(
        files: List<FileSimpleInfo>,
        onProgress: suspend (FilePropertySummary) -> Unit,
    ): Result<FilePropertySummary> {
        return try {
            if (files.isEmpty()) {
                val summary = FilePropertySummary()
                onProgress(summary)
                return Result.success(summary)
            }

            var summary = files.toSelectedFilePropertySummary()
            var firstError: Throwable? = null
            val summaryMutex = Mutex()
            var lastProgressMillis: Long? = null
            suspend fun reportProgress(force: Boolean = false) {
                val nowMillis = Clock.System.now().toEpochMilliseconds()
                val lastMillis = lastProgressMillis
                if (force || lastMillis == null || nowMillis - lastMillis >= FILE_PROPERTY_PROGRESS_INTERVAL_MILLIS) {
                    lastProgressMillis = nowMillis
                    onProgress(summary)
                }
            }
            reportProgress(force = true)

            val directories = files.filter { item -> item.isDirectory }
            if (directories.isNotEmpty()) {
                val directoriesByEndpoint = directories.groupBy { directory ->
                    directory.protocol to directory.protocolId
                }
                for ((endpoint, endpointDirectories) in directoriesByEndpoint) {
                    val (protocol, protocolId) = endpoint
                    val endpointKind = protocol.toTraversalEndpointKind()
                    processItemsAdaptive(
                        items = endpointDirectories,
                        config = resolveParallelism(endpointKind, protocolId),
                        ensureRunning = { currentCoroutineContext().ensureActive() },
                        dynamicMaxParallelismProvider = {
                            resolveRuntimeMax(endpointKind, protocolId)
                        },
                    ) { directory ->
                        try {
                            collectDirectoryEntries(
                                directory,
                                { currentCoroutineContext().ensureActive() },
                                { issueCount, issues ->
                                    summaryMutex.withLock {
                                        summary = summary.withScanIssues(issueCount, issues)
                                        reportProgress()
                                    }
                                },
                            ) { entries ->
                                val delta = entries.toFilePropertySummary()
                                if (!delta.hasContent()) return@collectDirectoryEntries
                                summaryMutex.withLock {
                                    summary += delta
                                    reportProgress()
                                }
                            }
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (error: Throwable) {
                            summaryMutex.withLock {
                                if (firstError == null) firstError = error
                            }
                        }
                    }
                }
            }

            summaryMutex.withLock { reportProgress(force = true) }
            firstError?.let { error -> Result.failure(error) }
                ?: Result.success(summary)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    private suspend fun releaseTemporaryMemorySafely() {
        try {
            releaseTemporaryMemory()
        } catch (_: Throwable) {
            // 内存回收是尽力而为，不能覆盖原始统计结果或取消信号。
        }
    }
}
