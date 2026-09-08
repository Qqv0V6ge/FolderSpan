package com.folderspan.service.http.client

import com.folderspan.utils.FileAccessPermission
import com.folderspan.service.operation.HttpTransferRuntimeTuning
import com.folderspan.service.operation.OperationParallelismConfig
import com.folderspan.service.operation.processItemsAdaptive
import com.folderspan.ui.state.main.isTaskLevelTransferFailure
import com.folderspan.utils.FileUtils
import com.folderspan.utils.LogKit
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.EOFException
import strings.AppStrings

internal fun httpSegmentCount(totalBytes: Long, segmentBytes: Int): Int {
    if (totalBytes <= 0L) return 0
    val safeSegmentBytes = segmentBytes.coerceAtLeast(1).toLong()
    val count = ((totalBytes - 1L) / safeSegmentBytes) + 1L
    return count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}

internal fun httpStreamWorkerCount(
    chunkCount: Int,
    recommendedParallelRequests: Int?,
    maxParallelRequests: Int,
): Int {
    val safeMaxParallelRequests = maxParallelRequests.coerceAtLeast(1)
    val statusBound = recommendedParallelRequests
        ?.coerceIn(1, safeMaxParallelRequests)
        ?: safeMaxParallelRequests
    val runtimeBound = HttpTransferRuntimeTuning.plan(
        maxChunkBytes = HttpRouteClientManager.MAX_LENGTH,
        maxParallelRequests = safeMaxParallelRequests,
    ).recommendedParallelRequests.coerceIn(1, safeMaxParallelRequests)
    return minOf(statusBound, runtimeBound, safeMaxParallelRequests, chunkCount).coerceAtLeast(1)
}

internal suspend fun processHttpChunksUntilFailure(
    chunkCount: Int,
    parallelism: Int,
    ensureActive: suspend () -> Unit,
    operationConfig: OperationParallelismConfig? = null,
    dynamicMaxParallelismProvider: (() -> Int)? = null,
    block: suspend (index: Int) -> Boolean,
): Boolean = coroutineScope {
    if (chunkCount <= 0) return@coroutineScope true
    val stateMutex = Mutex()
    var allOk = true
    var shouldStop = false
    val hardWorkerCount = minOf(
        operationConfig?.hardMaxParallelism ?: parallelism.coerceAtLeast(1),
        chunkCount,
    ).coerceAtLeast(1)
    val workerConfig = (operationConfig ?: OperationParallelismConfig(
            initialParallelism = parallelism.coerceAtLeast(1),
            maxParallelism = hardWorkerCount,
            queueCapacity = hardWorkerCount,
            hardMaxParallelism = hardWorkerCount,
        )).copy(
        initialParallelism = minOf(operationConfig?.initialParallelism ?: parallelism, parallelism, hardWorkerCount),
        maxParallelism = minOf(operationConfig?.maxParallelism ?: hardWorkerCount, hardWorkerCount),
        queueCapacity = maxOf(operationConfig?.queueCapacity ?: hardWorkerCount, hardWorkerCount),
        hardMaxParallelism = hardWorkerCount,
    ).normalized()
    class StopHttpChunkProcessing : Exception()

    suspend fun stopRequested(): Boolean = stateMutex.withLock { shouldStop }

    suspend fun markFailed() {
        stateMutex.withLock {
            allOk = false
            shouldStop = true
        }
    }

    try {
        processItemsAdaptive(
            items = (0 until chunkCount).toList(),
            config = workerConfig,
            ensureRunning = ensureActive,
            dynamicMaxParallelismProvider = dynamicMaxParallelismProvider?.let { provider ->
                { provider().coerceIn(1, hardWorkerCount) }
            },
        ) { currentIndex ->
            if (!stopRequested()) {
                ensureActive()
                val ok = block(currentIndex)
                if (!ok) {
                    markFailed()
                    throw StopHttpChunkProcessing()
                }
            }
        }
    } catch (_: StopHttpChunkProcessing) {
        markFailed()
    }

    ensureActive()
    stateMutex.withLock { allOk }
}

internal fun shouldLogHttpRangeTransferFailureAsError(error: Throwable): Boolean {
    return !error.isTaskLevelTransferFailure()
}

