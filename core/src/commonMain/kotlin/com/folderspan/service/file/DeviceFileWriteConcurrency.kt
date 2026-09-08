package com.folderspan.service.file

private const val SAME_TARGET_FILE_WRITE_PARALLELISM = 1

internal fun sameTargetFileWriteParallelism(requestedParallelism: Int): Int {
    return minOf(
        requestedParallelism.coerceAtLeast(1),
        SAME_TARGET_FILE_WRITE_PARALLELISM,
    )
}
