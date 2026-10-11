package com.folderspan.ui.state.main

import platform.Foundation.NSRecursiveLock

internal actual class TaskRuntimeStoreLock actual constructor() {
    private val delegate = NSRecursiveLock()

    actual fun lock() {
        delegate.lock()
    }

    actual fun unlock() {
        delegate.unlock()
    }
}
