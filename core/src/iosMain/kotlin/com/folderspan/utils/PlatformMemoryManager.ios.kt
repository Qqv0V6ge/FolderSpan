package com.folderspan.utils

import kotlin.native.runtime.GC
import kotlin.native.runtime.NativeRuntimeApi

internal actual object PlatformMemoryManager {
    @OptIn(NativeRuntimeApi::class)
    actual fun releaseUnusedMemory() {
        GC.collect()
    }
}
