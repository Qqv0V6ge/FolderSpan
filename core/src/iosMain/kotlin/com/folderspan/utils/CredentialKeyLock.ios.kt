package com.folderspan.utils

import platform.Foundation.NSRecursiveLock

internal actual class CredentialKeyLock actual constructor() {
    private val delegate = NSRecursiveLock()

    actual fun lock() = delegate.lock()

    actual fun unlock() = delegate.unlock()
}
