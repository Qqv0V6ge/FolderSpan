package com.folderspan.service.http.client

import com.folderspan.service.operation.*
import com.folderspan.ui.state.main.*
import kotlinx.coroutines.*
import strings.AppStrings

internal const val DEVICE_COPY_CHUNK_BYTES = HttpRouteClientManager.DEVICE_DIRECT_MAX_LENGTH
internal const val DEVICE_COPY_MIN_CHUNK_BYTES = 64 * 1024
internal const val DEVICE_COPY_CHUNK_ALIGNMENT_BYTES = 1024 * 1024
internal const val DEVICE_COPY_MAX_CHUNK_BYTES = HttpRouteClientManager.DEVICE_DIRECT_MAX_LENGTH
internal const val DEVICE_COPY_DOWNLOAD_READ_BYTES_MAX = 4 * 1024 * 1024
internal const val DEVICE_COPY_STREAM_CHUNK_BYTES = HttpRouteClientManager.DEVICE_DIRECT_STREAM_TARGET_RANGE_BYTES
internal const val DEVICE_COPY_MAX_CONCURRENT_REQUESTS = HttpRouteClientManager.DEVICE_DIRECT_MAX_PARALLEL_REQUESTS
internal const val DEVICE_COPY_STREAM_MAX_CONCURRENT_REQUESTS = HttpRouteClientManager.DEVICE_DIRECT_STREAM_PARALLEL_REQUESTS
internal const val DEVICE_COPY_MAX_IN_FLIGHT_BYTES = HttpRouteClientManager.DEVICE_DIRECT_MAX_IN_FLIGHT_BYTES
internal const val DEVICE_COPY_PROGRESS_BYTES = 1024 * 1024

internal enum class PathTransferLogKind {
    Upload,
    Download,
    DeviceTransfer,
}

internal fun shouldWritePathTransferInfoLog(kind: PathTransferLogKind): Boolean {
    return kind != PathTransferLogKind.Download
}

internal enum class DeviceDirectReadRoute {
    ReadBytes,
    StreamFile,
}

internal fun selectDeviceDirectReadRoute(
    expectedSize: Long,
    plan: AdaptiveHttpTransferPlan,
): DeviceDirectReadRoute {
    return if (expectedSize <= plan.byteChunkBytes.toLong()) {
        DeviceDirectReadRoute.ReadBytes
    } else {
        DeviceDirectReadRoute.StreamFile
    }
}

internal fun selectDeviceDirectDownloadToFileRoute(expectedSize: Long): DeviceDirectReadRoute {
    return if (expectedSize <= DEVICE_COPY_DOWNLOAD_READ_BYTES_MAX.toLong()) {
        DeviceDirectReadRoute.ReadBytes
    } else {
        DeviceDirectReadRoute.StreamFile
    }
}

internal fun shouldFallbackDeviceDirectStreamFailure(error: Throwable): Boolean {
    return error !is CancellationException && !error.isTaskLevelTransferFailure()
}

internal fun remainingDeviceDirectFallbackRanges(
    totalBytes: Long,
    streamChunkBytes: Int,
    fallbackChunkBytes: Int,
    streamWrittenBytes: LongArray,
): List<Pair<Long, Long>> {
    if (totalBytes <= 0L) return emptyList()
    val safeStreamChunkBytes = streamChunkBytes.coerceAtLeast(1).toLong()
    val safeFallbackChunkBytes = fallbackChunkBytes.coerceAtLeast(1).toLong()
    val totalBlocks = httpSegmentCount(totalBytes, streamChunkBytes)
    val ranges = mutableListOf<Pair<Long, Long>>()
    repeat(totalBlocks) { segmentIndex ->
        val segmentStart = segmentIndex * safeStreamChunkBytes
        val segmentEnd = minOf(segmentStart + safeStreamChunkBytes, totalBytes)
        val writtenBytes = streamWrittenBytes
            .getOrNull(segmentIndex)
            ?.coerceIn(0L, segmentEnd - segmentStart)
            ?: 0L
        var cursor = segmentStart + writtenBytes
        while (cursor < segmentEnd) {
            val next = minOf(cursor + safeFallbackChunkBytes, segmentEnd)
            ranges += cursor to next
            cursor = next
        }
    }
    return ranges
}

internal fun launchPathCopyControlMonitor(block: suspend CoroutineScope.() -> Unit): Job {
    return CoroutineScope(SupervisorJob() + Dispatchers.Main).launch(block = block)
}

internal fun Throwable?.isMissingSourceFileFailure(): Boolean {
    var current = this
    while (current != null) {
        val message = current.message.orEmpty().lowercase()
        if (
            message.contains(AppStrings.ui_file_does_not_exist.lowercase()) ||
            message.contains(AppStrings.ui_not_found_file.lowercase()) ||
            message.contains(AppStrings.ui_path_does_not_exist.lowercase()) ||
            message.contains(AppStrings.ui_file_does_not_exist) ||
            message.contains(AppStrings.ui_not_found_file) ||
            message.contains(AppStrings.ui_path_does_not_exist) ||
            message.contains("no such file") ||
            message.contains("not found")
        ) {
            return true
        }
        current = current.cause
    }
    return false
}

internal fun deviceDirectPathCopyChunkBytes(recommendedChunkBytes: Int? = null): Int {
    val advertisedChunkBytes = recommendedChunkBytes ?: DEVICE_COPY_CHUNK_BYTES
    val targetChunkBytes = (
        (DEVICE_COPY_MAX_IN_FLIGHT_BYTES.toLong() / DEVICE_COPY_MAX_CONCURRENT_REQUESTS) /
            DEVICE_COPY_CHUNK_ALIGNMENT_BYTES *
            DEVICE_COPY_CHUNK_ALIGNMENT_BYTES
        )
        .coerceAtLeast(DEVICE_COPY_MIN_CHUNK_BYTES.toLong())
        .coerceAtMost(DEVICE_COPY_MAX_CHUNK_BYTES.toLong())
        .toInt()
    val boundedChunkBytes = minOf(advertisedChunkBytes, targetChunkBytes)
        .coerceIn(DEVICE_COPY_MIN_CHUNK_BYTES, DEVICE_COPY_MAX_CHUNK_BYTES)
    return HttpTransferRuntimeTuning.plan(
        maxChunkBytes = boundedChunkBytes,
        maxParallelRequests = DEVICE_COPY_MAX_CONCURRENT_REQUESTS,
    ).recommendedChunkBytes.coerceIn(DEVICE_COPY_MIN_CHUNK_BYTES, boundedChunkBytes)
}
