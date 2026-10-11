package com.folderspan.utils

internal actual object PlatformMemoryManager {
    actual fun releaseUnusedMemory() {
        Runtime.getRuntime().gc()
    }
}
