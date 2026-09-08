package com.folderspan.service.http.client

import com.folderspan.utils.FileAccessPermission
import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.extensions.formatDuration
import com.folderspan.extensions.formatPercent
import com.folderspan.extensions.formatSpeed
import com.folderspan.service.operation.TraversalEndpointKind
import com.folderspan.service.operation.TraversalScanProgress
import com.folderspan.service.operation.collectDirectoryEntriesAdaptive
import com.folderspan.service.operation.processItemsAdaptive
import com.folderspan.service.operation.resolveOperationParallelism
import com.folderspan.service.operation.resolveOperationRuntimeMaxParallelism
import com.folderspan.service.operation.resolveTraversalParallelism
import com.folderspan.service.operation.resolveTraversalRuntimeMaxParallelism
import com.folderspan.ui.state.main.*
import com.folderspan.utils.FileUtils
import com.folderspan.utils.LogKit
import com.folderspan.utils.PathUtils
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.util.date.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import strings.AppStrings

internal fun calculateCompletedBlocks(doneBytes: Long, totalBytes: Long, maxChunkSize: Int, totalBlocks: Long): Long {
    if (totalBlocks <= 0L) return 0L
    if (totalBytes <= 0L) return totalBlocks
    val normalizedBytes = doneBytes.coerceIn(0L, totalBytes)
    val blockSize = maxChunkSize.toLong().coerceAtLeast(1L)
    val completedBlocks = (normalizedBytes + blockSize - 1L) / blockSize
    return completedBlocks.coerceIn(0L, totalBlocks)
}