internal suspend fun streamHttpRangeToFile(
    httpClient: HttpClient,
    endpoint: String,
    requestBody: ByteArray,
    expectedSize: Long,
    maxExpectedSize: Long,
    maxExpectedSizeError: String,
    localPath: String,
    fileSize: Long,
    localOffset: Long,
    bufferSize: Int,
    requestTimeoutMs: Long,
    connectTimeoutMs: Long,
    socketTimeoutMs: Long,
    updateTransferStatus: (HttpResponse) -> Unit,
    verifyByteStream: (HttpResponse, Long) -> Unit,
    encryptedTransport: Boolean = false,
    failureLogMessage: (HttpResponse, Exception) -> String,
    exceptionLogMessage: (Exception) -> String,
    onBytesWritten: suspend (offset: Long, bytesWritten: Int) -> Unit,
): Result<Boolean> {
    return try {
        if (expectedSize > maxExpectedSize) {
            throw Exception(maxExpectedSizeError)
        }
        val statement = httpClient.preparePost(endpoint) {
            timeout {
                requestTimeoutMillis = requestTimeoutMs
                connectTimeoutMillis = connectTimeoutMs
                socketTimeoutMillis = socketTimeoutMs
            }
            accept(ContentType.Application.OctetStream)
            setFolderSpanRequestBody(requestBody, encryptedTransport)
        }
        statement.execute { response ->
            if (!response.status.isSuccess()) {
                updateTransferStatus(response)
                val error = readHttpResponseException(response)
                LogKit.w(failureLogMessage(response, error))
                throw error
            }

            updateTransferStatus(response)
            verifyByteStream(response, expectedSize)
            val safeBufferSize = HttpTransferRuntimeTuning.plan(
                maxChunkBytes = bufferSize,
                maxParallelRequests = 1,
            ).recommendedChunkBytes.coerceIn(1, bufferSize.coerceAtLeast(1))
            val channel = response.bodyAsChannel()
            val write = FileUtils.writeByteStream(FileAccessPermission.Allowed,
                path = localPath,
                fileSize = fileSize,
                startOffset = localOffset,
                expectedBytes = expectedSize,
                bufferSize = safeBufferSize,
                readNext = { buffer, length ->
                    channel.readAvailable(buffer, 0, length)
                },
                onBytesWritten = onBytesWritten,
            )
            if (write.isFailure || !write.getOrDefault(false)) {
                throw write.exceptionOrNull() ?: Exception(AppStrings.ui_write_failed)
            }
            Result.success(true)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        val message = exceptionLogMessage(e)
        if (shouldLogHttpRangeTransferFailureAsError(e)) {
            LogKit.e(message, e)
        } else {
            LogKit.w(message)
        }
        Result.failure(e)
    }
}

internal suspend fun ByteReadChannel.readHttpRawByteRangeInto(
    expectedLength: Long,
    target: ByteArray,
    endOfStreamMessage: String,
): Long {
    if (expectedLength != target.size.toLong()) {
        throw IllegalArgumentException(AppStrings.ui_target_buffer_length_mismatch)
    }
    var remaining = expectedLength
    var totalRead = 0L
    var writeOffset = 0
    while (remaining > 0L) {
        val read = readAvailable(target, writeOffset, minOf(Int.MAX_VALUE.toLong(), remaining).toInt())
        if (read < 0) {
            throw EOFException(endOfStreamMessage)
        }
        if (read == 0) continue
        writeOffset += read
        remaining -= read
        totalRead += read
    }
    return totalRead
}

internal suspend fun ByteReadChannel.readHttpRawByteRange(
    expectedLength: Long,
    bufferBytes: Int,
    endOfStreamMessage: String,
    onChunk: suspend (ByteArray) -> Unit,
): Long {
    var remaining = expectedLength
    var totalRead = 0L
    val safeBufferBytes = HttpTransferRuntimeTuning.plan(
        maxChunkBytes = bufferBytes,
        maxParallelRequests = 1,
    ).recommendedChunkBytes.coerceIn(1, bufferBytes.coerceAtLeast(1))
    val buffer = ByteArray(safeBufferBytes)
    while (remaining > 0L) {
        val toRead = minOf(buffer.size.toLong(), remaining).toInt()
        val read = readAvailable(buffer, 0, toRead)
        if (read < 0) {
            throw EOFException(endOfStreamMessage)
        }
        if (read == 0) continue
        onChunk(buffer.copyOf(read))
        remaining -= read
        totalRead += read
    }
    return totalRead
}
