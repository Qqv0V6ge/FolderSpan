package com.folderspan.ui.state.main

import java.util.concurrent.locks.ReentrantLock

internal actual class TaskRuntimeStoreLock actual constructor() {
    private val delegate = ReentrantLock()

    actual fun lock() {
        delegate.lock()
    }

    actual fun unlock() {
        delegate.unlock()
    }
}