class ShareNetworkRouteClient(
    private val baseUrl: String,
    private val password: String,
    private val userAgent: String,
    private val httpClient: HttpClient = createNoProxyHttpClient {
        expectSuccess = false
    },
    private val json: Json = Json { ignoreUnknownKeys = true },
) : KoinComponent {
    internal val requestRegistry = HttpClientRequestRegistry()
    private val taskState: TaskState by inject()
    private val sessionMutex = Mutex()
    private val authHttpClient = httpClient.config { followRedirects = false }
    private var sessionToken: String? = null

    private suspend fun <T> withBlockingFileIo(block: () -> T): T = withContext(Dispatchers.Default) {
        block()
    }

    /**
     * 断开链接分享会话，并取消未完成请求。
     *
     * @return 是否已触发断开流程。
     */
    fun disconnect(): Boolean {
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            runCatching { requestRegistry.cancelAll(AppStrings.ui_link_share_break) }
        }
        return true
    }

    /**
     * 获取指定路径的文件列表（JSON）。
     *
     * @param path 目标路径。
     * @param requestId 请求标识，用于覆盖/取消同 ID 请求。
     * @param batchId 批次标识，用于批量取消请求。
     * @return 文件列表结果。
     */
    suspend fun getList(
        path: String,
        requestId: String? = null,
        batchId: String? = null,
    ): Result<List<FileSimpleInfo>> {
        return requestRegistry.trackResult(requestId, batchId) {
            try {
                val sessionToken = ensureSessionToken()
                val response = httpClient.get {
                    applyRequestDefaults(path, sessionToken)
                    accept(ContentType.Application.Json)
                }
                if (!response.status.isSuccess()) {
                    val error = readHttpResponseException(response)
                    LogKit.w(AppStrings.ui_link_share_list_failed_http_arg0_arg1.format(arg0 = (response.status.value).toString(), arg1 = (error.message).toString()))
                    return@trackResult Result.failure(error)
                }
                val text = response.bodyAsText()
                val list = json.decodeFromString<List<FileSimpleInfo>>(text)
                Result.success(list)
            } catch (e: CancellationException) {
                Result.failure(e)
            } catch (e: Exception) {
                LogKit.e(AppStrings.ui_link_share_list_exception_arg0.format(arg0 = (e.message).toString()), e)
                Result.failure(e)
            }
        }
    }

    /**
     * 递归遍历指定路径下的所有文件和文件夹。
     *
     * @param path 要遍历的目录路径。
     * @param requestId 可选请求 ID。
     * @param batchId 可选批次 ID，用于批量取消请求。
     * @return Flow，发射每个子目录的文件列表。
     */
    fun traversePath(
        path: String,
        requestId: String? = null,
        batchId: String? = null,
    ): Flow<Result<List<FileSimpleInfo>>> = traversePathInternal(
        path = path,
        requestId = requestId,
        batchId = batchId,
    )

    private fun traversePathInternal(
        path: String,
        requestId: String? = null,
        batchId: String? = null,
        onScanProgress: suspend (TraversalScanProgress) -> Unit = {},
    ): Flow<Result<List<FileSimpleInfo>>> = channelFlow {
        val rootPath = normalizeSharePath(path)

        try {
            collectDirectoryEntriesAdaptive(
                root = FileSimpleInfo.pathFileSimpleInfo(rootPath).withCopy(protocol = FileProtocol.Share),
                config = resolveTraversalParallelism(TraversalEndpointKind.Share),
                pathSeparator = "/",
                ensureRunning = {
                    currentCoroutineContext().ensureActive()
                },
                onScanProgress = onScanProgress,
                onEntriesDiscovered = { entries ->
                    if (entries.isNotEmpty()) {
                        send(Result.success(entries))
                    }
                },
                dynamicMaxParallelismProvider = {
                    resolveTraversalRuntimeMaxParallelism(TraversalEndpointKind.Share)
                },
                requireKnownSymbolicLinkMetadata = true,
            ) { directory ->
                getList(directory.path, requestId = requestId, batchId = batchId)
                    .map { entries ->
                        entries.map { entry ->
                            entry.withCopy(protocol = FileProtocol.Share)
                        }
                    }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LogKit.e(AppStrings.ui_link_sharing_traversal_exception_arg0.format(arg0 = (e.message).toString()), e)
            send(Result.failure(e))
        }
    }

    /**
     * 从链接分享源复制文件/文件夹到本地目标。
     * - 单文件：分块下载写入本地，更新任务进度与日志。
     * - 文件夹：遍历目录树，先创建目录，再并发下载文件。
     *
     * @param task 任务对象，用于进度追踪与取消控制。
     * @param srcFileSimpleInfo 源文件/目录信息。
     * @param destFileSimpleInfo 目标文件/目录信息。
     * @return 复制是否成功。
     */
    suspend fun copyTo(
        task: Task,
        srcFileSimpleInfo: FileSimpleInfo,
        destFileSimpleInfo: FileSimpleInfo,
    ): Result<Boolean> {
        val cancellation = CancellationException(AppStrings.message_task_cancelled)
        fun markCopySuccess(src: FileSimpleInfo, dest: FileSimpleInfo) {
            taskState.removeResult(task, dest.path)
        }

        fun markCopyFailure(src: FileSimpleInfo, dest: FileSimpleInfo, message: String, fallback: String): String {
            return taskState.recordRetryFailure(
                task = task,
                entry = buildCopyRetryEntry(task.taskType, src, dest),
                message = message,
                fallback = fallback,
            )
        }

        suspend fun ensureRunning() {
            if (!taskState.awaitIfPaused(task.key)) {
                if (taskState.isTaskCancelled(task.key)) {
                    throw cancellation
                }
            }
            if (taskState.isTaskCancelled(task.key)) {
                throw cancellation
            }
        }

        taskState.putValue(task, "path", destFileSimpleInfo.path)
        taskState.ensureRuntimeByteMetrics(task, 0L)

        if (srcFileSimpleInfo.isDirectory) {
            return try {
                coroutineScope {
                    val batchId = "share-copy:${task.key}"
                    val rootPath = normalizeSharePath(srcFileSimpleInfo.path)
                    val progressMutex = Mutex()
                    val progressStep = 20
                    val progressIntervalMs = 500L

                    var discovered = 0
                    var processed = 0
                    var successCount = 0
                    var failureCount = 0
                    var firstFailedPath: String? = null
                    var firstError: String? = null
                    var emittedMax = 0
                    var lastMaxUpdateMs = 0L
                    var plannedBytes = 0L
                    val discoveredFiles = mutableListOf<ShareNetworkEntry>()

                    suspend fun updateProgressMax(force: Boolean = false) {
                        progressMutex.withLock {
                            val now = getTimeMillis()
                            if (!force && discovered % progressStep != 0 && now - lastMaxUpdateMs < progressIntervalMs) {
                                return
                            }
                            lastMaxUpdateMs = now
                            emittedMax = discovered
                            taskState.putCopyScanProgress(task, discovered)
                        }
                    }

                    suspend fun updateProgress(success: Boolean, destPath: String, message: String = "") {
                        progressMutex.withLock {
                            processed++
                            taskState.putValue(task, "path", destPath)
                            if (success) {
                                successCount++
                                if (message.isNotEmpty()) {
                                    taskState.putResult(task, destPath, message)
                                } else {
                                    taskState.removeResult(task, destPath)
                                }
                            } else {
                                failureCount++
                                val errorText = message.ifEmpty { AppStrings.ui_copy_failed }
                                if (firstFailedPath == null) {
                                    firstFailedPath = destPath
                                }
                                if (firstError == null) {
                                    firstError = errorText
                                }
                            }
                            val now = getTimeMillis()
                            if (processed > emittedMax) {
                                emittedMax = discovered
                                lastMaxUpdateMs = now
                                taskState.putValue(task, "progressMax", emittedMax.toString())
                            }
                            taskState.putValue(task, "progressCur", processed.toString())
                        }
                    }

                    fun buildEntry(entry: FileSimpleInfo): ShareNetworkEntry? {
                        val relativeEncoded = buildRelativePath(rootPath, entry.path)
                        if (relativeEncoded.isBlank() || relativeEncoded == "/") return null
                        val relativeDecoded =
                            runCatching { relativeEncoded.decodeURLPart() }.getOrDefault(relativeEncoded)
                        return ShareNetworkEntry(entry, relativeDecoded)
                    }

                    taskState.putValue(task, "progressCur", "0")
                    taskState.putValue(task, "progressMax", "0")
                    taskState.putValue(task, "path", destFileSimpleInfo.path)

                    ensureRunning()
                    val rootResult = withBlockingFileIo {
                        FileUtils.createFolder(FileAccessPermission.Allowed, destFileSimpleInfo.path)
                    }
                    if (rootResult.isFailure || !rootResult.getOrDefault(false)) {
                        val message = rootResult.exceptionOrNull().toTaskFailureMessage(AppStrings.ui_folder_creation_failed)
                        markCopyFailure(
                            srcFileSimpleInfo,
                            destFileSimpleInfo,
                            message.ifEmpty { AppStrings.ui_folder_creation_failed },
                            AppStrings.ui_folder_creation_failed,
                        )
                        return@coroutineScope Result.failure(
                            rootResult.exceptionOrNull() ?: Exception(AppStrings.ui_folder_creation_failed)
                        )
                    }

                    suspend fun copyDiscoveredFile(entry: ShareNetworkEntry) {
                        ensureRunning()
                        val destPath = buildDestPath(destFileSimpleInfo.path, entry.relativePath)
                        taskState.putValue(task, "path", destPath)
                        val totalBytes = entry.info.size
                        val totalBlocks = maxOf(1, entry.info.getChunkCount(HttpRouteClientManager.MAX_LENGTH)).toLong()
                        val startMs = getTimeMillis()
                        val rateSampler = TaskProgressRateSampler(initialAt = startMs)
                        var lastLogMs = startMs
                        var doneBytes = 0L
                        taskState.startRuntimeByteItem(task, destPath, totalBytes)
                        if (totalBytes > 0L) {
                            taskState.putResult(
                                task,
                                destPath,
                                AppStrings.task_start_operation.format(operation = AppStrings.ui_download),
                            )
                        }
                        val result = if (entry.info.size == 0L) {
                            withBlockingFileIo { FileUtils.createFile(FileAccessPermission.Allowed, destPath) }
                        } else {
                            writeBytes(
                                path = entry.info.path,
                                batchId = batchId,
                                onChunk = { chunk, offset, _ ->
                                    ensureRunning()
                                    val writeResult = FileUtils.writeBytes(FileAccessPermission.Allowed, destPath, entry.info.size, chunk, offset)
                                    if (writeResult.isFailure || !writeResult.getOrDefault(false)) {
                                        return@writeBytes Result.failure(
                                            writeResult.exceptionOrNull()
                                                ?: Exception(AppStrings.ui_writing_file_failed)
                                        )
                                    }
                                    doneBytes += chunk.size
                                    val completedBlocks = calculateCompletedBlocks(
                                        doneBytes = doneBytes,
                                        totalBytes = totalBytes,
                                        maxChunkSize = HttpRouteClientManager.MAX_LENGTH,
                                        totalBlocks = totalBlocks
                                    )
                                    val now = getTimeMillis()
                                    if (now - lastLogMs >= 1000 || doneBytes >= totalBytes) {
                                        val rateSample = rateSampler.update(
                                            completed = doneBytes,
                                            total = totalBytes,
                                            now = now,
                                            force = totalBytes in 1..doneBytes,
                                        )
                                        val resultText =
                                            AppStrings.message_task_progress_with_metrics.format(
                                                progress = AppStrings.ui_download_progress,
                                                percent = doneBytes.formatPercent(totalBytes),
                                                current = completedBlocks.toString(),
                                                total = totalBlocks.toString(),
                                                speed = rateSample.speedPerSecond.formatSpeed(),
                                                remaining = rateSample.etaMs.formatDuration(),
                                            )
                                        taskState.putResult(task, destPath, resultText)
                                        taskState.putRuntimeByteProgress(task, destPath, doneBytes)
                                        lastLogMs = now
                                    }
                                    Result.success(Unit)
                                }
                            )
                        }
                        val success = result.isSuccess && result.getOrDefault(false)
                        val message = if (success) "" else result.exceptionOrNull().toTaskFailureMessage(AppStrings.ui_copy_failed)
                        if (success) {
                            taskState.finishRuntimeByteItem(task, destPath, totalBytes)
                            markCopySuccess(
                                src = entry.info.copy(
                                    protocol = srcFileSimpleInfo.protocol,
                                    protocolId = srcFileSimpleInfo.protocolId,
                                ),
                                dest = buildRetryFileSimpleInfo(
                                    path = destPath,
                                    protocol = destFileSimpleInfo.protocol,
                                    protocolId = destFileSimpleInfo.protocolId,
                                    isDirectory = false,
                                ),
                            )
                        } else {
                            markCopyFailure(
                                src = entry.info.copy(
                                    protocol = srcFileSimpleInfo.protocol,
                                    protocolId = srcFileSimpleInfo.protocolId,
                                ),
                                dest = buildRetryFileSimpleInfo(
                                    path = destPath,
                                    protocol = destFileSimpleInfo.protocol,
                                    protocolId = destFileSimpleInfo.protocolId,
                                    isDirectory = false,
                                ),
                                message = message,
                                fallback = AppStrings.ui_copy_failed,
                            )
                            taskState.clearRuntimeByteItem(task, destPath)
                        }
                        updateProgress(success, destPath, message)
                    }

                    taskState.putCopyScanProgress(task)
                    var traversalError: String? = null
                    traversePathInternal(
                        path = rootPath,
                        batchId = batchId,
                        onScanProgress = taskState.buildCopyScanProgressPublisher(task),
                    ).collect { result ->
                        ensureRunning()
                        if (result.isFailure) {
                            traversalError = result.exceptionOrNull()?.message?.takeIf { it.isNotBlank() }
                                ?: AppStrings.ui_failed_read_source_directory
                            LogKit.w(AppStrings.ui_link_share_traversal_failed_arg0.format(arg0 = (traversalError)))
                            return@collect
                        }

                        val list = result.getOrDefault(emptyList())
                        if (list.isEmpty()) return@collect

                        val dirEntries = mutableListOf<ShareNetworkEntry>()
                        for (entry in list) {
                            val normalizedPath = normalizeSharePath(entry.path)
                            entry.path = normalizedPath
                            val model = buildEntry(entry) ?: continue
                            if (entry.isDirectory) {
                                discovered++
                                updateProgressMax()
                                dirEntries.add(model)
                            } else {
                                discovered++
                                plannedBytes += entry.size.coerceAtLeast(0L)
                                taskState.updateRuntimeByteMetrics(task, plannedBytes)
                                updateProgressMax()
                                discoveredFiles += model
                            }
                        }

                        if (dirEntries.isNotEmpty()) {
                            for (chunk in dirEntries.chunked(30)) {
                                for ((info, relativePath) in chunk) {
                                    ensureRunning()
                                    val destPath = buildDestPath(destFileSimpleInfo.path, relativePath)
                                    taskState.putCreatingFolderProgress(task, destPath)
                                    val result = withBlockingFileIo { FileUtils.createFolder(FileAccessPermission.Allowed, destPath) }
                                    result.fold(
                                        onSuccess = { created ->
                                            if (created) {
                                                val sourcePath = normalizeSharePath(info.path)
                                                markCopySuccess(
                                                    src = buildRetryFileSimpleInfo(
                                                        path = sourcePath,
                                                        protocol = srcFileSimpleInfo.protocol,
                                                        protocolId = srcFileSimpleInfo.protocolId,
                                                        isDirectory = true,
                                                    ),
                                                    dest = buildRetryFileSimpleInfo(
                                                        path = destPath,
                                                        protocol = destFileSimpleInfo.protocol,
                                                        protocolId = destFileSimpleInfo.protocolId,
                                                        isDirectory = true,
                                                    ),
                                                )
                                            }
                                            updateProgress(
                                                created,
                                                destPath,
                                                if (created) "" else AppStrings.ui_creation_failed,
                                            )
                                        },
                                        onFailure = { failure ->
                                            markCopyFailure(
                                                src = buildRetryFileSimpleInfo(
                                                    path = normalizeSharePath(info.path),
                                                    protocol = srcFileSimpleInfo.protocol,
                                                    protocolId = srcFileSimpleInfo.protocolId,
                                                    isDirectory = true,
                                                ),
                                                dest = buildRetryFileSimpleInfo(
                                                    path = destPath,
                                                    protocol = destFileSimpleInfo.protocol,
                                                    protocolId = destFileSimpleInfo.protocolId,
                                                    isDirectory = true,
                                                ),
                                                message = failure.message.orEmpty(),
                                                fallback = AppStrings.ui_creation_failed,
                                            )
                                            updateProgress(false, destPath, failure.message.orEmpty())
                                        }
                                    )
                                }
                            }
                            taskState.removeResult(task, "")
                        }
                    }
                    taskState.removeResult(task, "")

                    val sourceTraversalError = traversalError
                    if (sourceTraversalError != null) {
                        markCopyFailure(
                            srcFileSimpleInfo,
                            destFileSimpleInfo,
                            sourceTraversalError,
                            AppStrings.ui_failed_read_source_directory,
                        )
                        return@coroutineScope Result.failure(Exception(sourceTraversalError))
                    }

                    if (discovered == 0) {
                        taskState.putValue(task, "progressMax", "1")
                        taskState.putValue(task, "progressCur", "1")
                        return@coroutineScope Result.success(true)
                    }

                    processItemsAdaptive(
                        items = discoveredFiles,
                        config = resolveOperationParallelism(TraversalEndpointKind.Share),
                        ensureRunning = { ensureRunning() },
                        dynamicMaxParallelismProvider = {
                            resolveOperationRuntimeMaxParallelism(TraversalEndpointKind.Share)
                        },
                    ) { entry ->
                        copyDiscoveredFile(entry)
                    }

                    updateProgressMax(force = true)
                    taskState.putValue(task, "progressCur", processed.toString())
                    if (failureCount > 0) {
                        val summary = buildBatchTaskFailureMessage(
                            operation = AppStrings.ui_copy,
                            failureCount = failureCount,
                            firstFailedPath = firstFailedPath,
                            firstError = firstError,
                        )
                        taskState.putResult(task, destFileSimpleInfo.path, summary)
                        Result.failure(Exception(summary))
                    } else {
                        taskState.removeResult(task, destFileSimpleInfo.path)
                        Result.success(successCount == discovered)
                    }
                }
            } catch (e: CancellationException) {
                Result.failure(e)
            }
        }

        if (srcFileSimpleInfo.size == 0L) {
            taskState.putValue(task, "progressMax", "1")
            taskState.putValue(task, "progressCur", "1")
            return withBlockingFileIo { FileUtils.createFile(FileAccessPermission.Allowed, destFileSimpleInfo.path) }
        }

        val maxChunkSize = HttpRouteClientManager.MAX_LENGTH
        val totalChunks = maxOf(1, srcFileSimpleInfo.getChunkCount(maxChunkSize))
        taskState.putValue(task, "progressMax", totalChunks.toString())
        taskState.putValue(task, "progressCur", "0")
        val totalBytes = srcFileSimpleInfo.size
        val startMs = getTimeMillis()
        val rateSampler = TaskProgressRateSampler(initialAt = startMs)
        var lastLogMs = startMs
        var doneBytes = 0L
        var lastChunk = 0

        return try {
            if (totalBytes > 0L) {
                taskState.putResult(
                    task,
                    destFileSimpleInfo.path,
                    AppStrings.task_start_operation.format(operation = AppStrings.ui_download),
                )
            }
            val result = writeBytes(
                path = srcFileSimpleInfo.path,
                requestId = task.key.toString(),
                onChunk = { chunk, offset, _ ->
                    ensureRunning()
                    val writeResult =
                        FileUtils.writeBytes(FileAccessPermission.Allowed, destFileSimpleInfo.path, srcFileSimpleInfo.size, chunk, offset)
                    if (writeResult.isFailure || !writeResult.getOrDefault(false)) {
                        return@writeBytes Result.failure(
                            writeResult.exceptionOrNull() ?: Exception(AppStrings.ui_writing_file_failed)
                        )
                    }
                    doneBytes += chunk.size
                    val completedBlocks = calculateCompletedBlocks(
                        doneBytes = doneBytes,
                        totalBytes = totalBytes,
                        maxChunkSize = maxChunkSize,
                        totalBlocks = totalChunks.toLong()
                    )
                    val now = getTimeMillis()
                    if (now - lastLogMs >= 1000 || doneBytes >= totalBytes) {
                        val rateSample = rateSampler.update(
                            completed = doneBytes,
                            total = totalBytes,
                            now = now,
                            force = totalBytes in 1..doneBytes,
                        )
                        val resultText =
                            AppStrings.message_task_progress_with_metrics.format(
                                progress = AppStrings.ui_download_progress,
                                percent = doneBytes.formatPercent(totalBytes),
                                current = completedBlocks.toString(),
                                total = totalChunks.toString(),
                                speed = rateSample.speedPerSecond.formatSpeed(),
                                remaining = rateSample.etaMs.formatDuration(),
                            )
                        taskState.putResult(task, destFileSimpleInfo.path, resultText)
                        lastLogMs = now
                    }
                    val currentChunk = completedBlocks.toInt()
                    if (currentChunk != lastChunk) {
                        lastChunk = currentChunk
                        taskState.putValue(task, "progressCur", currentChunk.toString())
                    }
                    Result.success(Unit)
                }
            )
            if (result.isSuccess && result.getOrDefault(false)) {
                markCopySuccess(srcFileSimpleInfo, destFileSimpleInfo)
            } else {
                markCopyFailure(
                    srcFileSimpleInfo,
                    destFileSimpleInfo,
                    result.exceptionOrNull().toTaskFailureMessage(AppStrings.ui_copy_failed),
                    AppStrings.ui_copy_failed,
                )
            }
            result
        } catch (e: CancellationException) {
            Result.failure(e)
        }
    }

    /**
     * 下载指定路径内容并回调分片数据。
     *
     * @param path 目标路径。
     * @param requestId 请求标识，用于覆盖/取消同 ID 请求。
     * @param batchId 批次标识，用于批量取消请求。
     * @param onChunk 分片回调：chunk/offset/contentLength。
     * @return 是否下载成功。
     */
    suspend fun writeBytes(
        path: String,
        requestId: String? = null,
        batchId: String? = null,
        onChunk: suspend (chunk: ByteArray, offset: Long, contentLength: Long?) -> Result<Unit>,
    ): Result<Boolean> {
        return requestRegistry.trackResult(requestId, batchId) {
            try {
                val sessionToken = ensureSessionToken()
                httpClient.prepareRequest {
                    method = HttpMethod.Get
                    applyRequestDefaults(path, sessionToken)
                }.execute { response ->
                    if (!response.status.isSuccess()) {
                        val error = readHttpResponseException(response)
                        LogKit.w(AppStrings.ui_link_download_failed_http_arg0_arg1.format(arg0 = (response.status.value).toString(), arg1 = (error.message).toString()))
                        return@execute Result.failure(error)
                    }
                    val channel = response.bodyAsChannel()
                    val contentLength = response.contentLength()
                    val buffer = ByteArray(HttpRouteClientManager.MAX_LENGTH)
                    var offset = 0L
                    while (true) {
                        val read = channel.readAvailable(buffer, 0, buffer.size)
                        if (read <= 0) break
                        val result = onChunk(buffer.copyOf(read), offset, contentLength)
                        if (result.isFailure) {
                            return@execute Result.failure(
                                result.exceptionOrNull() ?: Exception(AppStrings.ui_file_download_failed)
                            )
                        }
                        offset += read
                    }
                    Result.success(true)
                }
            } catch (e: Exception) {
                LogKit.e(AppStrings.ui_link_download_error_arg0.format(arg0 = (e.message).toString()), e)
                Result.failure(e)
            }
        }
    }

    /**
     * 取消指定批次内的请求。
     *
     * @param batchId 批次标识。
     * @param reason 取消原因。
     * @return 实际取消的请求数量。
     */
    suspend fun cancelBatch(batchId: String, reason: String = AppStrings.ui_cancel_request_batch): Int {
        return requestRegistry.cancelBatch(batchId, reason)
    }

    /**
     * 设置链接分享请求的通用参数（路径、UA、会话）。口令只通过 POST /auth 的 body 提交。
     */
    private fun HttpRequestBuilder.applyRequestDefaults(path: String, sessionToken: String?) {
        val normalizedPath = normalizePath(path)
        url.takeFrom(baseUrl)
        url.encodedPath = normalizedPath
        header("X-API-Request", "true")
        header(HttpHeaders.UserAgent, userAgent)
        if (!sessionToken.isNullOrBlank()) {
            header(LINK_SHARE_SESSION_HEADER, sessionToken)
        }
    }

    private suspend fun ensureSessionToken(): String? {
        if (password.isBlank()) return null
        sessionToken?.let { token -> return token }
        return sessionMutex.withLock {
            sessionToken?.let { token -> return@withLock token }
            val token = authenticateWithPassword()
            sessionToken = token
            token
        }
    }

    private suspend fun authenticateWithPassword(): String {
        val response = authHttpClient.post {
            url.takeFrom(baseUrl)
            url.encodedPath = LINK_SHARE_AUTH_PATH
            header("X-API-Request", "true")
            header(HttpHeaders.UserAgent, userAgent)
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(
                parametersOf(
                    "pwd" to listOf(password),
                    "redirect" to listOf("/"),
                ).formUrlEncode()
            )
        }
        val token = parseLinkShareSessionToken(response.headers.getAll(HttpHeaders.SetCookie).orEmpty())
        if (token != null && (response.status.value == 302 || response.status.isSuccess())) {
            return token
        }
        throw readHttpResponseException(response)
    }

    /**
     * 规范化请求路径，确保以 "/" 开头。
     *
     * @param path 原始路径。
     * @return 规范化后的路径。
     */
    private fun normalizePath(path: String): String {
        val trimmed = path.trim()
        if (trimmed.isEmpty() || trimmed == "/") return "/"
        return if (trimmed.startsWith("/")) trimmed else "/$trimmed"
    }

    /**
     * 内部临时条目，保存原始信息与解码后的相对路径。
     *
     * @property info 原始文件信息。
     * @property relativePath 解码后的相对路径。
     */
    private data class ShareNetworkEntry(
        val info: FileSimpleInfo,
        val relativePath: String,
    )

    /**
     * 规范化分享路径，去除多余 "/" 并保留根目录。
     *
     * @param path 原始路径。
     * @return 规范化后的路径。
     */
    private fun normalizeSharePath(path: String): String {
        val trimmed = path.trim()
        if (trimmed.isEmpty() || trimmed == "/") return "/"
        val normalized = if (trimmed.startsWith("/")) trimmed else "/$trimmed"
        return if (normalized.length > 1) normalized.trimEnd('/') else normalized
    }

    /**
     * 计算相对于根目录的路径。
     *
     * @param rootPath 根目录路径。
     * @param fullPath 完整路径。
     * @return 相对路径（以 "/" 开头）。
     */
    private fun buildRelativePath(rootPath: String, fullPath: String): String {
        val normalizedRoot = normalizeSharePath(rootPath)
        val normalizedFull = normalizeSharePath(fullPath)
        val relative = if (normalizedRoot == "/") {
            normalizedFull
        } else if (normalizedFull.startsWith(normalizedRoot)) {
            normalizedFull.removePrefix(normalizedRoot)
        } else {
            normalizedFull
        }
        if (relative.isEmpty()) return ""
        return if (relative.startsWith("/")) relative else "/$relative"
    }

    /**
     * 将相对路径转换为本地目标路径。
     *
     * @param basePath 目标根路径。
     * @param relativePath 相对路径。
     * @return 本地目标路径。
     */
    private fun buildDestPath(basePath: String, relativePath: String): String {
        val separator = PathUtils.getPathSeparator()
        val baseTrimmed = basePath.trimEnd('/', '\\')
        val normalizedBase = baseTrimmed.ifEmpty { separator }
        val normalizedRelative = relativePath
            .trimStart('/', '\\')
            .replace("/", separator)
            .replace("\\", separator)
        if (normalizedRelative.isEmpty()) return normalizedBase
        val joiner = if (normalizedBase.endsWith(separator)) "" else separator
        return normalizedBase + joiner + normalizedRelative
    }
}

private const val LINK_SHARE_AUTH_PATH = "/auth"
private const val LINK_SHARE_SESSION_HEADER = "X-FolderSpan-Link-Session"
private const val LINK_SHARE_SESSION_COOKIE = "FolderSpanLinkShareSession"

internal fun parseLinkShareSessionToken(setCookieValues: List<String>): String? {
    setCookieValues.forEach { header ->
        header.split(';').firstOrNull()?.let { pair ->
            val name = pair.substringBefore('=').trim()
            val value = pair.substringAfter('=', missingDelimiterValue = "").trim()
            if (name == LINK_SHARE_SESSION_COOKIE && isSafeLinkShareSessionToken(value)) {
                return value
            }
        }
    }
    return null
}

private fun isSafeLinkShareSessionToken(value: String): Boolean {
    return value.length in 16..128 && value.all { item ->
        item in 'a'..'z' || item in 'A'..'Z' || item in '0'..'9' || item == '-' || item == '_'
    }
}
