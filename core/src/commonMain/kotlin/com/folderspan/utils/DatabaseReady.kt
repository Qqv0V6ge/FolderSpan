package com.folderspan.utils

import kotlinx.coroutines.CompletableDeferred

object DatabaseReady {
    private val ready = CompletableDeferred<Unit>()

    fun markReady() {
        if (!ready.isCompleted) {
            ready.complete(Unit)
        }
    }

    fun markFailed(error: Throwable) { ready.completeExceptionally(error) }

    suspend fun await() {
        ready.await()
    }
}
