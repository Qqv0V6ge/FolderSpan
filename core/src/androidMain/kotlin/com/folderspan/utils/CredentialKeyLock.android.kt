package com.folderspan.utils

import java.util.concurrent.locks.ReentrantLock

internal actual class CredentialKeyLock actual constructor() {
    private val delegate = ReentrantLock()

    actual fun lock() = delegate.lock()

    actual fun unlock() = delegate.unlock()
}
