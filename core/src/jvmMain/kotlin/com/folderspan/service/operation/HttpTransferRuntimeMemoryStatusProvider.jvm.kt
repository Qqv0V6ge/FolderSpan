package com.folderspan.service.operation

private const val LOW_HEAP_GC_TRIGGER_BYTES = 64L * 1024L * 1024L

internal actual object HttpTransferRuntimeMemoryStatusProvider {
    actual fun sample(): HttpTransferRuntimeMemoryStatus {
        val runtime = Runtime.getRuntime()
        var maxHeapBytes = runtime.maxMemory()
        var availableHeapBytes = runtime.availableHeapBytes(maxHeapBytes)
        if (availableHeapBytes < LOW_HEAP_GC_TRIGGER_BYTES) {
            runtime.gc()
            maxHeapBytes = runtime.maxMemory()
            availableHeapBytes = runtime.availableHeapBytes(maxHeapBytes)
        }
        return HttpTransferRuntimeMemoryStatus(
            availableHeapBytes = availableHeapBytes,
            maxHeapBytes = maxHeapBytes,
        )
    }

    private fun Runtime.availableHeapBytes(maxHeapBytes: Long): Long {
        val usedHeapBytes = (totalMemory() - freeMemory()).coerceAtLeast(0L)
        return (maxHeapBytes - usedHeapBytes).coerceAtLeast(0L)
    }
}
