package com.folderspan.service.operation

import android.app.ActivityManager
import android.content.Context
import com.folderspan.androidContext

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
        val systemLowMemory = runCatching {
            val manager = androidContext().getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val info = ActivityManager.MemoryInfo()
            manager?.getMemoryInfo(info)
            info.lowMemory
        }.getOrDefault(false)
        return HttpTransferRuntimeMemoryStatus(
            availableHeapBytes = availableHeapBytes,
            maxHeapBytes = maxHeapBytes,
            lowMemory = systemLowMemory,
        )
    }

    private fun Runtime.availableHeapBytes(maxHeapBytes: Long): Long {
        val usedHeapBytes = (totalMemory() - freeMemory()).coerceAtLeast(0L)
        return (maxHeapBytes - usedHeapBytes).coerceAtLeast(0L)
    }
}
