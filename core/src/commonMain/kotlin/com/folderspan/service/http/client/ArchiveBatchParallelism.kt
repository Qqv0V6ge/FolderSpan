package com.folderspan.service.http.client

import com.folderspan.service.operation.OperationParallelismConfig

internal const val ARCHIVE_TRANSFER_MAX_CONCURRENT_BATCHES = 8

internal fun archiveTransferBatchOperationConfig(
    baseConfig: OperationParallelismConfig,
    batchCount: Int,
    maxConcurrentBatches: Int = ARCHIVE_TRANSFER_MAX_CONCURRENT_BATCHES,
): OperationParallelismConfig {
    val runtimeMax = archiveTransferBatchRuntimeMax(
        runtimeMaxParallelism = baseConfig.maxParallelism,
        batchCount = batchCount,
        maxConcurrentBatches = maxConcurrentBatches,
    )
    val hardMax = archiveTransferBatchRuntimeMax(
        runtimeMaxParallelism = baseConfig.hardMaxParallelism,
        batchCount = batchCount,
        maxConcurrentBatches = maxConcurrentBatches,
    )
    return baseConfig.copy(
        initialParallelism = minOf(baseConfig.initialParallelism, runtimeMax, hardMax),
        maxParallelism = minOf(runtimeMax, hardMax),
        queueCapacity = maxOf(baseConfig.queueCapacity, hardMax * 2, 4),
        hardMaxParallelism = hardMax,
    ).normalized()
}

internal fun archiveTransferBatchRuntimeMax(
    runtimeMaxParallelism: Int,
    batchCount: Int,
    maxConcurrentBatches: Int = ARCHIVE_TRANSFER_MAX_CONCURRENT_BATCHES,
): Int =
    minOf(
        runtimeMaxParallelism.coerceAtLeast(1),
        batchCount.coerceAtLeast(1),
        maxConcurrentBatches.coerceAtLeast(1),
    ).coerceAtLeast(1)
