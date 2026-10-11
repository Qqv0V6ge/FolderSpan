package com.folderspan.utils

internal expect object PlatformMemoryManager {
    /**
     * 在一次性的大批量工作结束、临时引用已经释放后，请求平台尽快回收未使用内存。
     */
    fun releaseUnusedMemory()
}
